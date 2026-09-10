package com.assignmate.app.timer.ui

import com.assignmate.app.timer.data.TimerPermissionKind
import com.assignmate.app.timer.data.TimerPermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限引导文案单测（纯函数）：按权限状态推导引导列表（已授权不出现、未知不引导、顺序稳定），
 * 并校验文案明确传达「不影响计时」且按钮文案非空。
 */
class TimerPermissionGuidanceTest {

    @Test
    fun `两项权限都已授权时没有任何引导`() {
        val hints = TimerPermissionGuidance.hintsOf(
            TimerPermissionStatus(notificationsGranted = true, exactAlarmGranted = true),
        )

        assertTrue(hints.isEmpty())
    }

    @Test
    fun `仅通知未授权时给出通知引导`() {
        val hints = TimerPermissionGuidance.hintsOf(
            TimerPermissionStatus(notificationsGranted = false, exactAlarmGranted = true),
        )

        assertEquals(1, hints.size)
        assertEquals(TimerPermissionKind.NOTIFICATIONS, hints.single().permission)
    }

    @Test
    fun `仅精确闹钟未授权时给出精确闹钟引导`() {
        val hints = TimerPermissionGuidance.hintsOf(
            TimerPermissionStatus(notificationsGranted = true, exactAlarmGranted = false),
        )

        assertEquals(1, hints.size)
        assertEquals(TimerPermissionKind.EXACT_ALARM, hints.single().permission)
    }

    @Test
    fun `两项都未授权时先通知后精确闹钟`() {
        val hints = TimerPermissionGuidance.hintsOf(
            TimerPermissionStatus(notificationsGranted = false, exactAlarmGranted = false),
        )

        assertEquals(
            listOf(TimerPermissionKind.NOTIFICATIONS, TimerPermissionKind.EXACT_ALARM),
            hints.map { it.permission },
        )
    }

    @Test
    fun `权限状态未知时不做任何引导`() {
        assertTrue(TimerPermissionGuidance.hintsOf(null).isEmpty())
    }

    @Test
    fun `引导文案非空且明确不影响计时`() {
        listOf(TimerPermissionGuidance.NOTIFICATIONS_HINT, TimerPermissionGuidance.EXACT_ALARM_HINT)
            .forEach { hint ->
                assertTrue("标题不应为空", hint.title.isNotBlank())
                assertTrue("说明不应为空", hint.message.isNotBlank())
                assertTrue("按钮文案不应为空", hint.actionLabel.isNotBlank())
                assertTrue("说明应提到计时不受影响：${hint.message}", hint.message.contains("计时"))
            }
    }

    @Test
    fun `状态快照可判断是否全部授权`() {
        assertTrue(
            TimerPermissionStatus(notificationsGranted = true, exactAlarmGranted = true).allGranted,
        )
        assertFalse(
            TimerPermissionStatus(notificationsGranted = false, exactAlarmGranted = true).allGranted,
        )
        assertFalse(
            TimerPermissionStatus(notificationsGranted = true, exactAlarmGranted = false).allGranted,
        )
    }
}
