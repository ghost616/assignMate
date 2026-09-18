package com.assignmate.app.settings.domain

import com.assignmate.app.core.domain.ocr.OcrConfig

/**
 * 设置模块日志出口（可注入）：
 *
 * 为什么抽接口：需求红线「OCR API 密钥仅本地加密存储且不落日志」需要一条**可在 JVM 单测中断言**的路径——
 * 直接调用 android.util.Log 在纯 JVM 单测里是空操作（且 Robolectric 未引入），无法证明「密钥没被打印」。
 * 经本接口输出后，单测注入记录型替身即可对全部日志行做「不含密钥明文」断言。
 *
 * 约束（实现与调用方共同遵守）：
 * - 传入 message 前必须先经 [OcrConfig.redacted] / [OcrApiKeyMask.mask] 处理，禁止传原始 [OcrConfig.apiKey]；
 * - release 构建的 AndroidLogSink 仅在 debug 可调试时输出，正式版不输出任何内容。
 */
interface OcrLogSink {

    /** 输出一行设置相关日志（调用方保证 message 不含敏感值） */
    fun log(tag: String, message: String)
}

/**
 * [OcrConfig] 的日志安全投影：只保留「是否启用 / 地址 / 模型名」与密钥是否存在的布尔标记，
 * **不含密钥明文与密文**。设置模块任何需要记录配置的场景一律投影后再输出。
 */
fun OcrConfig.redacted(): String =
    "OcrConfig(enabled=$enabled, apiBaseUrl=$apiBaseUrl, modelName=$modelName, apiKeyConfigured=${apiKey.isNotBlank()})"

/**
 * 空实现：用于不需要日志的场景（默认不装配；settings.data.AndroidOcrLogSink 为生产实现）。
 */
object NoOpOcrLogSink : OcrLogSink {

    override fun log(tag: String, message: String) = Unit
}