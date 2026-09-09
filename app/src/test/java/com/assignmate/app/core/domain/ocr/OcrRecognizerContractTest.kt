package com.assignmate.app.core.domain.ocr

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OcrRecognizer 接口契约单测（MockK + coroutines-test 骨架）：
 * 验证业务侧按接口调用时结果原样返回、调用次数正确；实现细节由真实云端测试覆盖。
 */
class OcrRecognizerContractTest {

    @Test
    fun `recognize 的返回结果原样透传`() = runTest {
        val recognizer: OcrRecognizer = mockk(relaxed = true)
        coEvery { recognizer.recognize(any(), any()) } returns OcrResult.Success(text = "识别文字")

        val image = OcrImage(bytes = "fake-image".toByteArray(), name = "page1.jpg")
        val config = OcrConfig(
            enabled = true,
            apiBaseUrl = "https://api.example.com/v1",
            modelName = "gpt-4o-mini",
            apiKey = "sk-test",
        )
        val result = recognizer.recognize(image, config)

        assertEquals("识别文字", (result as OcrResult.Success).text)
        coVerify(exactly = 1) { recognizer.recognize(image, config) }
    }
}
