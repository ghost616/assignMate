package com.assignmate.app.timer.data

import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.domain.TimerConstants
import com.assignmate.app.timer.domain.TimerReminderRules
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒同步协调器的补充边界单测（在 HomeworkReminderCoordinatorTest 之外）：过期宽限恰好边界、
 * 进行中作业取消、按学生同步的学生隔离与幂等性、空清单不产生调度。
 */
class HomeworkReminderCoordinatorEdgeCaseTest {

    @Test
    fun `触发时刻恰好落在过期宽限边界时仍设置提醒`() = runTest {
        // 触发时刻 == 现在 - 宽限：规则为「不早于」即仍设置（刚过点由系统立即触发）
        val fixture = fixture()
        val homework = fixture.put(
            timerTestHomework(
                id = 1L,
                status = HomeworkStatus.PENDING,
                startTimeMillis = BASE - TimerConstants.REMINDER_STALE_GRACE_MILLIS,
            ),
        )

        val result = fixture.coordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.ScheduledExact, result)
        assertTrue(fixture.scheduler.cancelled.isEmpty())
    }

    @Test
    fun `进行中的作业会取消既有闹钟`() = runTest {
        val fixture = fixture()
        val homework = fixture.put(
            timerTestHomework(
                id = 6L,
                status = HomeworkStatus.IN_PROGRESS,
                startTimeMillis = BASE + 30 * MINUTE,
            ),
        )

        val result = fixture.coordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.NotScheduled, result)
        assertTrue(fixture.scheduler.cancelled.contains(homework.id))
        assertTrue(fixture.scheduler.scheduled.isEmpty())
    }

    @Test
    fun `按学生同步只处理该学生名下作业`() = runTest {
        val fixture = fixture()
        val mine = fixture.put(
            timerTestHomework(id = 1L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE),
        )
        val others = fixture.put(
            timerTestHomework(
                id = 2L,
                studentId = 99L,
                status = HomeworkStatus.PENDING,
                startTimeMillis = BASE + MINUTE,
            ),
        )

        fixture.coordinator.syncStudentReminders(TimerTestEnv.STUDENT_ID)

        assertEquals(listOf(mine.id), fixture.scheduler.scheduled.map { it.homeworkId })
        assertFalse("他人名下作业不应被本学生同步影响", fixture.scheduler.cancelled.contains(others.id))
    }

    @Test
    fun `按学生同步可重复调用且调度结果稳定`() = runTest {
        val fixture = fixture()
        val pending = fixture.put(
            timerTestHomework(id = 1L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE),
        )
        val completed = fixture.put(timerTestHomework(id = 2L, status = HomeworkStatus.COMPLETED))

        fixture.coordinator.syncStudentReminders(TimerTestEnv.STUDENT_ID)
        val firstScheduled = fixture.scheduler.scheduled.toList()
        val firstCancelled = fixture.scheduler.cancelled.toList()

        fixture.coordinator.syncStudentReminders(TimerTestEnv.STUDENT_ID)

        assertEquals(
            "重复同步应产生相同的设置调用序列",
            firstScheduled + firstScheduled,
            fixture.scheduler.scheduled,
        )
        assertEquals(firstCancelled + firstCancelled, fixture.scheduler.cancelled)
        assertEquals(listOf(pending.id), fixture.scheduler.scheduled.map { it.homeworkId }.distinct())
    }

    @Test
    fun `清单为空时同步不产生任何调度调用`() = runTest {
        val fixture = fixture()

        fixture.coordinator.syncStudentReminders(TimerTestEnv.STUDENT_ID)

        assertTrue(fixture.scheduler.scheduled.isEmpty())
        assertTrue(fixture.scheduler.cancelled.isEmpty())
    }

    @Test
    fun `已记录且未排定时间的作业同步走取消`() = runTest {
        val fixture = fixture()
        val homework = fixture.put(timerTestHomework(id = 8L, status = HomeworkStatus.RECORDED))

        val result = fixture.coordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.NotScheduled, result)
        assertEquals(listOf(homework.id), fixture.scheduler.cancelled)
    }

    @Test
    fun `单条同步重复调用按同一触发时刻重复上报以便覆盖旧闹钟`() = runTest {
        val fixture = fixture()
        val homework = fixture.put(
            timerTestHomework(id = 9L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 5 * MINUTE),
        )

        fixture.coordinator.syncHomeworkReminder(homework.id)
        fixture.coordinator.syncHomeworkReminder(homework.id)

        assertEquals(2, fixture.scheduler.scheduled.size)
        // 请求码由作业 id 稳定派生（见 TimerReminderRules），故两次调度指向同一闹钟槽位 → 后者覆盖前者
        assertEquals(
            listOf(TimerReminderRules.requestCodeOf(homework.id), TimerReminderRules.requestCodeOf(homework.id)),
            fixture.scheduler.scheduled.map { TimerReminderRules.requestCodeOf(it.homeworkId) },
        )
        assertEquals(
            listOf(BASE + 5 * MINUTE, BASE + 5 * MINUTE),
            fixture.scheduler.scheduled.map { it.triggerAtMillis },
        )
    }

    // ---- 测试夹具 ----

    private class Fixture(
        val scheduler: FakeAlarmScheduler,
        val homeworkRepository: FakeHomeworkRepository,
        val clock: MutableClock,
        val coordinator: HomeworkReminderCoordinator,
    ) {

        fun put(homework: HomeworkItem): HomeworkItem = homework.also { homeworkRepository.put(it) }
    }

    private fun fixture(exactAlarmsAvailable: Boolean = true): Fixture {
        val scheduler = FakeAlarmScheduler(exactAlarmsAvailable = exactAlarmsAvailable)
        val homeworkRepository = FakeHomeworkRepository()
        val clock = MutableClock(BASE)
        return Fixture(
            scheduler = scheduler,
            homeworkRepository = homeworkRepository,
            clock = clock,
            coordinator = HomeworkReminderCoordinator(
                homeworkRepository,
                scheduler,
                clock,
                TimerTestEnv.ZONE,
            ),
        )
    }

    /** 仅用于在用例内引用请求码规则，避免与领域包重复 import 冲突 */
    private companion object {

        const val BASE = TimerTestEnv.FIXED_MILLIS
        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE
    }
}
