package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageDayRecords
import com.assignmate.app.homework.domain.HomeworkDayState
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.domain.TimerCalculations
import com.assignmate.app.timer.domain.TimerSession
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「到点不锁定、跨日才判未完成」单测（对照风后开发计划第二条与第四条）。
 *
 * 口径（需求原文）：到点仅作提醒/逾期标识，**当天内仍可完成**；只有跨入次日才判「未完成」。
 * 缺卡的唯一投影口径在 homework 的 [StageDayRecords]（本模块不重复实现），
 * 因此用例同时断言两件事：
 * 1. 计时侧在「已过每日截止时刻」的当天仍可正常开始与完成，并把当天详情写成「已完成」；
 * 2. 共享投影（[StageDayRecords]）对同一天给出「仍可完成」，只有**已过去**且未完成的天才是缺卡。
 */
class TimerDayLockTest {

    @Test
    fun `到点后当天仍可开始并完成计时`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        val homework = env.stageHomework(startEpochDay = today)
        // 每日 21:00 截止，孩子在 22:00（已过点）才开始做——到点只提醒不锁定
        env.clock.set(millisAt(today, LocalTime.of(22, 0)))

        val session = env.start(homework.id)
        env.advance(25 * MINUTE)
        val completed = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue("到点后当天仍可开始", session.epochDay == today)
        assertTrue("到点后当天仍可完成", completed is TimerCompleteResult.Success)
        val record = env.dailyRecordDao.snapshot().single()
        assertEquals(HomeworkDayStatus.COMPLETED.name, record.status)
        assertEquals(today, record.epochDay)
        assertEquals(25, record.actualMinutes)
        assertTrue("完成时刻落在当天（已过每日截止时刻也照样计入当天）", requireNotNull(record.finishedAt).toEpochMilli() > millisAt(today, LocalTime.of(21, 0)))
        // 共享投影：当天是「已完成」（不是缺卡）
        val outcome = StageDayRecords.todayOutcome(
            homework,
            env.dailyRecordsOf(homework.id),
            today,
            TimerTestEnv.ZONE,
        )
        assertEquals(HomeworkDayState.COMPLETED, outcome?.state)
    }

    @Test
    fun `到点后当天未完成仍可完成且不影响后续天的提醒`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(22, 0)))
        val homework = env.stageHomework(startEpochDay = today)

        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        // 当天已过点不补提醒（避免补发轰炸），但其余天照设——到点并不取消/锁定后续执行
        assertEquals(
            (1L until 7L).map { today + it },
            env.alarmScheduler.scheduledDaysOf(homework.id),
        )
        // 共享投影：当天在阶段范围内、尚未完成 → 仍可完成（不是缺卡）
        val records = env.dailyRecordsOf(homework.id)
        assertEquals(
            HomeworkDayState.PENDING,
            StageDayRecords.todayOutcome(homework, records, today, TimerTestEnv.ZONE)?.state,
        )
        assertTrue(StageDayRecords.isTodayActionable(homework, records, today, TimerTestEnv.ZONE))
        // 计时侧同样不锁定：当天仍能开始
        assertTrue(env.repository.startSession(homework.id, Role.STUDENT) is TimerStartResult.Success)
    }

    @Test
    fun `跨日才判未完成当天始终可完成`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        val yesterday = today - 1
        val homework = env.stageHomework(startEpochDay = yesterday, stageRange = StageRange.ONE_WEEK)
        val start = requireNotNull(homework.stageStartEpochDay)
        val covered = homework.stageCoveredDays

        // 昨天（阶段内、已过去、无完成记录）→ 跨日才判未完成
        assertEquals(
            HomeworkDayState.MISSED,
            StageDayRecords.stateOf(
                epochDay = yesterday,
                startEpochDay = start,
                coveredDays = covered,
                todayEpochDay = today,
                isCompleted = false,
            ),
        )
        // 今天（已过每日截止时刻）→ 仍可完成
        assertEquals(
            HomeworkDayState.PENDING,
            StageDayRecords.stateOf(
                epochDay = today,
                startEpochDay = start,
                coveredDays = covered,
                todayEpochDay = today,
                isCompleted = false,
            ),
        )
    }

    @Test
    fun `已完成的历史天不会被判为未完成`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        val yesterday = today - 1
        val homework = env.stageHomework(startEpochDay = yesterday)
        env.dailyRecordRepository.upsertStatus(
            homeworkId = homework.id,
            studentId = TimerTestEnv.STUDENT_ID,
            epochDay = yesterday,
            status = HomeworkDayStatus.COMPLETED,
            nowMillis = env.clock.currentTimeMillis(),
        )

        val records = env.dailyRecordsOf(homework.id)

        assertEquals(
            HomeworkDayState.COMPLETED,
            StageDayRecords.outcomeFor(
                homework,
                records,
                epochDay = yesterday,
                todayEpochDay = today,
                zoneId = TimerTestEnv.ZONE,
            )?.state,
        )
        assertEquals(
            "已完成天数计入阶段进度",
            1,
            StageDayRecords.progressOf(homework, records, today, TimerTestEnv.ZONE)?.completedDays,
        )
        assertEquals(0, StageDayRecords.progressOf(homework, records, today, TimerTestEnv.ZONE)?.missedDays)
    }

    @Test
    fun `计时链路只写进行中或已完成绝不写缺卡`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        val homework = env.stageHomework(startEpochDay = today)
        val first = env.start(homework.id)
        env.advance(3 * MINUTE)
        env.pause(first.id)
        env.advance(1 * MINUTE)
        env.resume(first.id)
        env.advance(2 * MINUTE)
        env.repository.completeSession(first.id, Role.STUDENT)

        // 次日再做一轮（撤销完成后重新开始）
        env.advance(DAY)
        env.homeworkRepository.reopen(homework.id, Role.STUDENT)
        val second = env.start(homework.id)

        assertEquals(
            "两天详情均为进行中/已完成，缺卡只由投影判定、不由计时写入",
            listOf(HomeworkDayStatus.COMPLETED, HomeworkDayStatus.IN_PROGRESS),
            env.dailyRecordDao.snapshot().map { HomeworkDayStatus.fromRawValue(it.status) },
        )
        assertEquals(today + 1, second.epochDay)
    }

    @Test
    fun `到点后逾期标识成立但计时仍可开始并完成不锁定`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        val homework = env.stageHomework(startEpochDay = today)
        // 每日 21:00 截止，22:00 才打开计时页
        env.clock.set(millisAt(today, LocalTime.of(22, 0)))
        val now = env.clock.currentTimeMillis()

        assertTrue(
            "到点后给出逾期标识（与修复前的差异：不再按 deadline 裸值比较，见 TimerStageOverdueTest）",
            TimerCalculations.isHomeworkOverdue(homework, session = null, nowMillis = now, zoneId = TimerTestEnv.ZONE),
        )
        // 逾期标识不产生任何锁定：当天仍可开始并完成
        val session = env.start(homework.id)
        env.advance(25 * MINUTE)
        val completed = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue("到点后当天仍可完成", completed is TimerCompleteResult.Success)
        assertEquals(HomeworkDayStatus.COMPLETED.name, env.dailyRecordDao.snapshot().single().status)
    }

    @Test
    fun `家长编辑阶段作业后当天仍不误判超时`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        // 起始日为两天前的阶段作业（编辑前：deadline 已是 timeOfDay 载体）
        val homework = env.stageHomework(startEpochDay = today - 2)
        env.useParentSession()
        val updated = env.homeworkRepository.updateTemplate(
            homeworkId = homework.id,
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = HomeworkDailyDeadlineCodec.encodeStageDaily(today - 2, LocalTime.of(20, 30)),
            sessionRole = Role.PARENT,
        )
        assertTrue("用例前提：家长编辑阶段作业应成功", updated is HomeworkOperationResult.Success)
        val edited = requireNotNull(env.homeworkRepository.getHomework(homework.id))

        env.clock.set(millisAt(today, LocalTime.of(19, 0)))
        assertFalse(
            "编辑后（deadline 仍为每日时刻载体）当天到点前不得判超时",
            TimerCalculations.isHomeworkOverdue(
                edited,
                session = null,
                nowMillis = env.clock.currentTimeMillis(),
                zoneId = TimerTestEnv.ZONE,
            ),
        )
        env.clock.set(millisAt(today, LocalTime.of(20, 30)) + 1)
        assertTrue(
            "当天到点后仅作逾期标识",
            TimerCalculations.isHomeworkOverdue(
                edited,
                session = null,
                nowMillis = env.clock.currentTimeMillis(),
                zoneId = TimerTestEnv.ZONE,
            ),
        )
    }

    // ---- 测试辅助 ----

    private suspend fun TimerTestEnv.start(homeworkId: Long): TimerSession =
        (repository.startSession(homeworkId, Role.STUDENT) as TimerStartResult.Success).session

    private suspend fun TimerTestEnv.pause(sessionId: Long): TimerSession =
        (repository.pauseSession(sessionId) as TimerPauseResult.Success).session

    private suspend fun TimerTestEnv.resume(sessionId: Long): TimerSession =
        (repository.resumeSession(sessionId) as TimerResumeResult.Success).session

    private fun millisAt(epochDay: Long, time: LocalTime): Long =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, time, TimerTestEnv.ZONE).toEpochMilli()

    private companion object {

        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
