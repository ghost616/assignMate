package com.assignmate.app.timer.service

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 到点提醒广播的指令解析单测（[HomeworkAlarmReceiver] 中不依赖 Android 运行时的解析部分）：
 * 作业 id 缺失时回落到哨兵值 0（据此忽略无效指令）、作业内容缺失时回落为空串。
 *
 * 说明：`onReceive` 本身要发通知与震动（NotificationManager / Vibrator），属真机或仪器测试范围；
 * 本环境无设备，故仅用 Intent 替身覆盖指令解析契约（对应测试说明第 6 项「解析作业 id/内容（无效指令忽略）」
 * 中可离线验证的部分）。
 */
class HomeworkAlarmReceiverParseTest {

    @Test
    fun `解析作业 id 与内容`() {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.getLongExtra(any(), any()) } returns 42L
        every { intent.getStringExtra(any()) } returns "语文生字"

        assertEquals(42L, HomeworkAlarmReceiver.homeworkIdOf(intent))
        assertEquals("语文生字", HomeworkAlarmReceiver.contentOf(intent))
    }

    @Test
    fun `作业 id 缺失时回落为零以便忽略无效指令`() {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.getLongExtra(any(), any()) } returns 0L
        every { intent.getStringExtra(any()) } returns null

        assertEquals(0L, HomeworkAlarmReceiver.homeworkIdOf(intent))
        assertEquals("", HomeworkAlarmReceiver.contentOf(intent))
    }

    @Test
    fun `提醒广播 action 常量稳定`() {
        assertEquals(
            "com.assignmate.app.timer.action.HOMEWORK_REMINDER",
            HomeworkAlarmReceiver.ACTION_HOMEWORK_REMINDER,
        )
    }
}
