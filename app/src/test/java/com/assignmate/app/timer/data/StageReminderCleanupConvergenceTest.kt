package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.domain.TimerConstants
import com.assignmate.app.timer.domain.TimerReminderRules
import com.assignmate.app.timer.service.HomeworkAlarmReceiver
import com.assignmate.app.timer.service.ReminderCheck
import com.assignmate.app.timer.service.ReminderCleanup
import com.assignmate.app.timer.service.ReminderVerification
import io.mockk.mockk
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒清理的**收敛与幂等**补充用例（本轮测试智能体新增，补齐既有用例未覆盖的路径）。
 *
 * 依据待测功能说明第 3、4 项，锁定三件事：
 * 1. `cancelStaleDays` 的「登记表收敛为 desiredDays」在**只走单次提醒路径**（类型切回 TODAY、
 *    登记表仍为阶段遗留）时也必须发生——这正是修复轮的缺口，否则登记表永远清不掉；
 * 2. 清理是**幂等**的：第二次同步（登记表已收敛）不得再产生多余的取消调用，
 *    否则每次进入清单都会对系统闹钟做无意义的破坏性操作；
 * 3. 调度侧的「按天请求码」与 `HomeworkReminderRules.requestCodeOf` 的**区间隔离不能撞码**——
 *    投递侧 `ReminderCleanup.android` 依赖同一映射精确取消，撞码会让清理取消掉**别的作业**的闹钟。
 */
class StageReminderCleanupConvergenceTest {

    // ---- 1. 登记表收敛（修复轮 #5 的真实缺口） ----

    @Test
    fun `类型切回当天作业时阶段遗留登记表被收敛而不残留无效天`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        val homework = env.seedHomework(startTime = Instant.ofEpochMilli(millisAt(today, LocalTime.of(23, 0))))
        // 前置：该作业曾是阶段作业，登记表残留旧区间（同步漏跑 / 页面未进入都会留下这种状态）
        val staleDays = (0L until 5L).map { today + it }.toSet()
        env.reminderScheduleStore.save(homework.id, staleDays)

        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(
            "残留的每一天都被精确取消",
            staleDays.sorted(),
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertTrue(
            "登记表必须收敛为空——否则只走单次提醒路径时永远清不掉（修复轮 #5 的缺口）",
            env.reminderScheduleStore.load(homework.id).isEmpty(),
        )
    }

    @Test
    fun `已完成当天后登记表只剩未完成的天且旧天被取消`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        env.clock.set(millisAt(today, LocalTime.of(20, 0)))
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.ONE_WEEK)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        // 打卡完成「今天 + 明天」两天：这两天必须从提醒集合里消失（其余 5 天照设）
        listOf(today, today + 1L).forEach { epochDay ->
            env.dailyRecordRepository.upsertStatus(
                homeworkId = homework.id,
                studentId = TimerTestEnv.STUDENT_ID,
                epochDay = epochDay,
                status = HomeworkDayStatus.COMPLETED,
                nowMillis = env.clock.currentTimeMillis(),
            )
        }

        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(
            "已完成的两天被精确取消",
            listOf(today, today + 1L).sorted(),
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertEquals(
            "登记表只保留仍未完成的天（与「已设集合」同口径收敛）",
            (2L until 7L).map { today + it }.toSet(),
            env.reminderScheduleStore.load(homework.id),
        )
    }

    // ---- 2. 幂等：第二次同步不得重复破坏性操作 ----

    @Test
    fun `重复同步同一阶段作业不产生重复取消调用`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.ONE_WEEK)

        env.reminderCoordinator.syncHomeworkReminder(homework.id)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertTrue(
            "登记表与目标集合一致时不得再逐日取消（已收敛即无事可做）",
            env.alarmScheduler.cancelledDaysOf(homework.id).isEmpty(),
        )
        assertEquals(
            "逐日闹钟仍完整覆盖一周（重设即覆盖，不因重复同步丢天）",
            (0L until 7L).map { today + it },
            env.alarmScheduler.scheduledDaysOf(homework.id),
        )
    }

    @Test
    fun `登记表为空时同步当天作业不产生逐日取消`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(type = HomeworkType.TODAY)

        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertTrue(
            "无登记（从未是阶段作业）时不做逐日取消，避免对系统闹钟做无意义操作",
            env.alarmScheduler.cancelledDaysOf(homework.id).isEmpty(),
        )
        assertTrue(env.reminderScheduleStore.load(homework.id).isEmpty())
    }

    @Test
    fun `作业删除后再同步不残留登记且取消幂等`() = runTest {
        val env = TimerTestEnv()
        val today = env.todayEpochDay()
        val homework = env.stageHomework(startEpochDay = today, stageRange = StageRange.ONE_WEEK)
        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        env.useParentSession()
        val deleted = env.homeworkRepository.deleteHomework(homework.id, Role.PARENT)
        assertTrue("用例前提：删除应成功", deleted is HomeworkOperationResult.Success)

        env.reminderCoordinator.syncHomeworkReminder(homework.id)
        val firstRound = env.alarmScheduler.cancelledDaysOf(homework.id).sorted()
        // 第二次同步：登记表已空，不应再枚举出任何天
        env.reminderCoordinator.syncHomeworkReminder(homework.id)

        assertEquals(
            "第一轮按登记表枚举出全部已设天",
            (0L until 7L).map { today + it }.sorted(),
            firstRound,
        )
        assertEquals(
            "登记表已空 → 第二轮不再产生逐日取消（幂等收口）",
            firstRound,
            env.alarmScheduler.cancelledDaysOf(homework.id).sorted(),
        )
        assertTrue(env.reminderScheduleStore.load(homework.id).isEmpty())
    }

    // ---- 3. 请求码口径：投递侧清理必须与调度侧同码且不撞码 ----

    @Test
    fun `作业与阶段的任意日组合请求码互不相同且与单次提醒区间隔离`() {
        val realisticDays = (0L until 31L).map { TODAY + it }
        val homeworkIds = (1L..200L).toList()

        val dayCodes = homeworkIds.flatMap { id -> realisticDays.map { day -> id to day } }
            .map { (id, day) -> TimerReminderRules.requestCodeOf(id, day) }
            .toSet()
        val singleCodes = homeworkIds.map { id -> TimerReminderRules.requestCodeOf(id) }.toSet()

        assertEquals(
            "200 条作业 × 31 天的按天请求码必须两两不同：撞码会让清理取消掉别的作业的闹钟",
            homeworkIds.size * realisticDays.size,
            dayCodes.size,
        )
        assertTrue(
            "按天请求码不得与单次提醒请求码重合（区间刻意错开）",
            dayCodes.intersect(singleCodes).isEmpty(),
        )
        assertTrue(
            "按天请求码必须落在独立区间 [DAY_BASE, DAY_BASE + MODULUS)",
            dayCodes.all {
                it >= TimerConstants.ALARM_REQUEST_CODE_DAY_BASE &&
                    it < TimerConstants.ALARM_REQUEST_CODE_DAY_BASE + TimerConstants.ALARM_REQUEST_CODE_MODULUS
            },
        )
        assertTrue(
            "单次请求码必须落在 [BASE, BASE + MODULUS)",
            singleCodes.all {
                it >= TimerConstants.ALARM_REQUEST_CODE_BASE &&
                    it < TimerConstants.ALARM_REQUEST_CODE_BASE + TimerConstants.ALARM_REQUEST_CODE_MODULUS
            },
        )
    }

    @Test
    fun `投递侧清理与调度侧使用同一请求码口径使精确取消可命中`() {
        val homeworkId = 42L
        val epochDay = TODAY + 3L

        // 调度侧（AndroidHomeworkAlarmScheduler）与投递侧（ReminderCleanup.android）共用同一映射，
        // 才能用「作业 + 自然日」重建同码 PendingIntent 并精确取消。
        assertEquals(
            "按天：同（作业，日）恒定映射（重设即覆盖、取消能命中）",
            TimerReminderRules.requestCodeOf(homeworkId, epochDay),
            TimerReminderRules.requestCodeOf(homeworkId, epochDay),
        )
        assertFalse(
            "不同天的请求码必须不同（只取消该天，不影响其它天）",
            TimerReminderRules.requestCodeOf(homeworkId, epochDay) ==
                TimerReminderRules.requestCodeOf(homeworkId, epochDay + 1L),
        )
        assertFalse(
            "逐日请求码与单次请求码不同（epochDay 为 null 时走单次映射）",
            TimerReminderRules.requestCodeOf(homeworkId, epochDay) ==
                TimerReminderRules.requestCodeOf(homeworkId),
        )
    }

    @Test
    fun `投递侧清理失败经投递入口返回 false 而不被吞掉`() = runTest {
        val attempts = mutableListOf<Pair<Long, Long?>>()

        val cleaned = HomeworkAlarmReceiver().deliver(
            context = mockk(relaxed = true),
            homeworkId = 42L,
            epochDay = TODAY + 3L,
            cleanup = ReminderCleanup { homeworkId, epochDay ->
                attempts += homeworkId to epochDay
                false
            },
            check = ReminderCheck { _, _ -> ReminderVerification.Stale },
        )

        assertFalse(
            "cleanup failure must surface from the delivery entry (before the fix the runCatching inside " +
                "ReminderCleanup.android swallowed it without any trace)",
            cleaned,
        )
        assertEquals(
            "cleanup must still be attempted exactly once for (homeworkId, epochDay)",
            listOf(42L to (TODAY + 3L)),
            attempts,
        )
    }

    // ---- 4. 区间核对边界（含首尾、极端值） ----

    @Test
    fun `区间核对覆盖首尾且对极端自然日不误判`() {
        val start = TODAY - 100L
        val range = StageRange.TWO_WEEKS
        val item = timerTestHomework(
            id = 91L,
            type = HomeworkType.STAGE,
            stageRange = range,
            stageStartEpochDay = start,
            stageDailyTime = LocalTime.of(21, 0),
        )
        val last = item.stageLastEpochDay ?: error("阶段作业应有派生的覆盖末日")

        assertEquals("派生末日 = 起始日 + 天数 - 1", start + range.days - 1L, last)
        assertTrue("首日含", TimerReminderRules.isWithinStageRange(item, start))
        assertTrue("末日含", TimerReminderRules.isWithinStageRange(item, last))
        assertFalse("首日前一天不含", TimerReminderRules.isWithinStageRange(item, start - 1L))
        assertFalse("末日后一天不含", TimerReminderRules.isWithinStageRange(item, last + 1L))
        assertFalse(
            "极端大值不得被误判为在区间内",
            TimerReminderRules.isWithinStageRange(item, Long.MAX_VALUE),
        )
        assertFalse(
            "极端小值不得被误判为在区间内",
            TimerReminderRules.isWithinStageRange(item, Long.MIN_VALUE),
        )
    }

    // ---- 测试辅助 ----

    /** 某业务自然日某钟面的绝对毫秒（业务时区口径，与调度口径同源） */
    private fun millisAt(epochDay: Long, time: LocalTime): Long =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, time, TimerTestEnv.ZONE).toEpochMilli()

    private companion object {

        /** 与 [TimerTestEnv] 固定时钟同一业务自然日 */
        val TODAY: Long = HomeworkDailyDeadlineCodec.absoluteEpochDay(TimerTestEnv.FIXED_MILLIS, TimerTestEnv.ZONE)
    }
}