package com.assignmate.app.core.domain.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OcrConfig 配置校验纯逻辑单测：覆盖启用态/字段缺失/非法协议等关键分支。
 */
class OcrConfigTest {

    private val fullConfig = OcrConfig(
        enabled = true,
        apiBaseUrl = "https://api.example.com/v1",
        modelName = "gpt-4o-mini",
        apiKey = "sk-test-123",
    )

    @Test
    fun `完整启用配置无校验问题且视为已配置`() {
        assertTrue(fullConfig.validationIssues().isEmpty())
        assertTrue(fullConfig.isConfigured)
    }

    @Test
    fun `未启用配置视为不可用且提示启用`() {
        val config = fullConfig.copy(enabled = false)
        assertFalse(config.isConfigured)
        assertTrue(config.validationIssues().contains(OcrConfigIssue.DISABLED))
    }

    @Test
    fun `缺少密钥与模型名称被拦截`() {
        val config = fullConfig.copy(apiKey = "", modelName = "   ")
        assertFalse(config.isConfigured)
        assertTrue(config.validationIssues().contains(OcrConfigIssue.API_KEY_BLANK))
        assertTrue(config.validationIssues().contains(OcrConfigIssue.MODEL_NAME_BLANK))
    }

    @Test
    fun `非 http 协议的地址被拦截`() {
        val config = fullConfig.copy(apiBaseUrl = "ftp://images.example.com")
        assertTrue(config.validationIssues().contains(OcrConfigIssue.API_BASE_URL_INVALID))
    }

    @Test
    fun `空地址被拦截为缺失而非协议非法`() {
        val config = fullConfig.copy(apiBaseUrl = "")
        val issues = config.validationIssues()
        assertTrue(issues.contains(OcrConfigIssue.API_BASE_URL_BLANK))
        assertFalse(issues.contains(OcrConfigIssue.API_BASE_URL_INVALID))
    }

    @Test
    fun `默认配置未启用且不可用`() {
        val defaultConfig = OcrConfig()
        assertFalse(defaultConfig.enabled)
        assertFalse(defaultConfig.isConfigured)
    }
}
