package com.assignmate.app.settings.domain

import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrConfigIssue

/**
 * OCR 配置表单字段标识（与页面输入框一一对应，供校验问题就地渲染）。
 */
enum class OcrConfigField {
    API_BASE_URL,
    MODEL_NAME,
    API_KEY,
    ENABLED,
}

/**
 * OCR 配置表单校验（纯逻辑，复用 core 的 [OcrConfig.validationIssues]，不自建校验规则）。
 *
 * 为什么再包一层：core 的校验返回「问题枚举列表」，页面需要「哪个输入框有问题 + 就地展示哪句文案」。
 * 本对象只做这一层映射，规则本身仍唯一来源于 [OcrConfig.validationIssues]，
 * 避免设置页与识别链路出现两套口径（例如识别时判定「地址非法」而设置页却允许保存）。
 */
object SettingsOcrValidator {

    /** 关闭总开关时的提示文案（页面在开关下方就地展示） */
    val DISABLED_HINT: String = OcrConfigIssue.DISABLED.message

    /**
     * 校验配置：返回「字段 -> 问题文案」映射（空映射表示可保存）。
     *
     * 说明：同一字段最多映射一条问题（core 校验对同一字段不会重复报错）；
     * [OcrConfigIssue.DISABLED] 映射到 [OcrConfigField.ENABLED]，其余按字段对应。
     */
    fun fieldErrors(config: OcrConfig): Map<OcrConfigField, String> =
        config.validationIssues().associate { issue ->
            issue.toField() to issue.message
        }

    /** 是否存在校验问题（保存按钮的拦截依据） */
    fun hasIssues(config: OcrConfig): Boolean = config.validationIssues().isNotEmpty()

    /** 是否可保存：无校验问题即可保存 */
    fun canSave(config: OcrConfig): Boolean = !hasIssues(config)

    /** 取某字段的问题文案（无问题返回 null） */
    fun errorOf(config: OcrConfig, field: OcrConfigField): String? = fieldErrors(config)[field]

    private fun OcrConfigIssue.toField(): OcrConfigField = when (this) {
        OcrConfigIssue.DISABLED -> OcrConfigField.ENABLED
        OcrConfigIssue.API_BASE_URL_BLANK,
        OcrConfigIssue.API_BASE_URL_INVALID,
        -> OcrConfigField.API_BASE_URL

        OcrConfigIssue.MODEL_NAME_BLANK -> OcrConfigField.MODEL_NAME
        OcrConfigIssue.API_KEY_BLANK -> OcrConfigField.API_KEY
    }
}