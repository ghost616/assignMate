package com.assignmate.app.homework.ui

import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音与待重试任务文案转换单测：验证 Partial/Final/Error 事件与任务状态的界面文案口径，
 * 页面据此展示，避免各处硬编码导致提示不一致。
 */
class HomeworkOcrSpeechTextsTest {

    @Test
    fun `中间结果用于实时回显`() {
        assertEquals("写语文作业", SpeechToText.SpeechEvent.Partial("写语文作业").toPartialHint())
        // 空白中间结果不覆盖已有回显
        assertNull(SpeechToText.SpeechEvent.Partial("   ").toPartialHint())
    }

    @Test
    fun `最终结果与错误不作为中间回显`() {
        assertNull(SpeechToText.SpeechEvent.Final("完成").toPartialHint())
        assertNull(SpeechToText.SpeechEvent.Error(null, "出错").toPartialHint())
    }

    @Test
    fun `语音错误码映射为可读说明`() {
        assertEquals(
            "语音识别需要联网，请检查网络后重试",
            SpeechToText.SpeechEvent.Error(SPEECH_ERROR_NETWORK, "网络错误").describe(),
        )
        assertEquals(
            "没有听清，请再说一遍",
            SpeechToText.SpeechEvent.Error(SPEECH_ERROR_NO_MATCH, "无匹配").describe(),
        )
        assertEquals(
            "没有检测到说话声，请重试",
            SpeechToText.SpeechEvent.Error(SPEECH_ERROR_SPEECH_TIMEOUT, "超时").describe(),
        )
        assertEquals(
            "缺少录音权限，请在系统设置中开启",
            SpeechToText.SpeechEvent.Error(SPEECH_ERROR_INSUFFICIENT_PERMISSIONS, "权限").describe(),
        )
        assertEquals(
            "识别服务忙，请稍后再试",
            SpeechToText.SpeechEvent.Error(SPEECH_ERROR_RECOGNIZER_BUSY, "忙").describe(),
        )
    }

    @Test
    fun `未知错误码直接使用系统消息`() {
        assertEquals("识别服务异常", SpeechToText.SpeechEvent.Error(99, "识别服务异常").describe())
        assertEquals("无错误码消息", SpeechToText.SpeechEvent.Error(null, "无错误码消息").describe())
    }

    @Test
    fun `待重试任务状态文案正确`() {
        assertEquals("待识别", PendingOcrStatus.PENDING.label())
        assertEquals("识别中", PendingOcrStatus.PROCESSING.label())
        assertEquals("已识别", PendingOcrStatus.SUCCEEDED.label())
        assertEquals("识别失败", PendingOcrStatus.FAILED.label())
    }

    @Test
    fun `待重试任务描述包含时间与状态`() {
        val task = PendingOcrTask(
            id = 1L,
            localImagePath = "/tmp/pending.jpg",
            createdAtMillis = 1_700_000_000_000L,
            status = PendingOcrStatus.PENDING,
        )
        val described = task.describe()
        assertTrue(described.contains("待识别"))
        assertTrue(described.length > "待识别".length)
    }
}