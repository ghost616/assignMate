package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 到点提醒与超时去重规则单测：
 * - 触发时刻计算（含提前量与未排定兜底）；
 * - 是否值得设置闹钟（仅待完成、且触发时刻未过期太久）；
 * - 超时鼓励去重判定（首播/间隔内静默/达到间隔再播/时钟回拨不重复）；
 * - 作业 id → 闹钟请求码的稳定映射（重设覆盖与取消命中的前提）。
 */
class TimerReminderRulesTest {

    // ---- 触发时刻 ----

    @Test
    fun `触发时刻等于开始时间减去提前量`() {
        assertEquals(
            BASE + 10 * MINUTE,
            TimerReminderRules.triggerAtMillisOf(BASE + 10 * MINUTE + 2 * MINUTE, leadMillis = 2 * MINUTE),
        )
    }

    @Test
    fun `默认提前量为零即到点提醒`() {
        assertEquals(0L, TimerConstants.REMINDER_LEAD_MILLIS)
        assertEquals(BASE, TimerReminderRules.triggerAtMillisOf(BASE))
    }

    @Test
    fun `未排定开始时间没有触发时刻`() {
        assertNull(TimerReminderRules.triggerAtMillisOf(null))
        assertNull(TimerReminderRules.triggerAtMillis(timerTestHomework(id = 1L)))
    }

    @Test
    fun `作业的触发时刻取自其开始时间`() {
        val homework = timerTestHomework(id = 1L, startTimeMillis = BASE + MINUTE)

        assertEquals(BASE + MINUTE, TimerReminderRules.triggerAtMillis(homework))
    }

    // ---- 是否设置闹钟 ----

    @Test
    fun `待完成且开始时间在未来的作业需要设置闹钟`() {
        val homework = timerTestHomework(
            id = 1L,
            status = HomeworkStatus.PENDING,
            startTimeMillis = BASE + 30 * MINUTE,
        )

        assertTrue(TimerReminderRules.canSchedule(homework, nowMillis = BASE))
    }

    @Test
    fun `刚过点但仍在宽限窗口内的作业仍设置闹钟由系统立即触发`() {
        val homework = timerTestHomework(
            id = 1L,
            status = HomeworkStatus.PENDING,
            startTimeMillis = BASE,
        )

        assertTrue(
            TimerReminderRules.canSchedule(homework, nowMillis = BASE + GRACE - 1L),
        )
    }

    @Test
    fun `触发时刻过期超过宽限的作业不再设置闹钟`() {
        val homework = timerTestHomework(
            id = 1L,
            status = HomeworkStatus.PENDING,
            startTimeMillis = BASE,
        )

        assertFalse(TimerReminderRules.canSchedule(homework, nowMillis = BASE + GRACE + 1L))
    }

    @Test
    fun `未排定时间的已记录作业不设置闹钟`() {
        val homework = timerTestHomework(id = 1L, status = HomeworkStatus.RECORDED)

        assertFalse(TimerReminderRules.canSchedule(homework, nowMillis = BASE))
    }

    @Test
    fun `进行中与已完成的作业不再设置闹钟`() {
        listOf(HomeworkStatus.IN_PROGRESS, HomeworkStatus.COMPLETED).forEach { status ->
            val homework = timerTestHomework(
                id = 1L,
                status = status,
                startTimeMillis = BASE + 30 * MINUTE,
            )

            assertFalse("$status 不应再设置到点提醒", TimerReminderRules.canSchedule(homework, BASE))
        }
    }

    // ---- 超时鼓励去重 ----

    @Test
    fun `从未提醒过时允许给出超时鼓励`() {
        assertTrue(TimerReminderRules.shouldPromptOverdue(lastPromptedAtMillis = null, nowMillis = BASE))
    }

    @Test
    fun `距上次提醒未达间隔时静默不重复骚扰`() {
        assertFalse(
            TimerReminderRules.shouldPromptOverdue(
                lastPromptedAtMillis = BASE,
                nowMillis = BASE + TimerConstants.OVERDUE_PROMPT_INTERVAL_MILLIS - 1L,
            ),
        )
    }

    @Test
    fun `达到或超过间隔后允许再次给出鼓励`() {
        assertTrue(
            TimerReminderRules.shouldPromptOverdue(
                lastPromptedAtMillis = BASE,
                nowMillis = BASE + TimerConstants.OVERDUE_PROMPT_INTERVAL_MILLIS,
            ),
        )
        assertTrue(TimerReminderRules.shouldPromptOverdue(BASE, BASE + 10 * HOUR))
    }

    @Test
    fun `时钟回拨时按刚提醒过处理不重复提醒`() {
        assertFalse(TimerReminderRules.shouldPromptOverdue(lastPromptedAtMillis = BASE, nowMillis = BASE - 1L))
    }

    @Test
    fun `间隔可由调用方自定义`() {
        assertTrue(TimerReminderRules.shouldPromptOverdue(BASE, BASE + MINUTE, intervalMillis = MINUTE))
        assertFalse(TimerReminderRules.shouldPromptOverdue(BASE, BASE + MINUTE - 1L, intervalMillis = MINUTE))
    }

    // ---- 请求码映射 ----

    @Test
    fun `同一作业的请求码稳定以便重设覆盖与取消命中`() {
        assertEquals(TimerReminderRules.requestCodeOf(42L), TimerReminderRules.requestCodeOf(42L))
    }

    @Test
    fun `不同作业的请求码不同`() {
        assertNotEquals(TimerReminderRules.requestCodeOf(1L), TimerReminderRules.requestCodeOf(2L))
    }

    @Test
    fun `请求码落在基数与取模范围内`() {
        listOf(1L, 999L, TimerConstants.ALARM_REQUEST_CODE_MODULUS.toLong(), Long.MAX_VALUE).forEach { id ->
            val code = TimerReminderRules.requestCodeOf(id)
            assertTrue("请求码不应小于基数：$code", code >= TimerConstants.ALARM_REQUEST_CODE_BASE)
            assertTrue(
                "请求码应在基数 + 取模范围内：$code",
                code < TimerConstants.ALARM_REQUEST_CODE_BASE + TimerConstants.ALARM_REQUEST_CODE_MODULUS,
            )
        }
    }

    private companion object {

        const val BASE = TimerTestEnv.FIXED_MILLIS
        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE
        const val HOUR = TimerConstants.MILLIS_PER_HOUR
        const val GRACE = TimerConstants.REMINDER_STALE_GRACE_MILLIS
    }
}
