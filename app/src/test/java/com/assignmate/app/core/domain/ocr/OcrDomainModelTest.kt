package com.assignmate.app.core.domain.ocr

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 领域模型（OcrResult/OcrImage/PendingOcrTask）纯逻辑单测。
 */
class OcrDomainModelTest {

    @Test
    fun `成功结果携带可编辑文本`() {
        val result = OcrResult.Success(text = "数学作业\n第 3 题")
        assertEquals("数学作业\n第 3 题", result.text)
    }

    @Test
    fun `网络失败可重试且文案提示联网`() {
        val failure: OcrResult.Failure = OcrResult.Failure.NetworkError()
        assertTrue(failure.isRetryable)
        assertTrue(failure.userMessage.contains("网络"))
    }

    @Test
    fun `未配置与解析类失败不可重试`() {
        assertFalse(OcrResult.Failure.NotConfigured().isRetryable)
        assertFalse(OcrResult.Failure.ParseError().isRetryable)
    }

    @Test
    fun `服务错误携带状态码且可重试`() {
        val failure = OcrResult.Failure.ServiceError(code = 401, userMessage = "鉴权失败")
        assertEquals(401, failure.code)
        assertTrue(failure.isRetryable)
    }

    @Test
    fun `图片 base64 编码可往返解码`() {
        val original = "hello ocr".toByteArray()
        val image = OcrImage(bytes = original, mimeType = OcrImage.MIME_TYPE_PNG, name = "hw.png")
        val decoded = Base64.getDecoder().decode(image.toBase64())
        assertArrayEquals(original, decoded)
        assertEquals(OcrImage.MIME_TYPE_PNG, image.mimeType)
    }

    @Test
    fun `待重试任务默认状态为 PENDING 且重试计数为零`() {
        val task = PendingOcrTask(localImagePath = "/tmp/hw.jpg", createdAtMillis = 123L)
        assertEquals(PendingOcrStatus.PENDING, task.status)
        assertEquals(0, task.retryCount)
        assertEquals(123L, task.createdAtMillis)
    }
}
