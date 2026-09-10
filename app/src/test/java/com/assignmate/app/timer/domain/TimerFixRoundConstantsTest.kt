package com.assignmate.app.timer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 修复轮新增常量的口径自洽单测：走秒唤醒锁（评审第 5 项）与休息起点复用窗口（评审第 7 项）。
 *
 * 这些常量的真实行为（WakeLock 获取/释放、跨进程重建）需真机验证，但「换算是否正确、窗口与休息时长是否同源、
 * 兜底超时是否足够大」在离线即可用断言固化，避免两处魔法值漂移。
 */
class TimerFixRoundConstantsTest {

    @Test
    fun `唤醒锁标签非空且可定位到计时走秒`() {
        assertTrue(TimerConstants.WAKE_LOCK_TAG.isNotBlank())
        assertTrue(TimerConstants.WAKE_LOCK_TAG.contains("timer"))
    }

    @Test
    fun `唤醒锁兜底超时与小时常量同源且远大于合理作业时长`() {
        assertEquals(
            TimerConstants.WAKE_LOCK_TIMEOUT_HOURS * TimerConstants.MILLIS_PER_HOUR,
            TimerConstants.WAKE_LOCK_TIMEOUT_MILLIS,
        )
        assertTrue("兜底超时应为正", TimerConstants.WAKE_LOCK_TIMEOUT_HOURS > 0)
        assertTrue(
            "兜底超时应远大于任何合理作业时长（至少 1 小时）",
            TimerConstants.WAKE_LOCK_TIMEOUT_MILLIS >= TimerConstants.MILLIS_PER_HOUR,
        )
    }

    @Test
    fun `休息起点复用窗口与休息时长同源`() {
        assertEquals(TimerConstants.REST_DURATION_MILLIS, TimerConstants.REST_RESUME_WINDOW_MILLIS)
        assertEquals(
            TimerConstants.REST_DURATION_MINUTES * TimerConstants.MILLIS_PER_MINUTE,
            TimerConstants.REST_RESUME_WINDOW_MILLIS,
        )
    }

    @Test
    fun `走秒刷新间隔仍为一秒`() {
        assertEquals(TimerConstants.MILLIS_PER_SECOND, TimerConstants.TICK_INTERVAL_MILLIS)
        assertTrue(
            "复用窗口应覆盖整段休息（否则窗口内重新进入会提前判定结束）",
            TimerConstants.REST_RESUME_WINDOW_MILLIS >= TimerConstants.REST_DURATION_MILLIS,
        )
    }
}
