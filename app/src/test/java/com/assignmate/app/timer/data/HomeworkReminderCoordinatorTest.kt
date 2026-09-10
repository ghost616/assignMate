package com.assignmate.app.timer.data

import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.domain.TimerConstants
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒同步协调器单测（真实协调器 + 替身闹钟 + 内存作业仓库 + 可推进时钟）：
 * 覆盖「触发时刻与作业绑定」「不该提醒时取消」「精确闹钟不可用时的降级结果」
 * 「按学生批量同步」「作业不存在/删除后取消」等提醒维护语义。
 */
class HomeworkReminderCoordinatorTest {

    @Test
    fun `待完成作业按开始时间设置到点提醒且携带作业内容`() = runTest {
        val fixture = fixture()
        val homework = fixture.put(
            timerTestHomework(
                id = 7L,
                status = HomeworkStatus.PENDING,
                content = "语文生字",
                startTimeMillis = BASE + 30 * MINUTE,
            ),
        )

        val result = fixture.coordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.ScheduledExact, result)
        val scheduled = fixture.scheduler.lastScheduledFor(homework.id)
        assertNotNull(scheduled)
        assertEquals("语文生字", scheduled?.content)
        assertEquals(BASE + 30 * MINUTE, scheduled?.triggerAtMillis)
        assertTrue(fixture.scheduler.cancelled.isEmpty())
    }

    @Test
    fun `已记录与已完成的作业会取消既有闹钟`() = runTest {
        listOf(HomeworkStatus.RECORDED, HomeworkStatus.COMPLETED).forEach { status ->
            val fixture = fixture()
            val homework = fixture.put(
                timerTestHomework(id = 3L, status = status, startTimeMillis = BASE + 30 * MINUTE),
            )

            val result = fixture.coordinator.syncHomeworkReminder(homework.id)

            assertEquals(AlarmScheduleResult.NotScheduled, result)
            assertTrue("$status 应取消闹钟", fixture.scheduler.cancelled.contains(homework.id))
            assertTrue(fixture.scheduler.scheduled.isEmpty())
        }
    }

    @Test
    fun `触发时刻过期超过宽限的作业取消闹钟`() = runTest {
        val fixture = fixture()
        val homework = fixture.put(
            timerTestHomework(
                id = 4L,
                status = HomeworkStatus.PENDING,
                startTimeMillis = BASE - TimerConstants.REMINDER_STALE_GRACE_MILLIS - 1L,
            ),
        )

        val result = fixture.coordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.NotScheduled, result)
        assertTrue(fixture.scheduler.cancelled.contains(homework.id))
    }

    @Test
    fun `精确闹钟不可用时返回降级结果`() = runTest {
        val fixture = fixture(exactAlarmsAvailable = false)
        val homework = fixture.put(
            timerTestHomework(id = 5L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE),
        )

        val result = fixture.coordinator.syncHomeworkReminder(homework.id)

        assertEquals(AlarmScheduleResult.ScheduledInexact, result)
        assertEquals(1, fixture.scheduler.scheduled.size)
    }

    @Test
    fun `按学生同步只给应提醒的作业设闹钟其余一律取消`() = runTest {
        val fixture = fixture()
        val pending = fixture.put(
            timerTestHomework(id = 1L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 10 * MINUTE),
        )
        val completed = fixture.put(
            timerTestHomework(id = 2L, status = HomeworkStatus.COMPLETED, startTimeMillis = BASE + 10 * MINUTE),
        )
        val recorded = fixture.put(timerTestHomework(id = 3L, status = HomeworkStatus.RECORDED))

        fixture.coordinator.syncStudentReminders(TimerTestEnv.STUDENT_ID)

        assertEquals(listOf(pending.id), fixture.scheduler.scheduled.map { it.homeworkId })
        assertEquals(setOf(completed.id, recorded.id), fixture.scheduler.cancelled.toSet())
    }

    @Test
    fun `作业不存在时同步走取消`() = runTest {
        val fixture = fixture()

        val result = fixture.coordinator.syncHomeworkReminder(404L)

        assertEquals(AlarmScheduleResult.NotScheduled, result)
        assertTrue(fixture.scheduler.cancelled.contains(404L))
        assertNull(fixture.scheduler.lastScheduled())
    }

    @Test
    fun `取消单条作业提醒直接透传调度器`() = runTest {
        val fixture = fixture()

        fixture.coordinator.cancelHomeworkReminder(9L)

        assertEquals(listOf(9L), fixture.scheduler.cancelled)
    }

    // ---- 测试夹具 ----

    private class Fixture(
        val scheduler: FakeAlarmScheduler,
        val homeworkRepository: FakeHomeworkRepository,
        val clock: MutableClock,
        val coordinator: HomeworkReminderCoordinator,
    ) {

        /** 登记作业项并返回（便于用例直接拿到 id） */
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
            coordinator = HomeworkReminderCoordinator(homeworkRepository, scheduler, clock),
        )
    }

    private companion object {

        const val BASE = TimerTestEnv.FIXED_MILLIS
        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE
    }
}
