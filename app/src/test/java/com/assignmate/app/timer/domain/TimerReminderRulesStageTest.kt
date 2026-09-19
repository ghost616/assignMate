package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.data.timerTestHomework
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段作业每日触发时刻与「按天请求码」的纯规则单测（[TimerReminderRules]）。
 *
 * 覆盖边界：覆盖区间起止、起点与「今天」取较晚者、过期宽限 ±1ms、
 * 非阶段作业 / 缺每日时刻 / 阶段已结束一律返回空列表，
 * 以及「作业 + 自然日」请求码的稳定性与区间隔离，另含毫秒转分钟的取整口径。
 */
class TimerReminderRulesStageTest {

    @Test
    fun `阶段作业逐日触发起自今天且止于覆盖最后一天`() {
        val item = stageItem(startEpochDay = TODAY, range = StageRange.ONE_WEEK, time = LocalTime.of(21, 0))

        val triggers = TimerReminderRules.stageDailyTriggers(item, TODAY, nowMillis = at(TODAY, 20, 0), zoneId = ZONE)

        assertEquals("一周 = 7 天", 7, triggers.size)
        assertEquals((0L until 7L).map { TODAY + it }, triggers.map { it.epochDay })
        triggers.forEach { trigger ->
            assertEquals(at(trigger.epochDay, 21, 0), trigger.triggerAtMillis)
        }
    }

    @Test
    fun `起点已过的日子不补提醒只保留今天起`() {
        // 阶段起始日为 3 天前，今天才打开应用：只从今天起设置
        val item = stageItem(startEpochDay = TODAY - 3, range = StageRange.ONE_WEEK, time = LocalTime.of(21, 0))

        val triggers = TimerReminderRules.stageDailyTriggers(item, TODAY, nowMillis = at(TODAY, 20, 0), zoneId = ZONE)

        assertEquals("覆盖末日 = 起始日 + 6", TODAY + 3, triggers.last().epochDay)
        assertEquals(listOf(TODAY, TODAY + 1, TODAY + 2, TODAY + 3), triggers.map { it.epochDay })
    }

    @Test
    fun `当天触发时刻已过宽限则跳过当天`() {
        val item = stageItem(startEpochDay = TODAY, range = StageRange.ONE_WEEK, time = LocalTime.of(21, 0))
        val grace = TimerConstants.REMINDER_STALE_GRACE_MILLIS

        // 恰好等于「触发时刻 + 宽限」：仍在窗口内（含边界）
        val onBoundary = TimerReminderRules.stageDailyTriggers(
            item,
            TODAY,
            nowMillis = at(TODAY, 21, 0) + grace,
            zoneId = ZONE,
        )
        assertEquals(TODAY, onBoundary.first().epochDay)

        // 超出 1ms：跳过当天，从明天起
        val beyond = TimerReminderRules.stageDailyTriggers(
            item,
            TODAY,
            nowMillis = at(TODAY, 21, 0) + grace + 1L,
            zoneId = ZONE,
        )
        assertEquals(TODAY + 1, beyond.first().epochDay)
        assertEquals(6, beyond.size)
    }

    @Test
    fun `非阶段作业与缺少每日截止时刻均不产生逐日提醒`() {
        val today = timerTestHomework(id = 1L, type = HomeworkType.TODAY)
        val stageWithoutTime = timerTestHomework(
            id = 2L,
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            stageStartEpochDay = TODAY,
        )

        assertTrue(TimerReminderRules.stageDailyTriggers(today, TODAY, at(TODAY, 20, 0), ZONE).isEmpty())
        assertTrue(TimerReminderRules.stageDailyTriggers(stageWithoutTime, TODAY, at(TODAY, 20, 0), ZONE).isEmpty())
    }

    @Test
    fun `阶段已整体结束时不产生提醒`() {
        val item = stageItem(startEpochDay = TODAY - 10, range = StageRange.ONE_WEEK, time = LocalTime.of(21, 0))

        val triggers = TimerReminderRules.stageDailyTriggers(item, TODAY, nowMillis = at(TODAY, 20, 0), zoneId = ZONE)

        assertTrue("覆盖末日已过（起始日 + 6 < 今天）", triggers.isEmpty())
    }

    @Test
    fun `每日截止时刻取自 deadline 编码而非创建时刻`() {
        // 阶段起始日由 deadline 编码还原（与创建时刻无关），逐日区间跟随阶段范围
        val stageFromTomorrow = timerTestHomework(
            id = 3L,
            type = HomeworkType.STAGE,
            stageRange = StageRange.TWO_WEEKS,
            stageStartEpochDay = TODAY + 1,
            stageDailyTime = LocalTime.of(8, 30),
            createdAtMillis = 0L,
        )

        val triggers = TimerReminderRules.stageDailyTriggers(stageFromTomorrow, TODAY, at(TODAY, 7, 0), ZONE)

        assertEquals("两周 = 14 天，自明天起", (1L..14L).map { TODAY + it }, triggers.map { it.epochDay })
        assertTrue(triggers.all { it.triggerAtMillis == at(it.epochDay, 8, 30) })
    }

    @Test
    fun `按天请求码对同一作业与自然日稳定且与单次请求码区间隔离`() {
        val first = TimerReminderRules.requestCodeOf(7L, TODAY)
        val again = TimerReminderRules.requestCodeOf(7L, TODAY)
        val nextDay = TimerReminderRules.requestCodeOf(7L, TODAY + 1)
        val single = TimerReminderRules.requestCodeOf(7L)

        assertEquals("同一（作业，日）恒定映射：重设即覆盖、取消能命中", first, again)
        assertNotEquals("不同天不同请求码", first, nextDay)
        assertNotEquals("与单次提醒的请求码不同（区间隔离）", first, single)
        assertTrue(
            "按天请求码落在独立区间内",
            first >= TimerConstants.ALARM_REQUEST_CODE_DAY_BASE &&
                first < TimerConstants.ALARM_REQUEST_CODE_DAY_BASE + TimerConstants.ALARM_REQUEST_CODE_MODULUS,
        )
    }

    @Test
    fun `时长分钟折算向上取整且非正时长记零`() {
        assertEquals(0, TimerCalculations.minutesOf(0L))
        assertEquals(0, TimerCalculations.minutesOf(-5L))
        assertEquals("不足 1 分钟的非零时长记 1 分钟", 1, TimerCalculations.minutesOf(1L))
        assertEquals(1, TimerCalculations.minutesOf(TimerConstants.MILLIS_PER_MINUTE))
        assertEquals(2, TimerCalculations.minutesOf(TimerConstants.MILLIS_PER_MINUTE + 1L))
        assertEquals(30, TimerCalculations.minutesOf(30 * TimerConstants.MILLIS_PER_MINUTE))
    }

    // ---- 测试辅助 ----

    private fun stageItem(
        startEpochDay: Long,
        range: StageRange,
        time: LocalTime,
        status: HomeworkStatus = HomeworkStatus.PENDING,
    ) = timerTestHomework(
        id = 9L,
        status = status,
        type = HomeworkType.STAGE,
        stageRange = range,
        stageStartEpochDay = startEpochDay,
        stageDailyTime = time,
    )

    private fun at(epochDay: Long, hour: Int, minute: Int): Long =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, LocalTime.of(hour, minute), ZONE).toEpochMilli()

    private companion object {

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 固定基准日（与 homework/timer 测试的基准时钟 1_700_000_000_000L 同一业务日） */
        val TODAY: Long = HomeworkDailyDeadlineCodec.absoluteEpochDay(1_700_000_000_000L, ZONE)
    }
}
