package com.assignmate.app.timer.ui

import com.assignmate.app.timer.data.TimerPermissionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限引导文案口径单测（评审第 3 项）：精确闹钟的说明必须与「未授权时降级为不精确闹钟」的
 * 真实风险一致——明确「可能晚一些（有时长达十几分钟）」，且仍说明不影响计时；
 * 通知权限说明同样不得夸大，并给出可手动开启的去处。
 */
class TimerPermissionGuidanceWordingTest {

    @Test
    fun `精确闹钟引导说明与降级风险一致`() {
        val message = TimerPermissionGuidance.EXACT_ALARM_HINT.message

        assertTrue("应说明可能晚到：$message", message.contains("可能晚"))
        assertTrue("应给出延迟量级（十几分钟）：$message", message.contains("十几分钟"))
        assertTrue("仍应说明不影响计时：$message", message.contains("计时"))
        assertTrue("应说明不受影响的范围：$message", message.contains("不受影响"))
    }

    @Test
    fun `精确闹钟引导指向系统设置且权限种类正确`() {
        val hint = TimerPermissionGuidance.EXACT_ALARM_HINT

        assertEquals(TimerPermissionKind.EXACT_ALARM, hint.permission)
        assertEquals("去设置", hint.actionLabel)
        assertTrue(hint.title.isNotBlank())
        assertFalse("说明不应空口承诺准点：${hint.message}", hint.message.contains("一定准点"))
    }

    @Test
    fun `通知引导说明不影响计时并给出开启方式`() {
        val hint = TimerPermissionGuidance.NOTIFICATIONS_HINT

        assertEquals(TimerPermissionKind.NOTIFICATIONS, hint.permission)
        assertEquals("允许通知", hint.actionLabel)
        assertTrue("应说明不影响计时：${hint.message}", hint.message.contains("不影响计时"))
        assertTrue("应给出可手动开启的去处：${hint.message}", hint.message.contains("系统设置"))
        assertFalse("不应出现「已开启」之类误导表述", hint.message.contains("已开启"))
    }
}
