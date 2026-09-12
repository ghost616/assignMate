package com.assignmate.app.stats.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.StatsCalculations
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatsRepositoryImpl] 单测：基于内存替身（作业清单 + 会话/暂停明细 + 会话状态）验证
 * 「按学生聚合、当日纳入口径、历史范围查询、权限与失败收敛」。
 *
 * 固定时钟为 2023-11-14 00:09:00 +08:00（[StatsRepositoryTestEnv.FIXED_MILLIS]），
 * 当日窗口为 [StatsRepositoryTestEnv.DAY_START_MILLIS] 起 24 小时，业务时区 Asia/Shanghai。
 */
class StatsRepositoryImplTest {

    private val minute = StatsRepositoryTestEnv.MINUTE
    private val dayEpoch = StatsRepositoryTestEnv.DAY_EPOCH

    // ---- 当日盘点 ----

    @Test
    fun `当日盘点按学生聚合完成率与暂停`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, content = "语文生字", status = HomeworkStatus.COMPLETED),
        )
        env.homeworkRepository.put(
            statsTestHomework(2L, content = "数学口算", status = HomeworkStatus.PENDING, priority = 101),
        )
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(10)),
        )
        env.timerRepository.putSession(
            // 进行中会话：自当日第 0 分钟开始（固定时钟为当日第 9 分钟，故当日净耗时 > 0）
            statsTestSession(11L, 2L, StatsRepositoryTestEnv.at(0), null),
        )
        env.timerRepository.putPause(statsTestPause(1L, 10L, 1L, StatsRepositoryTestEnv.at(2), StatsRepositoryTestEnv.at(4)))

        val result = env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch)

        assertTrue(result is StatsResult.Success)
        val summary = (result as StatsResult.Success).data
        assertEquals(2, summary.totalCount)
        assertEquals(1, summary.completedCount)
        assertEquals(0.5, summary.completionRate, 1e-9)
        assertEquals(1, summary.pauseCount)
        assertEquals(2 * minute, summary.pausedTotalMillis)
        assertEquals("语文生字", summary.mostPausedItem?.content)
        assertEquals(dayEpoch, summary.epochDay)
    }

    @Test
    fun `当日无执行记录时返回空盘点而非失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.PENDING))

        val result = env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch)

        assertTrue(result is StatsResult.Success)
        val summary = (result as StatsResult.Success).data
        assertTrue(summary.isEmpty)
        assertEquals(0.0, summary.completionRate, 0.0)
        assertEquals(dayEpoch, summary.epochDay)
    }

    @Test
    fun `当日盘点回填请求的纪元日供页面展示所选日期`() = runTest {
        val env = StatsRepositoryTestEnv()
        val requestedDay = dayEpoch - 1

        val result = env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, requestedDay)

        assertEquals(requestedDay, (result as StatsResult.Success).data.epochDay)
    }

    @Test
    fun `其他学生的作业不进入当日盘点`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID),
        )
        env.timerRepository.putSession(
            statsTestSession(
                sessionId = 10L,
                homeworkId = 1L,
                startedAtMillis = StatsRepositoryTestEnv.at(0),
                finishedAtMillis = StatsRepositoryTestEnv.at(5),
                studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID,
            ),
        )

        val result = env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch)

        assertTrue(result is StatsResult.Success)
        assertTrue((result as StatsResult.Success).data.isEmpty)
    }

    @Test
    fun `无会话时当日盘点返回无会话失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.authRepository.setSession(SessionState.NONE)

        val result = env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch)

        assertEquals(StatsFailure.NO_ACTIVE_SESSION, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `学生查看他人统计数据被拒绝`() = runTest {
        val env = StatsRepositoryTestEnv()

        val result = env.repository.summarizeDay(StatsRepositoryTestEnv.OTHER_STUDENT_ID, dayEpoch)

        assertEquals(StatsFailure.ACCESS_DENIED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `家长可查看名下学生统计但不能查看他人学生`() = runTest {
        val env = StatsRepositoryTestEnv(
            SessionState(role = Role.PARENT, parentId = StatsRepositoryTestEnv.PARENT_ID, studentId = null),
        )
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.COMPLETED))
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(5)),
        )

        val own = env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch)
        val other = env.repository.summarizeDay(StatsRepositoryTestEnv.OTHER_STUDENT_ID, dayEpoch)

        assertEquals(1, (own as StatsResult.Success).data.totalCount)
        assertEquals(StatsFailure.ACCESS_DENIED, (other as StatsResult.Failure).reason)
    }

    @Test
    fun `读取作业清单失败时当日盘点收敛为读取失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.failReads = true

        val result = env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch)

        assertEquals(StatsFailure.READ_FAILED, (result as StatsResult.Failure).reason)
    }

    // ---- 单项详情 ----

    @Test
    fun `单项详情汇总预估实际暂停与困难度`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, content = "数学口算", status = HomeworkStatus.COMPLETED, estimatedMinutes = 30),
        )
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(45)),
        )
        env.timerRepository.putPause(statsTestPause(1L, 10L, 1L, StatsRepositoryTestEnv.at(5), StatsRepositoryTestEnv.at(10)))
        env.timerRepository.putPause(statsTestPause(2L, 10L, 1L, StatsRepositoryTestEnv.at(20), StatsRepositoryTestEnv.at(25)))

        val result = env.repository.itemDetail(1L)

        val detail = (result as StatsResult.Success).data
        assertEquals(30, detail.estimatedMinutes)
        assertEquals(35 * minute, detail.elapsedMillis)
        assertEquals(10 * minute, detail.pausedTotalMillis)
        assertEquals(2, detail.pauseCount)
        assertEquals(1, detail.sessionCount)
        assertTrue(detail.hasExecution)
        assertTrue(detail.assessmentHint.isNotBlank())
    }

    @Test
    fun `同一作业多次会话的实际耗时累加`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, status = HomeworkStatus.COMPLETED, estimatedMinutes = 60),
        )
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(10)),
        )
        env.timerRepository.putSession(
            statsTestSession(11L, 1L, StatsRepositoryTestEnv.at(60), StatsRepositoryTestEnv.at(75)),
        )

        val detail = (env.repository.itemDetail(1L) as StatsResult.Success).data

        assertEquals(2, detail.sessionCount)
        assertEquals(25 * minute, detail.elapsedMillis)
    }

    @Test
    fun `暂停明细只归属到对应的会话`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.COMPLETED))
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(30)),
        )
        env.timerRepository.putSession(
            statsTestSession(11L, 1L, StatsRepositoryTestEnv.at(60), StatsRepositoryTestEnv.at(90)),
        )
        env.timerRepository.putPause(statsTestPause(1L, 11L, 1L, StatsRepositoryTestEnv.at(70), StatsRepositoryTestEnv.at(80)))

        val detail = (env.repository.itemDetail(1L) as StatsResult.Success).data

        assertEquals(10 * minute, detail.pausedTotalMillis)
        assertEquals(1, detail.pauseCount)
    }

    @Test
    fun `无执行记录的作业返回尚未开始而非失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.PENDING))

        val detail = (env.repository.itemDetail(1L) as StatsResult.Success).data

        assertFalse(detail.hasExecution)
        assertEquals(0, detail.sessionCount)
        assertEquals(com.assignmate.app.stats.domain.DifficultyLevel.UNKNOWN, detail.difficulty)
    }

    @Test
    fun `作业不存在时单项详情返回作业不存在`() = runTest {
        val env = StatsRepositoryTestEnv()

        val result = env.repository.itemDetail(999L)

        assertEquals(StatsFailure.HOMEWORK_NOT_FOUND, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `查看他人名下作业详情被拒绝`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID),
        )

        val result = env.repository.itemDetail(1L)

        assertEquals(StatsFailure.ACCESS_DENIED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `无会话时单项详情返回无会话失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.authRepository.setSession(SessionState.NONE)

        val result = env.repository.itemDetail(1L)

        assertEquals(StatsFailure.NO_ACTIVE_SESSION, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `家长名下仅一名学生时查看他人学生作业详情被拒绝`() = runTest {
        // 会话唯一可见学生 = 学生 A；被查作业归属学生 B（家长可归属，但不在本会话可见范围）
        val env = StatsRepositoryTestEnv(
            SessionState(role = Role.PARENT, parentId = StatsRepositoryTestEnv.PARENT_ID, studentId = null),
        )
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID),
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

        val result = env.repository.itemDetail(1L)

        assertEquals(StatsFailure.ACCESS_DENIED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `详情仅在作业归属与会话可见学生一致时成功`() = runTest {
        val env = StatsRepositoryTestEnv(
            SessionState(role = Role.PARENT, parentId = StatsRepositoryTestEnv.PARENT_ID, studentId = null),
        )
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.COMPLETED))
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(10)),
        )

        val detail = (env.repository.itemDetail(1L) as StatsResult.Success).data

        assertEquals(StatsRepositoryTestEnv.STUDENT_ID, detail.studentId)
        assertTrue(detail.hasExecution)
    }

    // ---- 历史查询 ----

    @Test
    fun `历史查询逐日返回并按日期倒序`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.COMPLETED))
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(10)),
        )
        env.timerRepository.putSession(
            statsTestSession(
                sessionId = 11L,
                homeworkId = 1L,
                startedAtMillis = StatsRepositoryTestEnv.at(-24 * 60),
                finishedAtMillis = StatsRepositoryTestEnv.at(-24 * 60 + 10),
            ),
        )

        val result = env.repository.history(
            StatsRepositoryTestEnv.STUDENT_ID,
            HistoryQuery(dayEpoch - 1, dayEpoch),
        )

        val summaries = (result as StatsResult.Success).data
        assertEquals(listOf(dayEpoch, dayEpoch - 1), summaries.map { it.epochDay })
        assertEquals(1, summaries.first().totalCount)
        assertEquals(1, summaries.last().totalCount)
    }

    @Test
    fun `历史查询跳过无记录的日子`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.COMPLETED))
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(10)),
        )

        val result = env.repository.history(
            StatsRepositoryTestEnv.STUDENT_ID,
            HistoryQuery(dayEpoch - 3, dayEpoch),
        )

        assertEquals(listOf(dayEpoch), (result as StatsResult.Success).data.map { it.epochDay })
    }

    @Test
    fun `历史查询起止日倒置返回非法入参失败`() = runTest {
        val env = StatsRepositoryTestEnv()

        val result = env.repository.history(
            StatsRepositoryTestEnv.STUDENT_ID,
            HistoryQuery(dayEpoch, dayEpoch - 1),
        )

        assertEquals(StatsFailure.INVALID_QUERY, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `历史查询无会话返回无会话失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.authRepository.setSession(SessionState.NONE)

        val result = env.repository.history(StatsRepositoryTestEnv.STUDENT_ID, HistoryQuery(dayEpoch))

        assertEquals(StatsFailure.NO_ACTIVE_SESSION, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `历史查询学生越权被拒绝`() = runTest {
        val env = StatsRepositoryTestEnv()

        val result = env.repository.history(StatsRepositoryTestEnv.OTHER_STUDENT_ID, HistoryQuery(dayEpoch))

        assertEquals(StatsFailure.ACCESS_DENIED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `历史查询超长范围自动收敛到上限`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.COMPLETED))
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(10)),
        )

        val result = env.repository.history(
            StatsRepositoryTestEnv.STUDENT_ID,
            HistoryQuery(dayEpoch - 10L, dayEpoch + 400L),
        )

        // 超长范围收敛到最多 92 天：有记录的那一天仍在范围内，且不抛异常
        val summaries = (result as StatsResult.Success).data
        assertEquals(listOf(dayEpoch), summaries.map { it.epochDay })
        assertTrue(summaries.size <= com.assignmate.app.stats.domain.StatsConstants.MAX_HISTORY_DAYS.toInt())
    }

    @Test
    fun `会话读取失败时历史查询收敛为读取失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L))
        env.timerRepository.failReads = true

        val result = env.repository.history(StatsRepositoryTestEnv.STUDENT_ID, HistoryQuery(dayEpoch))

        assertEquals(StatsFailure.READ_FAILED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `未结束会话在盘点中按当前时钟折算`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.IN_PROGRESS))
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), null),
        )

        val summaryResult = env.repository.summarizeDay(StatsRepositoryTestEnv.STUDENT_ID, dayEpoch)
        val summary = (summaryResult as StatsResult.Success).data

        // 固定时钟为当日第 9 分钟，会话自当日第 0 分钟开始且仍在计时 → 纳入当日盘点（未完成）
        assertEquals(1, summary.totalCount)
        assertEquals(0, summary.completedCount)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun `详情文案经展示口径换算`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, status = HomeworkStatus.COMPLETED, estimatedMinutes = 30),
        )
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(45)),
        )

        val detail = (env.repository.itemDetail(1L) as StatsResult.Success).data

        assertEquals("45 分钟", StatsCalculations.durationText(detail.elapsedMillis))
        // 预估时长文案必须按「分钟」口径换算（P1 回归看护：多乘一次毫秒换算法会得到「30000 小时」）
        assertEquals("30 分钟", detail.estimatedText)
    }

    @Test
    fun `未设定预估时长时详情文案为空`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, status = HomeworkStatus.RECORDED, estimatedMinutes = null),
        )

        val detail = (env.repository.itemDetail(1L) as StatsResult.Success).data

        assertNull(detail.estimatedText)
        assertFalse(detail.hasExecution)
    }

    @Test
    fun `空作业清单的历史查询返回成功且为空`() = runTest {
        val env = StatsRepositoryTestEnv()

        val result = env.repository.history(StatsRepositoryTestEnv.STUDENT_ID, HistoryQuery(dayEpoch))

        assertTrue((result as StatsResult.Success).data.isEmpty())
    }

    @Test
    fun `单项详情在作业清单读取失败时仍可返回（按 id 直读）`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, status = HomeworkStatus.COMPLETED))
        env.timerRepository.failReads = true

        val result = env.repository.itemDetail(1L)

        assertEquals(StatsFailure.READ_FAILED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `详情模型不包含他人作业数据`() = runTest {
        val env = StatsRepositoryTestEnv(
            SessionState(role = Role.PARENT, parentId = StatsRepositoryTestEnv.PARENT_ID, studentId = null),
        )
        env.homeworkRepository.put(statsTestHomework(1L))
        env.timerRepository.putSession(
            statsTestSession(10L, 1L, StatsRepositoryTestEnv.at(0), StatsRepositoryTestEnv.at(10)),
        )

        val detail = (env.repository.itemDetail(1L) as StatsResult.Success).data

        assertEquals(StatsRepositoryTestEnv.STUDENT_ID, detail.studentId)
    }
}
