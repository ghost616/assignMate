package com.assignmate.app.navigation

import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.AlarmScheduleResult
import com.assignmate.app.timer.data.FakeAlarmScheduler
import com.assignmate.app.timer.data.FakeHomeworkRepository
import com.assignmate.app.timer.data.HomeworkAlarmScheduler
import com.assignmate.app.timer.data.HomeworkReminderCoordinator
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import com.assignmate.app.timer.domain.TimerConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒同步接线层单测（真实协调器 + 替身闹钟 + 内存作业仓库 + 可注入作用域）：
 * 覆盖「删除作业后取消被调用」「重排时间保存后按新时刻同步被调用」
 * 「同步异常被吞掉、不影响主流程」三条语义——正是皋陶审查指出的时效性缺口。
 *
 * 作用域刻意做成可注入：生产注入进程级作用域（页面 popBackStack 后仍能跑完），
 * 测试注入 [UnconfinedTestDispatcher]，在调用处即可确定性观察结果。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderSyncDispatcherTest {

    @Test
    fun `删除作业后取消该作业的闹钟`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        val fixture = fixture(scheduler)
        val homework = fixture.put(
            timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE),
        )

        fixture.dispatcher.cancelHomeworkReminderSync(homework.id)

        assertEquals("删除后必须取消该作业的闹钟", listOf(7L), scheduler.cancelled)
        assertTrue("取消不应顺带设置闹钟", scheduler.scheduled.isEmpty())
    }

    @Test
    fun `重排时间保存后按新时刻重设闹钟`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        val fixture = fixture(scheduler)
        val homework = fixture.put(
            timerTestHomework(id = 9L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE),
        )

        fixture.dispatcher.syncHomeworkReminder(homework.id)

        assertEquals(
            "首次同步应按当前排定时间设置",
            BASE + MINUTE - TimerConstants.REMINDER_LEAD_MILLIS,
            scheduler.lastTriggerOf(homework.id),
        )

        // 模拟「重排到更晚的时刻」：仓库数据更新后再次同步，闹钟应覆盖为新时刻
        fixture.put(
            timerTestHomework(id = 9L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 30 * MINUTE),
        )
        fixture.dispatcher.syncHomeworkReminder(homework.id)

        assertEquals(
            "重排后闹钟应指向新时刻",
            BASE + 30 * MINUTE - TimerConstants.REMINDER_LEAD_MILLIS,
            scheduler.lastTriggerOf(homework.id),
        )
        assertEquals(
            "同一作业按 id 覆盖重设（两次调用同一请求码）",
            2,
            scheduler.scheduled.count { it.first == 9L },
        )
    }

    @Test
    fun `作业已不存在时同步会取消既有闹钟`() = runTest {
        val scheduler = RecordingAlarmScheduler()
        val fixture = fixture(scheduler)

        fixture.dispatcher.syncHomeworkReminder(404L)

        assertEquals(listOf(404L), scheduler.cancelled)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `调度器抛异常时被接线层吞掉且不外抛给调用方`() = runTest {
        val throwing = ThrowingAlarmScheduler()
        val fixture = fixture(scheduler = throwing)
        val homework = fixture.put(
            timerTestHomework(id = 2L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE),
        )
        val coordinator = fixture.coordinator

        // 前置事实：该替身下协调器两条链路都会抛出（说明「吞异常」确有对象可吞）
        val syncFailure = runCatching { coordinator.syncHomeworkReminder(homework.id) }.exceptionOrNull()
        val cancelFailure = runCatching { coordinator.cancelHomeworkReminder(homework.id) }.exceptionOrNull()
        assertNotNull("同步链路应抛出系统调用异常", syncFailure)
        assertNotNull("取消链路应抛出系统调用异常", cancelFailure)

        // 经接线层调用（且不做 advanceUntilIdle）：不得向调用方抛出，删除/保存主流程不受影响
        fixture.dispatcher.cancelHomeworkReminderSync(homework.id)
        fixture.dispatcher.syncHomeworkReminder(homework.id)

        // 调用确实透传到调度器（记录写于抛异常之前）：前置检查 1 次 + 接线层 1 次
        assertEquals(2, throwing.cancelled.count { it == homework.id })
        assertEquals(listOf(homework.id, homework.id), throwing.scheduled.map { it.first })
    }

    // ---- 测试夹具 ----

    private class Fixture(
        val repository: FakeHomeworkRepository,
        val coordinator: HomeworkReminderCoordinator,
        val dispatcher: ReminderSyncDispatcher,
    ) {

        /** 登记作业项并返回（便于用例直接拿到 id） */
        fun put(homework: HomeworkItem): HomeworkItem = homework.also { repository.put(it) }
    }

    /** 记录调度调用（用于断言语义）且可被子类改造成「抛异常」的替身代理 */
    private open class RecordingAlarmScheduler(
        private val delegate: HomeworkAlarmScheduler = FakeAlarmScheduler(),
    ) : HomeworkAlarmScheduler {

        val scheduled = mutableListOf<Triple<Long, String, Long>>()
        val cancelled = mutableListOf<Long>()

        override fun canScheduleExactAlarms(): Boolean = delegate.canScheduleExactAlarms()

        override fun schedule(
            homeworkId: Long,
            content: String,
            triggerAtMillis: Long,
        ): AlarmScheduleResult {
            scheduled += Triple(homeworkId, content, triggerAtMillis)
            return delegate.schedule(homeworkId, content, triggerAtMillis)
        }

        override fun cancel(homeworkId: Long) {
            cancelled += homeworkId
            delegate.cancel(homeworkId)
        }

        /**
         * 按天调度方法与单次方法一样**显式委派**（接口已把它们改为抽象方法，见 #8-2 防静默退化）：
         * 本替身只关心接线层语义，逐日记录交由 [FakeAlarmScheduler] 承担。
         */
        override fun scheduleDaily(
            homeworkId: Long,
            epochDay: Long,
            content: String,
            triggerAtMillis: Long,
        ): AlarmScheduleResult =
            delegate.scheduleDaily(homeworkId, epochDay, content, triggerAtMillis)

        override fun cancelDaily(homeworkId: Long, epochDay: Long) {
            delegate.cancelDaily(homeworkId, epochDay)
        }

        /** 某作业最近一次设置的触发时刻（未设置过返回 null） */
        fun lastTriggerOf(homeworkId: Long): Long? =
            scheduled.lastOrNull { it.first == homeworkId }?.third
    }

    /**
     * 构建被测接线层：作用域复用 [TestScope.testScheduler]，
     * 否则接线层内部 `scope.launch` 会跑在另一个测试调度器上、`runTest` 永不推进它，
     * 造成「同步没执行」的假失败（Unconfined 仅在共享调度器时才在调用处立即执行）。
     */
    private fun TestScope.fixture(scheduler: HomeworkAlarmScheduler = RecordingAlarmScheduler()): Fixture {
        val repository = FakeHomeworkRepository()
        val clock = MutableClock(BASE)
        val coordinator = HomeworkReminderCoordinator(repository, scheduler, clock, TimerTestEnv.ZONE)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            repository = repository,
            coordinator = coordinator,
            dispatcher = ReminderSyncDispatcher(coordinator, scope),
        )
    }

    /**
     * 在真实替身之上让系统调用一律抛异常的调度器（先记录再抛）：
     * 验证接线层「失败静默降级、不干扰主流程」。
     */
    private class ThrowingAlarmScheduler : RecordingAlarmScheduler() {

        override fun schedule(
            homeworkId: Long,
            content: String,
            triggerAtMillis: Long,
        ): AlarmScheduleResult {
            super.schedule(homeworkId, content, triggerAtMillis)
            throw IllegalStateException("调度器不可用")
        }

        override fun cancel(homeworkId: Long) {
            super.cancel(homeworkId)
            throw IllegalStateException("调度器不可用")
        }
    }

    private companion object {

        const val BASE = TimerTestEnv.FIXED_MILLIS
        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE
    }
}
