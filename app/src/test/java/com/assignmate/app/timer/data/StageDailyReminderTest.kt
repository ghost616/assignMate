package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段作业「每日到点提醒」的调度与取消单测（真实协调器 + 真实 homework 仓库 + 真实每天详情契约 + 替身闹钟）。
 *
 * 覆盖（对照风后开发计划第二条）：
 * 1. 阶段作业按「每日截止时刻」在**阶段范围内的每一天**各设一个闹钟（逐日触发时刻一致、内容为作业内容）；
 * 2. 当天详情已完成（打卡完成）→ **当天不再提醒**，其余天照设，且先前设过的当天闹钟被取消；
 * 3. 阶段范围变更 → 超出新范围的历史闹钟被精确取消（不遗留无效闹钟）；
 * 4. 作业删除 → 逐日闹钟与单次闹钟全部取消，登记表清空；
 * 5. 当天已过每日截止时刻（超出宽限）→ 不补当天提醒，未到天数照设；阶段整体结束后不再提醒；
 * 6. 当天作业（TODAY）语义不变（仍为「绝对日期 + 时刻」的**单次**提醒）。
 */
class StageDailyReminderTest {

    @Test
    fun `阶段作业按每日截止时刻在覆盖区间内逐日设置提醒`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.ONE_WEEK)

        val result = env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.ScheduledExact, result)
        assertEquals("一周阶段 = 逐日 7 个闹钟", 7, env.alarmScheduler.scheduledDaily.size)
        assertEquals(
            "覆盖区间为今天起连续 7 天",
            (0L until 7L).map { today + it },
            env.alarmScheduler.scheduledDaysOf(homework.id),
        )
        env.alarmScheduler.scheduledDaily.forEach { alarm ->
            assertEquals(homework.id, alarm.homeworkId)
            assertEquals(homework.content, alarm.content)
            assertEquals(
                "每天 21:00 到点（每日截止时刻）",
                millisAt(alarm.epochDay, LocalTime.of(21, 0)),
                alarm.triggerAtMillis,
            )
        }
        assertTrue("阶段作业不设「绝对时刻」单次提醒", env.alarmScheduler.scheduled.isEmpty())
        assertEquals("已设集合落登记表", (0L until 7L).map { today + it }.toSet(), env.reminderScheduleStore.load(homework.id))
    }

    @Test
    fun `已完成当天不再提醒且取消已设的当天闹钟`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val homework = env.stageHomework(startEpochDay = today)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)
        assertEquals("前置：7 天各设一次", 7, env.alarmScheduler.scheduledDaily.size)
        // 打卡完成当天：写入每天详情「已完成」（与 homework 的完成路径同一数据源）
        env.dailyRecordRepository.upsertStatus(
            homeworkId = homework.id,
            studentId = TimerTestEnv.STUDENT_ID,
            epochDay = today,
            status = HomeworkDayStatus.COMPLETED,
            nowMillis = env.clock.currentTimeMillis(),
        )

        val result = env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.ScheduledExact, result)
        assertEquals("已设的当天闹钟被取消", listOf(today), env.alarmScheduler.cancelledDaysOf(homework.id))
        assertEquals(
            "登记表只剩后续 6 天（当天已完成不再提醒）",
            (1L until 7L).map { today + it }.toSet(),
            env.reminderScheduleStore.load(homework.id),
        )
    }

    @Test
    fun `阶段范围变更后超出新范围的历史闹钟被取消`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.TWO_WEEKS)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)
        assertEquals("前置：两周 = 逐日 14 个闹钟", 14, env.reminderScheduleStore.load(homework.id).size)

        // 家长把阶段范围缩短为一周：新范围仍锚定创建日（homework 的 updateTemplate 编码口径），
        // 因此「第 8~14 天」落在新范围之外，必须被精确取消（不遗留无效闹钟）
        env.useParentSession()
        val updated = env.homeworkRepository.updateTemplate(
            homeworkId = homework.id,
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = HomeworkDailyDeadlineCodec.encodeStageDaily(today, LocalTime.of(21, 0)),
            sessionRole = Role.PARENT,
        )
        assertTrue("用例前提：阶段范围变更应成功", updated is HomeworkOperationResult.Success)

        val result = env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.ScheduledExact, result)
        assertEquals(
            "超出新范围的历史闹钟（原第 8~14 天）被精确取消",
            (7L until 14L).map { today + it }.sorted(),
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertEquals(
            "登记表只剩新范围的一周",
            (0L until 7L).map { today + it }.toSet(),
            env.reminderScheduleStore.load(homework.id),
        )
    }

    @Test
    fun `作业删除后逐日闹钟与单次闹钟全部取消且登记清空`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.ONE_WEEK)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        env.reminderCoordinator.cancelHomeworkReminder(homework.id)

        assertEquals(
            "登记在册的每一天都被取消",
            (0L until 7L).map { today + it }.sorted(),
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertTrue(
            "单次提醒同样取消（幂等）：取消调用全部指向该作业",
            env.alarmScheduler.cancelled.isNotEmpty() && env.alarmScheduler.cancelled.all { it == homework.id },
        )
        assertEquals("最后一次取消即删除作业时的收口调用", homework.id, env.alarmScheduler.cancelled.last())
        assertTrue("登记表清空，不残留", env.reminderScheduleStore.load(homework.id).isEmpty())
    }

    @Test
    fun `当天已过每日截止时刻不补当天提醒但未到天数照设`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        // 每日 21:00 截止、宽限 5 分钟：21:06 打开应用
        env.clock.set(millisAt(today, LocalTime.of(21, 6)))
        val homework = env.stageHomework(startEpochDay = today)

        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(
            "当天已过点不补提醒，从明天起连续 6 天",
            (1L until 7L).map { today + it },
            env.alarmScheduler.scheduledDaysOf(homework.id),
        )
    }

    @Test
    fun `当天刚过点仍在宽限窗口内立即提醒`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(21, 0)) + 4 * MINUTE)
        val homework = env.stageHomework(startEpochDay = today)

        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(
            "宽限窗口内（刚过点）仍设置当天，由系统立即触发",
            (0L until 7L).map { today + it },
            env.alarmScheduler.scheduledDaysOf(homework.id),
        )
    }

    @Test
    fun `阶段整体结束后不再设置提醒并取消登记`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.ONE_WEEK)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        env.advance(7 * DAY)
        val result = env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.NotScheduled, result)
        assertEquals(
            "阶段已结束：登记在册的 7 天全部取消",
            (0L until 7L).map { today + it }.sorted(),
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertTrue(env.reminderScheduleStore.load(homework.id).isEmpty())
    }

    @Test
    fun `缺少每日截止时刻的阶段作业不设每日提醒`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = null,
        )

        val result = env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.NotScheduled, result)
        assertTrue(env.alarmScheduler.scheduledDaily.isEmpty())
        assertNull("阶段作业不回落为单次提醒", env.alarmScheduler.lastScheduledFor(homework.id))
        assertTrue("单次提醒被显式取消（幂等）", env.alarmScheduler.cancelled.contains(homework.id))
    }

    @Test
    fun `按学生批量同步时阶段作业逐日设置其余作业照旧取消`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val stage: HomeworkItem = env.stageHomework(startEpochDay = today)
        val completed = env.seedHomework(status = HomeworkStatus.COMPLETED)

        env.reminderCoordinator.syncStudentReminders(TimerTestEnv.STUDENT_ID)

        assertEquals((0L until 7L).map { today + it }, env.alarmScheduler.scheduledDaysOf(stage.id))
        assertTrue("已完成的当天作业取消既有闹钟", env.alarmScheduler.cancelled.contains(completed.id))
    }

    @Test
    fun `当天作业保持绝对日期时刻的单次提醒语义`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        val startTime = millisAt(today, LocalTime.of(23, 0))
        env.clock.set(millisAt(today, LocalTime.of(19, 0)))
        val homework = env.seedHomework(startTime = Instant.ofEpochMilli(startTime))

        val result = env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.ScheduledExact, result)
        assertEquals(1, env.alarmScheduler.scheduled.size)
        assertEquals(
            "TODAY 仍按排定开始时间单次提醒",
            startTime,
            env.alarmScheduler.lastScheduledFor(homework.id)?.triggerAtMillis,
        )
        assertTrue("TODAY 不产生逐日闹钟", env.alarmScheduler.scheduledDaily.isEmpty())
    }

    // ---- 测试辅助 ----

    /** 某业务自然日某钟面的绝对毫秒（业务时区口径） */
    private fun millisAt(epochDay: Long, time: LocalTime): Long =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, time, TimerTestEnv.ZONE).toEpochMilli()

    private companion object {

        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
