package com.assignmate.app.core.domain.ocr

/**
 * OCR 厂商配置（领域模型）：对应某个兼容 chat/completions 协议的多模态大模型厂商。
 *
 * [apiKey] 为敏感凭据：存储层落盘前须经 ValueEncryptor 加密（当前为透传明文 + 预留 Keystore 方案），
 * 且任何日志/上报路径均不得输出该字段。
 *
 * @param apiBaseUrl 服务地址（含版本前缀，如 https://api.openai.com/v1），保存时统一去除末尾斜杠
 * @param modelName  多模态模型名
 * @param apiKey     鉴权密钥（Bearer Token）
 * @param enabled    是否启用云端识别；关闭或参数不完整时识别返回 NotConfigured 失败
 */
data class OcrConfig(
    val apiBaseUrl: String = "",
    val modelName: String = "",
    val apiKey: String = "",
    val enabled: Boolean = false,
) {

    /** 便捷判断：是否具备发起云端识别的全部必要条件 */
    val isConfigured: Boolean
        get() = validationIssues().isEmpty()

    /**
     * 校验配置完整性，返回全部问题项（空列表表示配置可用）。
     * 供设置页保存前校验与识别前防御性校验复用。
     */
    fun validationIssues(): List<OcrConfigIssue> {
        val issues = mutableListOf<OcrConfigIssue>()
        if (!enabled) {
            issues += OcrConfigIssue.DISABLED
        }
        if (apiBaseUrl.isBlank()) {
            issues += OcrConfigIssue.API_BASE_URL_BLANK
        } else if (!apiBaseUrl.startsWith("http://") && !apiBaseUrl.startsWith("https://")) {
            issues += OcrConfigIssue.API_BASE_URL_INVALID
        }
        if (modelName.isBlank()) {
            issues += OcrConfigIssue.MODEL_NAME_BLANK
        }
        if (apiKey.isBlank()) {
            issues += OcrConfigIssue.API_KEY_BLANK
        }
        return issues
    }
}

/** OCR 配置校验问题项（含面向用户的可读文案，供 UI 提示） */
enum class OcrConfigIssue(val message: String) {
    DISABLED("识别功能未启用"),
    API_BASE_URL_BLANK("请填写识别服务地址"),
    API_BASE_URL_INVALID("识别服务地址需以 http:// 或 https:// 开头"),
    MODEL_NAME_BLANK("请填写模型名称"),
    API_KEY_BLANK("请填写 API 密钥"),
}
