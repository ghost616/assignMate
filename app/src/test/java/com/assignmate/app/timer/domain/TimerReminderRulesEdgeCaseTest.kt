package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒与去重规则的补充边界单测（在 TimerReminderRulesTest 之外）：
 * 过期宽限恰好边界、提前量的正负与零、常量口径自洽、请求码在零/负数/取模回绕下的稳定性、
 * 去重间隔在纪元零时刻与零间隔下的行为。
 */
class TimerReminderRulesEdgeCaseTest {

    // ---- 触发时刻与过期判定 ----

    @Test
    fun `触发时刻恰好落在过期宽限边界时仍可设置提醒`() {
        val homework = timerTestHomework(
            id = 1L,
            status = HomeworkStatus.PENDING,
            startTimeMillis = BASE - GRACE,
        )

        // 规则为「不早于 现在 - 宽限」：恰好相等仍可设置（刚过点由系统立即触发）
        assertTrue(TimerReminderRules.canSchedule(homework, nowMillis = BASE))
    }

    @Test
    fun `提前量为零时触发时刻等于开始时间`() {
        assertEquals(BASE + 5 * MINUTE, TimerReminderRules.triggerAtMillisOf(BASE + 5 * MINUTE, leadMillis = 0L))
    }

    @Test
    fun `提前量把触发时刻提前到开始时间之前`() {
        assertEquals(
            BASE,
            TimerReminderRules.triggerAtMillisOf(BASE + 10 * MINUTE, leadMillis = 10 * MINUTE),
        )
    }

    @Test
    fun `负提前量等价于延后提醒`() {
        assertEquals(
            BASE + 12 * MINUTE,
            TimerReminderRules.triggerAtMillisOf(BASE + 10 * MINUTE, leadMillis = -2 * MINUTE),
        )
    }

    @Test
    fun `提醒与去重常量口径自洽`() {
        assertEquals(0, TimerConstants.REMINDER_LEAD_MINUTES)
        assertEquals(0L, TimerConstants.REMINDER_LEAD_MILLIS)
        assertEquals(5, TimerConstants.REMINDER_STALE_GRACE_MINUTES)
        assertEquals(5 * MINUTE, TimerConstants.REMINDER_STALE_GRACE_MILLIS)
        assertEquals(10, TimerConstants.OVERDUE_PROMPT_INTERVAL_MINUTES)
        assertEquals(10 * MINUTE, TimerConstants.OVERDUE_PROMPT_INTERVAL_MILLIS)
        assertTrue(TimerConstants.ALARM_REQUEST_CODE_BASE > 0)
        assertTrue(TimerConstants.ALARM_REQUEST_CODE_MODULUS > 1)
    }

    // ---- 请求码映射边界 ----

    @Test
    fun `作业 id 为零时请求码等于基数且稳定`() {
        assertEquals(
            TimerConstants.ALARM_REQUEST_CODE_BASE,
            TimerReminderRules.requestCodeOf(0L),
        )
    }

    @Test
    fun `负数作业 id 不崩溃且落在合法范围`() {
        val code = TimerReminderRules.requestCodeOf(-1L)

        assertTrue(code >= TimerConstants.ALARM_REQUEST_CODE_BASE)
        assertTrue(code < TimerConstants.ALARM_REQUEST_CODE_BASE + TimerConstants.ALARM_REQUEST_CODE_MODULUS)
        assertEquals(code, TimerReminderRules.requestCodeOf(-1L))
    }

    @Test
    fun `取模回绕时相隔取模基数的作业共用同一闹钟槽位`() {
        val modulus = TimerConstants.ALARM_REQUEST_CODE_MODULUS.toLong()

        // 记录当前实现口径：请求码为 id 取模映射，故 id 与 id + 模数 共用槽位（重设即覆盖、取消命中）
        assertEquals(TimerReminderRules.requestCodeOf(5L), TimerReminderRules.requestCodeOf(modulus + 5L))
        assertEquals(
            TimerConstants.ALARM_REQUEST_CODE_BASE,
            TimerReminderRules.requestCodeOf(modulus),
        )
    }

    @Test
    fun `超大作业 id 的请求码仍落在基数与取模范围内`() {
        val code = TimerReminderRules.requestCodeOf(Long.MAX_VALUE)

        assertTrue(code >= TimerConstants.ALARM_REQUEST_CODE_BASE)
        assertTrue(code < TimerConstants.ALARM_REQUEST_CODE_BASE + TimerConstants.ALARM_REQUEST_CODE_MODULUS)
    }

    // ---- 去重间隔边界 ----

    @Test
    fun `纪元零时刻的去重判定同样生效`() {
        assertTrue(TimerReminderRules.shouldPromptOverdue(0L, TimerConstants.OVERDUE_PROMPT_INTERVAL_MILLIS))
        assertFalse(TimerReminderRules.shouldPromptOverdue(0L, TimerConstants.OVERDUE_PROMPT_INTERVAL_MILLIS - 1L))
    }

    @Test
    fun `间隔为零时允许立刻再次提醒`() {
        assertTrue(TimerReminderRules.shouldPromptOverdue(BASE, BASE, intervalMillis = 0L))
    }

    @Test
    fun `时钟回拨在间隔为零时仍不重复提醒`() {
        assertFalse(TimerReminderRules.shouldPromptOverdue(BASE, BASE - 1L, intervalMillis = 0L))
    }

    private companion object {

        const val BASE = TimerTestEnv.FIXED_MILLIS
        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE
        const val GRACE = TimerConstants.REMINDER_STALE_GRACE_MILLIS
    }
}
