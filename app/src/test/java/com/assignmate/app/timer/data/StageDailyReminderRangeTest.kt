package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「阶段范围变更后旧区间内的每日闹钟不再触发」的三条路径回归（皐陶审查 #5 的调度侧口径）。
 *
 * 三条路径（风后计划第二条）：
 * 1. **范围缩短**：超出新范围的旧闹钟被精确取消，登记表只留新范围；
 * 2. **作业删除**：作业已不可读，登记表仍能枚举出全部已设天并逐一取消，登记清空；
 * 3. **类型切回 TODAY**：阶段作业改回当天作业，全部逐日闹钟取消、不回落为「单次 + 残留逐日」。
 *
 * 与 [com.assignmate.app.timer.service.StageDailyReminderDeliveryTest]（投递侧核对：即便同步没跑到也不展示）
 * 共同构成「取消 + 投递核对」双保险。
 */
class StageDailyReminderRangeTest {

    @Test
    fun `阶段范围缩短后旧区间内的每日闹钟被精确取消且不再登记`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.TWO_WEEKS)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)
        assertEquals("前置：两周 = 14 天登记在册", 14, env.reminderScheduleStore.load(homework.id).size)

        env.useParentSession()
        val updated = env.homeworkRepository.updateTemplate(
            homeworkId = homework.id,
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = HomeworkDailyDeadlineCodec.encodeStageDaily(today, LocalTime.of(21, 0)),
            sessionRole = Role.PARENT,
        )
        assertTrue("用例前提：范围缩短应成功", updated is HomeworkOperationResult.Success)

        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        val staleDays = (7L until 14L).map { today + it }.sorted()
        assertEquals(
            "原第 8~14 天（已超出新范围）必须被精确取消",
            staleDays,
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertEquals(
            "登记表只保留新范围的一周（旧天不再可枚举 → 不会再触发）",
            (0L until 7L).map { today + it }.toSet(),
            env.reminderScheduleStore.load(homework.id),
        )
    }

    @Test
    fun `作业删除后登记在册的每一天都被取消且登记清空`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.ONE_WEEK)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        env.useParentSession()
        val deleted = env.homeworkRepository.deleteHomework(homework.id, Role.PARENT)
        assertTrue("用例前提：删除作业应成功", deleted is HomeworkOperationResult.Success)

        // 作业已不可读：协调器只能靠登记表枚举要取消哪几天（这正是登记表存在的理由）
        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(
            "登记在册的 7 天全部被取消，不残留无效闹钟",
            (0L until 7L).map { today + it }.sorted(),
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertTrue("登记表清空", env.reminderScheduleStore.load(homework.id).isEmpty())
    }

    @Test
    fun `类型切回当天作业时旧逐日闹钟被取消而当天作业改走单次提醒口径`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.ONE_WEEK)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        env.useParentSession()
        // 当天作业的提醒按「已排定开始时间」触发；本级用例先覆盖「切回 TODAY 后不残留逐日闹钟」
        val startTime = millisAt(today, LocalTime.of(23, 0))
        val updated = env.homeworkRepository.updateTemplate(
            homeworkId = homework.id,
            type = HomeworkType.TODAY,
            stageRange = null,
            deadline = Instant.ofEpochMilli(startTime),
            sessionRole = Role.PARENT,
        )
        assertTrue("用例前提：类型切换应成功", updated is HomeworkOperationResult.Success)

        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(
            "逐日残留全部取消（阶段语义已不存在）",
            (0L until 7L).map { today + it }.sorted(),
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertTrue("登记表清空，不遗留可再次触发的逐日闹钟", env.reminderScheduleStore.load(homework.id).isEmpty())
        assertNull(
            "已排定时间的当天作业本应设单次提醒：本用例用的是 stage 作业（startTime 为空），故此处不设",
            env.alarmScheduler.lastScheduledFor(homework.id),
        )
    }

    @Test
    fun `当天作业汇总同步时清掉阶段遗留的逐日闹钟并改设单次提醒`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(19, 0)))
        val startTime = millisAt(today, LocalTime.of(23, 0))
        val homework = env.seedHomework(startTime = Instant.ofEpochMilli(startTime))
        // 前置：该作业曾经是阶段作业（登记表里残留旧区间），随后被改成当天作业
        env.reminderScheduleStore.save(homework.id, (0L until 7L).map { today + it }.toSet())

        env.reminderCoordinator.syncStudentReminders(TimerTestEnv.STUDENT_ID)

        assertEquals(
            "阶段遗留的 7 天全部被取消（不因类型切换而残留）",
            (0L until 7L).map { today + it }.sorted(),
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertEquals(
            "TODAY 语义不变：按排定开始时间单次提醒",
            startTime,
            env.alarmScheduler.lastScheduledFor(homework.id)?.triggerAtMillis,
        )
        assertTrue("登记表清空", env.reminderScheduleStore.load(homework.id).isEmpty())
        assertTrue("不再产生任何逐日闹钟", env.alarmScheduler.scheduledDaysOf(homework.id).isEmpty())
    }

    // ---- 测试辅助 ----

    /** 某业务自然日某钟面的绝对毫秒（业务时区口径，与调度口径同源） */
    private fun millisAt(epochDay: Long, time: LocalTime): Long =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, time, TimerTestEnv.ZONE).toEpochMilli()
}
