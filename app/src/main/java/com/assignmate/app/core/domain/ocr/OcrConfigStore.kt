package com.assignmate.app.core.domain.ocr

import kotlinx.coroutines.flow.Flow

/**
 * OCR 厂商配置存取抽象：配置项（含敏感 [OcrConfig.apiKey]）持久化于 DataStore。
 *
 * 敏感字段在实现层经 ValueEncryptor 加密落盘（默认透传，后续替换 Keystore 方案），
 * 密钥不落日志；业务模块通过本接口读取最新配置，感知配置变更用 [config] Flow。
 */
interface OcrConfigStore {

    /** 当前配置流：首次发射为已持久化值或默认值，配置变更后自动发射新值 */
    val config: Flow<OcrConfig>

    /** 保存配置（覆盖式）；实现负责去空格、统一 baseUrl 末尾斜杠并对 apiKey 加密 */
    suspend fun save(config: OcrConfig)

    /** 清除全部 OCR 配置，恢复默认 */
    suspend fun clear()
}
