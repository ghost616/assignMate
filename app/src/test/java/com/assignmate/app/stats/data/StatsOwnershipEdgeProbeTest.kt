package com.assignmate.app.stats.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.stats.domain.HistoryQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离朱补充边界探针（不改动被测代码，仅新增用例）：
 * 覆盖计划主用例未涉及的**边界与退化路径**——非正学生 id、家长会话缺失 parentId、
 * 未登记档案的学生、学生会话查看他人作业、入参与会话校验的优先级、
 * 归属通过后的数据读取异常收敛（不得吞异常、不得越权放行）。
 *
 * 复用模块既有测试替身 [StatsRepositoryTestEnv] / [FakeStatsAuthRepository] 等，
 * 时钟/时区口径与之一致（2023-11-14 00:09 +08:00 / Asia/Shanghai）。
 */
class StatsOwnershipEdgeProbeTest {

    private val dayEpoch = StatsRepositoryTestEnv.DAY_EPOCH

    /** 家长会话（不携带学生维度），学生档案沿用测试环境默认登记 */
    private fun parentEnv(): StatsRepositoryTestEnv = StatsRepositoryTestEnv(
        SessionState(role = Role.PARENT, parentId = StatsRepositoryTestEnv.PARENT_ID, studentId = null),
    )

    // ---- 边界：非正学生 id（归属口径直接拒绝，不触达取数） ----

    @Test
    fun `非正学生 id 在统计入口均拒绝且不触达取数`() = runTest {
        val env = parentEnv()
        listOf(0L, -1L).forEach { invalidId ->
            assertEquals(
                "summarizeDay($invalidId)",
                StatsFailure.ACCESS_DENIED,
                (env.repository.summarizeDay(invalidId, dayEpoch) as StatsResult.Failure).reason,
            )
            assertEquals(
                "history($invalidId)",
                StatsFailure.ACCESS_DENIED,
                (env.repository.history(invalidId, HistoryQuery(dayEpoch)) as StatsResult.Failure).reason,
            )
        }
        assertFalse(env.authRepository.isStudentOwnedBy(StatsRepositoryTestEnv.PARENT_ID, 0L))
        assertFalse(env.authRepository.isStudentOwnedBy(StatsRepositoryTestEnv.PARENT_ID, -1L))
    }

    // ---- 边界：家长会话缺 parentId ----

    @Test
    fun `家长会话缺 parentId 时视为不可见并收敛为越权拒绝`() = runTest {
        val env = StatsRepositoryTestEnv(
            SessionState(role = Role.PARENT, parentId = null, studentId = null),
        )
        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch) as StatsResult.Failure).reason,
        )
        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.history(StatsRepositoryTestEnv.STUDENT_ID, HistoryQuery(dayEpoch))
                as StatsResult.Failure).reason,
        )
    }

    // ---- 边界：未登记档案的学生 ----

    @Test
    fun `未登记档案的学生不因同一家长而放行`() = runTest {
        val env = parentEnv()
        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.summarizeDay(NOT_REGISTERED_ID, dayEpoch) as StatsResult.Failure).reason,
        )
        // 作业不存在：先取作业得到 HOMEWORK_NOT_FOUND，不因归属解析而崩溃
        assertEquals(
            StatsFailure.HOMEWORK_NOT_FOUND,
            (env.repository.itemDetail(404L) as StatsResult.Failure).reason,
        )
    }

    // ---- 反向：学生会话查看他人作业 ----

    @Test
    fun `学生会话查看他人作业详情被拒绝且不泄露数据`() = runTest {
        val env = StatsRepositoryTestEnv() // 会话学生 = STUDENT_ID
        env.homeworkRepository.put(
            statsTestHomework(
                id = 1L,
                studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID,
                status = HomeworkStatus.COMPLETED,
            ),
        )
        env.timerRepository.putSession(
            statsTestSession(
                sessionId = 10L,
                homeworkId = 1L,
                startedAtMillis = StatsRepositoryTestEnv.at(0),
                finishedAtMillis = StatsRepositoryTestEnv.at(10),
                studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID,
            ),
        )

        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.itemDetail(1L) as StatsResult.Failure).reason,
        )
    }

    // ---- 边界：家长名下多名学生时，学生维度入口按显式入参分别取数 ----

    @Test
    fun `家长名下多名学生时汇总按显式学员维度分别取数且互不串号`() = runTest {
        val env = parentEnv()
        env.authRepository.addStudentProfile(
            id = SIBLING_ID,
            parentAccountId = StatsRepositoryTestEnv.PARENT_ID,
            name = "小刚",
        )
        // 姐姐名下有一条当日完成作业；妹妹名下无任何数据
        env.homeworkRepository.put(
            statsTestHomework(
                id = 1L,
                studentId = StatsRepositoryTestEnv.STUDENT_ID,
                status = HomeworkStatus.COMPLETED,
            ),
        )
        env.timerRepository.putSession(
            statsTestSession(
                sessionId = 10L,
                homeworkId = 1L,
                startedAtMillis = StatsRepositoryTestEnv.at(0),
                finishedAtMillis = StatsRepositoryTestEnv.at(10),
                studentId = StatsRepositoryTestEnv.STUDENT_ID,
            ),
        )

        val elder = (env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch)
            as StatsResult.Success).data
        val younger = (env.repository.summarizeDay(SIBLING_ID, dayEpoch) as StatsResult.Success).data

        assertEquals(listOf(1L), elder.items.map { it.id })
        assertEquals(StatsRepositoryTestEnv.STUDENT_ID, elder.items.single().studentId)
        assertTrue("妹妹名下无数据应返回空盘点（非失败）", younger.isEmpty)
        assertEquals(emptyList<Long>(), younger.items.map { it.id })
        // 他人家长的学生仍被拒绝
        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.summarizeDay(StatsRepositoryTestEnv.OTHER_STUDENT_ID, dayEpoch)
                as StatsResult.Failure).reason,
        )
    }

    // ---- 反向：读取异常收敛（汇总链路 READ_FAILED；详情链路见下方发现项） ----

    @Test
    fun `归属校验通过后汇总链路读取异常收敛为读取失败而非越权`() = runTest {
        val env = parentEnv()
        env.homeworkRepository.put(statsTestHomework(1L, studentId = StatsRepositoryTestEnv.STUDENT_ID))
        env.homeworkRepository.failReads = true
        env.timerRepository.failReads = true

        assertEquals(
            StatsFailure.READ_FAILED,
            (env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch) as StatsResult.Failure).reason,
        )
        assertEquals(
            StatsFailure.READ_FAILED,
            (env.repository.history(StatsRepositoryTestEnv.STUDENT_ID, HistoryQuery(dayEpoch))
                as StatsResult.Failure).reason,
        )
    }

    /**
     * 离朱发现项（**非本次改造引入**，基线行为一致）：
     * `itemDetail` 首个取数 `runCatching { homeworkRepository.getHomework(homeworkId) }.getOrNull()`
     * 会把**读取异常**与**作业不存在**一并折叠成 `HOMEWORK_NOT_FOUND`（见 StatsRepositoryImpl 第 73-74 行，
     * 与 `git show HEAD` 的改造前实现逐字相同）。即：详情入口在数据库读取异常时不返回
     * [StatsFailure.READ_FAILED]，与计划「数据读取异常仍统一收敛为 READ_FAILED」的表述存在口径差异
     * （汇总/历史链路的取数异常确实收敛为 READ_FAILED，已由上一个用例覆盖）。
     *
     * 本用例按**实际行为**断言并固化现状，避免把既有口径当作本次改造的回归；
     * 若后续要求详情链路也区分「读取失败」，应同时修改实现与该用例。
     */
    @Test
    fun `详情入口取数异常与作业不存在同为作业不存在`() = runTest {
        val env = parentEnv()
        env.homeworkRepository.put(statsTestHomework(1L, studentId = StatsRepositoryTestEnv.STUDENT_ID))
        env.homeworkRepository.failReads = true

        assertEquals(
            StatsFailure.HOMEWORK_NOT_FOUND,
            (env.repository.itemDetail(1L) as StatsResult.Failure).reason,
        )
    }

    // ---- 优先级：入参非法、越权先于取数 ----

    @Test
    fun `历史查询入参非法先于归属校验返回`() = runTest {
        val env = parentEnv()
        assertEquals(
            StatsFailure.INVALID_QUERY,
            (env.repository.history(
                StatsRepositoryTestEnv.OTHER_STUDENT_ID,
                HistoryQuery(startEpochDay = dayEpoch, endEpochDay = dayEpoch - 1L),
            ) as StatsResult.Failure).reason,
        )
    }

    @Test
    fun `越权判定先于取数即读取异常也不放行他人数据`() = runTest {
        val env = parentEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID),
        )
        // 仅计时读取失败：作业读取正常，可确认越权判定未被取数异常掩盖
        env.timerRepository.failReads = true

        // 他人学生：归属校验先失败，返回越权而非读取失败，也不泄露数据
        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.summarizeDay(StatsRepositoryTestEnv.OTHER_STUDENT_ID, dayEpoch)
                as StatsResult.Failure).reason,
        )
        assertEquals(
            StatsFailure.ACCESS_DENIED,
            (env.repository.history(StatsRepositoryTestEnv.OTHER_STUDENT_ID, HistoryQuery(dayEpoch))
                as StatsResult.Failure).reason,
        )
    }

    @Test
    fun `归属通过后计时读取失败在详情入口收敛为读取失败而非越权`() = runTest {
        val env = parentEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = StatsRepositoryTestEnv.STUDENT_ID),
        )
        env.timerRepository.failReads = true

        assertEquals(
            StatsFailure.READ_FAILED,
            (env.repository.itemDetail(1L) as StatsResult.Failure).reason,
        )
    }

    // ---- 无会话：三个入口口径一致 ----

    @Test
    fun `无会话时三个入口均为无会话失败`() = runTest {
        val env = StatsRepositoryTestEnv(SessionState.NONE)
        assertEquals(
            StatsFailure.NO_ACTIVE_SESSION,
            (env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch) as StatsResult.Failure).reason,
        )
        assertEquals(
            StatsFailure.NO_ACTIVE_SESSION,
            (env.repository.history(StatsRepositoryTestEnv.STUDENT_ID, HistoryQuery(dayEpoch))
                as StatsResult.Failure).reason,
        )
        assertEquals(
            StatsFailure.NO_ACTIVE_SESSION,
            (env.repository.itemDetail(1L) as StatsResult.Failure).reason,
        )
    }

    private companion object {
        const val SIBLING_ID = 6L
        const val NOT_REGISTERED_ID = 999L
    }
}
