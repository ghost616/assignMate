package com.assignmate.app.settings

import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrConfigIssue
import com.assignmate.app.settings.domain.OcrConfigField
import com.assignmate.app.settings.domain.SettingsOcrValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 配置表单校验单测：**4 类校验问题分支全覆盖**（未启用 / 地址缺失 / 地址协议非法 / 模型名缺失 / 密钥缺失），
 * 并断言文案直取 core 的 [OcrConfigIssue.message]（口径唯一，不出现两套提示语）。
 */
class SettingsOcrValidatorTest {

    private val validConfig = OcrConfig(
        apiBaseUrl = "https://api.example.com/v1",
        modelName = "gpt-4o-mini",
        apiKey = "sk-test-1234",
        enabled = true,
    )

    @Test
    fun `完整配置无问题且可保存`() {
        val errors = SettingsOcrValidator.fieldErrors(validConfig)

        assertTrue(errors.isEmpty())
        assertFalse(SettingsOcrValidator.hasIssues(validConfig))
        assertTrue(SettingsOcrValidator.canSave(validConfig))
    }

    @Test
    fun `未启用分支映射到启用字段并给出 core 文案`() {
        val config = validConfig.copy(enabled = false)

        assertEquals(
            OcrConfigIssue.DISABLED.message,
            SettingsOcrValidator.errorOf(config, OcrConfigField.ENABLED),
        )
        assertEquals(SettingsOcrValidator.DISABLED_HINT, SettingsOcrValidator.errorOf(config, OcrConfigField.ENABLED))
        assertFalse(SettingsOcrValidator.canSave(config))
    }

    @Test
    fun `地址缺失分支映射到地址字段`() {
        val config = validConfig.copy(apiBaseUrl = "   ")

        assertEquals(
            OcrConfigIssue.API_BASE_URL_BLANK.message,
            SettingsOcrValidator.errorOf(config, OcrConfigField.API_BASE_URL),
        )
        // 空地址只报「缺失」，不报「协议非法」
        assertEquals(1, SettingsOcrValidator.fieldErrors(config).size)
    }

    @Test
    fun `地址协议非法分支映射到地址字段`() {
        val config = validConfig.copy(apiBaseUrl = "ftp://api.example.com")

        assertEquals(
            OcrConfigIssue.API_BASE_URL_INVALID.message,
            SettingsOcrValidator.errorOf(config, OcrConfigField.API_BASE_URL),
        )
        assertEquals(1, SettingsOcrValidator.fieldErrors(config).size)
    }

    @Test
    fun `模型名称缺失分支映射到模型字段`() {
        val config = validConfig.copy(modelName = "")

        assertEquals(
            OcrConfigIssue.MODEL_NAME_BLANK.message,
            SettingsOcrValidator.errorOf(config, OcrConfigField.MODEL_NAME),
        )
        assertNull(SettingsOcrValidator.errorOf(config, OcrConfigField.API_KEY))
    }

    @Test
    fun `密钥缺失分支映射到密钥字段`() {
        val config = validConfig.copy(apiKey = "")

        assertEquals(
            OcrConfigIssue.API_KEY_BLANK.message,
            SettingsOcrValidator.errorOf(config, OcrConfigField.API_KEY),
        )
    }

    @Test
    fun `多项问题时逐字段就地渲染且数量与 core 一致`() {
        val config = OcrConfig()

        val errors = SettingsOcrValidator.fieldErrors(config)

        assertEquals(4, errors.size)
        assertEquals(
            OcrConfig().validationIssues().map { it.message }.toSet(),
            errors.values.toSet(),
        )
        assertFalse(SettingsOcrValidator.canSave(config))
    }

    @Test
    fun `问题文案全部来自 core 枚举不另造文案`() {
        val allMessages = OcrConfigIssue.entries.map { it.message }.toSet()

        SettingsOcrValidator.fieldErrors(OcrConfig()).values.forEach { message ->
            assertTrue("文案 [$message] 应来源于 core 的 OcrConfigIssue", message in allMessages)
        }
    }
}