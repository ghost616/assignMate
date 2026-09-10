package com.assignmate.app.timer.service

import com.assignmate.app.timer.domain.TimerReminderRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒通知契约单测（[TimerNotifications] 中不依赖 Android 运行时的部分）：
 * 提醒通知 id 由作业请求码派生（同一作业复用同一通知、不同作业互不覆盖、不与走秒常驻通知冲突）、
 * 渠道 id 与震动波形约定。
 *
 * 说明：通知构造（NotificationCompat）与渠道创建需要 Android 运行时，属真机/仪器测试范围，
 * 本环境无设备，故只覆盖可纯函数验证的 id 派生与常量约定（对应测试说明第 6 项「通知 id 由作业请求码派生」）。
 */
class TimerNotificationsContractTest {

    @Test
    fun `提醒通知 id 由作业请求码派生且同一作业稳定复用`() {
        listOf(1L, 7L, 42L, 99_999L).forEach { homeworkId ->
            val id = TimerNotifications.reminderNotificationIdOf(homeworkId)

            assertEquals("同一作业两次派生应一致", id, TimerNotifications.reminderNotificationIdOf(homeworkId))
        }
        // 与请求码同源派生：两条作业的通知 id 之差应等于其请求码之差
        assertEquals(
            TimerReminderRules.requestCodeOf(20L) - TimerReminderRules.requestCodeOf(3L),
            TimerNotifications.reminderNotificationIdOf(20L) - TimerNotifications.reminderNotificationIdOf(3L),
        )
    }

    @Test
    fun `不同作业的提醒通知 id 互不相同`() {
        val ids = (1L..50L).map { TimerNotifications.reminderNotificationIdOf(it) }

        assertEquals("50 条作业的提醒通知 id 不应碰撞", 50, ids.toSet().size)
    }

    @Test
    fun `提醒通知 id 不与计时走秒常驻通知冲突`() {
        (1L..50L).forEach { homeworkId ->
            assertNotEquals(
                "提醒通知不应覆盖走秒通知",
                TimerNotifications.NOTIFICATION_ID,
                TimerNotifications.reminderNotificationIdOf(homeworkId),
            )
        }
    }

    @Test
    fun `走秒与提醒渠道 id 分离且非空`() {
        assertTrue(TimerNotifications.CHANNEL_ID.isNotBlank())
        assertTrue(TimerNotifications.REMINDER_CHANNEL_ID.isNotBlank())
        assertNotEquals(TimerNotifications.CHANNEL_ID, TimerNotifications.REMINDER_CHANNEL_ID)
        assertTrue("走秒通知 id 应为正数", TimerNotifications.NOTIFICATION_ID > 0)
    }

    @Test
    fun `提醒震动波形为非空且以零等待开始`() {
        val pattern = TimerNotifications.VIBRATION_PATTERN

        assertTrue("震动波形不应为空", pattern.isNotEmpty())
        assertEquals("首项应为等待时长 0ms（立即震动）", 0L, pattern.first())
        assertTrue("波形时长不应为负", pattern.all { it >= 0L })
        assertTrue("波形应包含实际震动时长", pattern.any { it > 0L })
    }
}
