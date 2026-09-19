package com.assignmate.app.stats.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.stats.domain.DifficultyLevel
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.StatsCalculations
import com.assignmate.app.stats.domain.StatsConstants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatsRepositoryImpl] 单测：基于内存替身（作业清单 + **作业每天详情**）验证
 * 「按学生聚合、当天应做分母口径、单项按天取值、历史范围查询、权限与失败收敛」。
 *
 * 固定业务时区为 Asia/Shanghai，基准自然日为 2023-11-14（[StatsRepositoryTestEnv.DAY_EPOCH]）；
 * 统计口径完全由每天详情的 epochDay 与当天字段决定，不再依赖时钟或计时会话裁剪
 * （这正是「一条作业项 + 每天详情」改造后的取数方式）。
 */
class StatsRepositoryImplTest {

    private val minute = StatsRepositoryTestEnv.MINUTE
    private val dayEpoch = StatsRepositoryTestEnv.DAY_EPOCH
    private val studentId = StatsRepositoryTestEnv.STUDENT_ID

    // ---- 当日盘点 ----

    @Test
    fun `当日盘点按学生聚合完成率与暂停`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, content = "语文生字", status = HomeworkStatus.COMPLETED, priority = 100),
        )
        env.homeworkRepository.put(statsTestHomework(2L, content = "数学口算", priority = 101))
        env.dailyRecordRepository.put(
            statsTestDayRecord(
                homeworkId = 1L,
                status = HomeworkDayStatus.COMPLETED,
                pauseCount = 1,
                pausedTotalMinutes = 2,
            ),
        )
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 2L, status = HomeworkDayStatus.IN_PROGRESS, startedAtMillis = 1L),
        )

        val summary = (env.repository.summarizeDay(studentId, dayEpoch) as StatsResult.Success).data

        assertEquals(2, summary.totalCount)
        assertEquals(1, summary.completedCount)
        assertEquals(0.5, summary.completionRate, 1e-9)
        assertEquals(1, summary.pauseCount)
        assertEquals(2 * minute, summary.pausedTotalMillis)
        assertEquals("语文生字", summary.mostPausedItem?.content)
        assertEquals(dayEpoch, summary.epochDay)
    }

    @Test
    fun `当天没有应做作业时返回空盘点而非失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        // 阶段作业覆盖从明天开始：今天不在其阶段范围内，故今天没有应做作业（分母为 0，不除零）
        env.homeworkRepository.put(statsTestStageHomework(1L, startEpochDay = dayEpoch + 1))

        val result = env.repository.summarizeDay(studentId, dayEpoch)

        val summary = (result as StatsResult.Success).data
        assertTrue(summary.isEmpty)
        assertEquals(0.0, summary.completionRate, 0.0)
        assertEquals(dayEpoch, summary.epochDay)
    }

    @Test
    fun `待开始作业无执行痕迹时仍进入分母`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.put(statsTestDayRecord(homeworkId = 1L))

        val summary = (env.repository.summarizeDay(studentId, dayEpoch) as StatsResult.Success).data

        assertEquals(1, summary.totalCount)
        assertEquals(0, summary.completedCount)
        assertEquals(HomeworkDayStatus.NOT_STARTED, summary.items.single().status)
    }

    @Test
    fun `阶段作业落在今天时进入分母并携带打卡进度`() = runTest {
        val env = StatsRepositoryTestEnv()
        // 覆盖 day-1 .. day+5：今天在阶段范围内，且进度分母 M = 覆盖天数（7）
        env.homeworkRepository.put(statsTestStageHomework(1L, content = "背单词", startEpochDay = dayEpoch - 1))
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 1L, epochDay = dayEpoch - 1, status = HomeworkDayStatus.COMPLETED),
        )
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 1L, epochDay = dayEpoch, status = HomeworkDayStatus.COMPLETED),
        )

        val summary = (env.repository.summarizeDay(studentId, dayEpoch) as StatsResult.Success).data

        assertEquals(1, summary.totalCount)
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.stages.size)
        assertEquals(2, summary.stages.single().doneDays)
        assertEquals(7, summary.stages.single().totalDays)
    }

    @Test
    fun `当日盘点回填请求的纪元日供页面展示所选日期`() = runTest {
        val env = StatsRepositoryTestEnv()
        val requestedDay = dayEpoch - 1

        val result = env.repository.summarizeDay(studentId, requestedDay)

        assertEquals(requestedDay, (result as StatsResult.Success).data.epochDay)
    }

    @Test
    fun `其他学生的作业与详情不进入当日盘点`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID),
        )
        env.dailyRecordRepository.put(
            statsTestDayRecord(
                homeworkId = 1L,
                studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID,
                status = HomeworkDayStatus.COMPLETED,
            ),
        )

        val result = env.repository.summarizeDay(studentId, dayEpoch)

        assertTrue((result as StatsResult.Success).data.isEmpty)
    }

    @Test
    fun `无会话时当日盘点返回无会话失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.authRepository.setSession(SessionState.NONE)

        val result = env.repository.summarizeDay(studentId, dayEpoch)

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
        val env = StatsRepositoryTestEnv(parentSession())
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 1L, status = HomeworkDayStatus.COMPLETED),
        )

        val own = env.repository.summarizeDay(studentId, dayEpoch)
        val other = env.repository.summarizeDay(StatsRepositoryTestEnv.OTHER_STUDENT_ID, dayEpoch)

        assertEquals(1, (own as StatsResult.Success).data.totalCount)
        assertEquals(StatsFailure.ACCESS_DENIED, (other as StatsResult.Failure).reason)
    }

    @Test
    fun `读取作业清单失败时当日盘点收敛为读取失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.failReads = true

        val result = env.repository.summarizeDay(studentId, dayEpoch)

        assertEquals(StatsFailure.READ_FAILED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `读取每天详情失败时当日盘点收敛为读取失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.failReads = true

        val result = env.repository.summarizeDay(studentId, dayEpoch)

        assertEquals(StatsFailure.READ_FAILED, (result as StatsResult.Failure).reason)
    }

    // ---- 单项详情（按指定某一天） ----

    @Test
    fun `单项详情按当天详情汇总预估实际暂停与困难度`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, content = "数学口算", status = HomeworkStatus.COMPLETED, estimatedMinutes = 30),
        )
        env.dailyRecordRepository.put(
            statsTestDayRecord(
                homeworkId = 1L,
                status = HomeworkDayStatus.COMPLETED,
                startedAtMillis = StatsRepositoryTestEnv.at(0),
                estimatedMinutes = 30,
                actualMinutes = 45,
                pauseCount = 2,
                pausedTotalMinutes = 10,
                finishedAtMillis = StatsRepositoryTestEnv.at(55),
            ),
        )

        val detail = (env.repository.itemDetail(1L, dayEpoch) as StatsResult.Success).data

        assertEquals(30, detail.estimatedMinutes)
        assertEquals(45 * minute, detail.elapsedMillis)
        assertEquals(10 * minute, detail.pausedTotalMillis)
        assertEquals(2, detail.pauseCount)
        assertEquals(HomeworkDayStatus.COMPLETED, detail.dayStatus)
        assertTrue(detail.hasExecution)
        assertTrue(detail.assessmentHint.isNotBlank())
        assertEquals(dayEpoch, detail.epochDay)
    }

    @Test
    fun `阶段作业详情按指定某一天取值而不累加整段`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestStageHomework(1L, content = "背单词", startEpochDay = dayEpoch - 1))
        env.dailyRecordRepository.put(
            statsTestDayRecord(
                homeworkId = 1L,
                epochDay = dayEpoch - 1,
                status = HomeworkDayStatus.COMPLETED,
                actualMinutes = 20,
                pauseCount = 1,
                pausedTotalMinutes = 3,
            ),
        )
        env.dailyRecordRepository.put(
            statsTestDayRecord(
                homeworkId = 1L,
                epochDay = dayEpoch,
                status = HomeworkDayStatus.IN_PROGRESS,
                startedAtMillis = StatsRepositoryTestEnv.at(0),
                actualMinutes = 8,
                pauseCount = 2,
                pausedTotalMinutes = 4,
            ),
        )

        val today = (env.repository.itemDetail(1L, dayEpoch) as StatsResult.Success).data
        val yesterday = (env.repository.itemDetail(1L, dayEpoch - 1) as StatsResult.Success).data

        assertEquals(8 * minute, today.elapsedMillis)
        assertEquals(4 * minute, today.pausedTotalMillis)
        assertEquals(2, today.pauseCount)
        assertEquals(HomeworkDayStatus.IN_PROGRESS, today.dayStatus)

        assertEquals(20 * minute, yesterday.elapsedMillis)
        assertEquals(3 * minute, yesterday.pausedTotalMillis)
        assertEquals(HomeworkDayStatus.COMPLETED, yesterday.dayStatus)

        // 同一条阶段作业的整段打卡进度两天一致（进度是整段概念，时长是当天概念）
        // 分母 M = 阶段覆盖天数（7），分子 = 覆盖区间内已达成的天数（day-1 已完成）
        assertEquals(1, today.stageProgress?.doneDays)
        assertEquals(7, today.stageProgress?.totalDays)
        assertEquals(today.stageProgress, yesterday.stageProgress)
    }

    @Test
    fun `当天没有详情时返回尚未开始而非失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 1L, epochDay = dayEpoch - 1, status = HomeworkDayStatus.COMPLETED),
        )

        val detail = (env.repository.itemDetail(1L, dayEpoch) as StatsResult.Success).data

        assertFalse(detail.hasExecution)
        assertEquals(HomeworkDayStatus.NOT_STARTED, detail.dayStatus)
        assertEquals(0L, detail.elapsedMillis)
        assertEquals(DifficultyLevel.UNKNOWN, detail.difficulty)
    }

    @Test
    fun `作业不存在时单项详情返回作业不存在`() = runTest {
        val env = StatsRepositoryTestEnv()

        val result = env.repository.itemDetail(999L, dayEpoch)

        assertEquals(StatsFailure.HOMEWORK_NOT_FOUND, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `查看他人名下作业详情被拒绝`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID),
        )

        val result = env.repository.itemDetail(1L, dayEpoch)

        assertEquals(StatsFailure.ACCESS_DENIED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `无会话时单项详情返回无会话失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.authRepository.setSession(SessionState.NONE)

        val result = env.repository.itemDetail(1L, dayEpoch)

        assertEquals(StatsFailure.NO_ACTIVE_SESSION, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `家长名下仅一名学生时查看他人学生作业详情被拒绝`() = runTest {
        // 会话唯一可见学生 = 学生 A；被查作业归属学生 B（家长可归属，但不在本会话可见范围）
        val env = StatsRepositoryTestEnv(parentSession())
        env.homeworkRepository.put(
            statsTestHomework(1L, studentId = StatsRepositoryTestEnv.OTHER_STUDENT_ID),
        )

        val result = env.repository.itemDetail(1L, dayEpoch)

        assertEquals(StatsFailure.ACCESS_DENIED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `详情仅在作业归属与会话可见学生一致时成功`() = runTest {
        val env = StatsRepositoryTestEnv(parentSession())
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 1L, status = HomeworkDayStatus.COMPLETED, actualMinutes = 10),
        )

        val detail = (env.repository.itemDetail(1L, dayEpoch) as StatsResult.Success).data

        assertEquals(studentId, detail.studentId)
        assertTrue(detail.hasExecution)
    }

    @Test
    fun `详情模型不包含他人作业数据`() = runTest {
        val env = StatsRepositoryTestEnv(parentSession())
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.put(statsTestDayRecord(homeworkId = 1L, actualMinutes = 10))

        val detail = (env.repository.itemDetail(1L, dayEpoch) as StatsResult.Success).data

        assertEquals(studentId, detail.studentId)
        assertEquals(1L, detail.homeworkId)
    }

    @Test
    fun `详情文案经展示口径换算`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, estimatedMinutes = 30))
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 1L, actualMinutes = 45, estimatedMinutes = 30),
        )

        val detail = (env.repository.itemDetail(1L, dayEpoch) as StatsResult.Success).data

        assertEquals("45 分钟", StatsCalculations.durationText(detail.elapsedMillis))
        // 预估时长文案必须按「分钟」口径换算（P1 回归看护：多乘一次毫秒换算法会得到「30000 小时」）
        assertEquals("30 分钟", detail.estimatedText)
    }

    @Test
    fun `未设定预估时长时详情文案为空`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, estimatedMinutes = null))

        val detail = (env.repository.itemDetail(1L, dayEpoch) as StatsResult.Success).data

        assertNull(detail.estimatedText)
        assertFalse(detail.hasExecution)
    }

    @Test
    fun `当天未单独设定预估时回落到作业本身的预估`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L, estimatedMinutes = 25))
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 1L, actualMinutes = 10, estimatedMinutes = null),
        )

        val detail = (env.repository.itemDetail(1L, dayEpoch) as StatsResult.Success).data

        assertEquals(25, detail.estimatedMinutes)
        assertEquals("25 分钟", detail.estimatedText)
    }

    @Test
    fun `每天详情读取失败时详情收敛为读取失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.failReads = true

        val result = env.repository.itemDetail(1L, dayEpoch)

        assertEquals(StatsFailure.READ_FAILED, (result as StatsResult.Failure).reason)
    }

    // ---- 历史查询 ----

    @Test
    fun `历史查询逐日返回并按日期倒序`() = runTest {
        val env = StatsRepositoryTestEnv()
        // 阶段作业覆盖 day-1 与 day：两天都有应做项（即使某天没有任何执行记录）
        env.homeworkRepository.put(statsTestStageHomework(1L, startEpochDay = dayEpoch - 1))
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 1L, epochDay = dayEpoch, status = HomeworkDayStatus.COMPLETED),
        )

        val result = env.repository.history(studentId, HistoryQuery(dayEpoch - 1, dayEpoch))

        val summaries = (result as StatsResult.Success).data
        assertEquals(listOf(dayEpoch, dayEpoch - 1), summaries.map { it.epochDay })
        assertEquals(1, summaries.first().completedCount)
        assertEquals(0, summaries.last().completedCount)
    }

    @Test
    fun `历史查询跳过没有应做作业的日子`() = runTest {
        val env = StatsRepositoryTestEnv()
        // 当天作业的归属日只有 day：day-3..day-1 都没有应做作业
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.put(statsTestDayRecord(homeworkId = 1L, status = HomeworkDayStatus.COMPLETED))

        val result = env.repository.history(studentId, HistoryQuery(dayEpoch - 3, dayEpoch))

        assertEquals(listOf(dayEpoch), (result as StatsResult.Success).data.map { it.epochDay })
    }

    @Test
    fun `阶段作业在历史里体现当日完成与整段打卡进度`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestStageHomework(1L, content = "背单词", startEpochDay = dayEpoch - 1))
        env.dailyRecordRepository.put(
            statsTestDayRecord(homeworkId = 1L, epochDay = dayEpoch - 1, status = HomeworkDayStatus.COMPLETED),
        )

        val result = env.repository.history(studentId, HistoryQuery(dayEpoch - 1, dayEpoch))
        val summaries = (result as StatsResult.Success).data

        val today = summaries.first()
        assertEquals(dayEpoch, today.epochDay)
        assertEquals(0, today.completedCount)
        assertEquals(1, today.stages.single().doneDays)
        assertEquals(7, today.stages.single().totalDays)
        // 今天尚未完成（阶段覆盖内、今天未到点）：按「未开始」呈现
        assertEquals(HomeworkDayStatus.NOT_STARTED, today.items.single().status)

        val yesterday = summaries.last()
        assertEquals(1, yesterday.completedCount)
        assertEquals(HomeworkDayStatus.COMPLETED, yesterday.items.single().status)
    }

    @Test
    fun `历史查询起止日倒置返回非法入参失败`() = runTest {
        val env = StatsRepositoryTestEnv()

        val result = env.repository.history(studentId, HistoryQuery(dayEpoch, dayEpoch - 1))

        assertEquals(StatsFailure.INVALID_QUERY, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `历史查询无会话返回无会话失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.authRepository.setSession(SessionState.NONE)

        val result = env.repository.history(studentId, HistoryQuery(dayEpoch))

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
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.put(statsTestDayRecord(homeworkId = 1L, status = HomeworkDayStatus.COMPLETED))

        val result = env.repository.history(studentId, HistoryQuery(dayEpoch - 10L, dayEpoch + 400L))

        // 超长范围收敛到最多 92 天：有记录的那一天仍在范围内，且不抛异常
        val summaries = (result as StatsResult.Success).data
        assertEquals(listOf(dayEpoch), summaries.map { it.epochDay })
        assertTrue(summaries.size <= StatsConstants.MAX_HISTORY_DAYS.toInt())
    }

    @Test
    fun `每天详情读取失败时历史查询收敛为读取失败`() = runTest {
        val env = StatsRepositoryTestEnv()
        env.homeworkRepository.put(statsTestHomework(1L))
        env.dailyRecordRepository.failReads = true

        val result = env.repository.history(studentId, HistoryQuery(dayEpoch))

        assertEquals(StatsFailure.READ_FAILED, (result as StatsResult.Failure).reason)
    }

    @Test
    fun `空作业清单的历史查询返回成功且为空`() = runTest {
        val env = StatsRepositoryTestEnv()

        val result = env.repository.history(studentId, HistoryQuery(dayEpoch))

        assertTrue((result as StatsResult.Success).data.isEmpty())
    }

    /** 家长会话（不携带学生维度，与生产家长登录后的会话形态一致） */
    private fun parentSession(): SessionState = SessionState(
        role = Role.PARENT,
        parentId = StatsRepositoryTestEnv.PARENT_ID,
        studentId = null,
    )
}
