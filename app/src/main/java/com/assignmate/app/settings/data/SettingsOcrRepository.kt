package com.assignmate.app.settings.data

import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.settings.domain.SettingsDenialReason
import kotlinx.coroutines.flow.Flow

/**
 * OCR 厂商配置读写用例（settings 模块对外的唯一配置入口）。
 *
 * 存储来源唯一：读/写全部经 core 的 [com.assignmate.app.core.domain.ocr.OcrConfigStore]，
 * 本模块**不自建任何存储**（无 DataStore 键、无数据库表、无本地文件）。
 *
 * 分权（红线，双保险之一：仓库/用例层）：
 * - 学生会话调用 [save] 一律返回 [OcrConfigSaveResult.Denied] 且**不触达** OcrConfigStore；
 * - 学生会话调用 [observeConfig] 得到的流**不发射任何配置**（密钥与厂商参数不进入学生端内存/界面）；
 * - role 为 null（未登录/已登出）按未登录处理，同样拒绝读写。
 *
 * 安全：实现不得以任何形式把 [OcrConfig.apiKey] 写入日志（见 settings.domain.OcrLogSink 约定）。
 */
interface SettingsOcrRepository {

    /**
     * 观察当前 OCR 配置（配置变更后自动发射新值）。
     *
     * 无权限（学生会话/未登录）时发射空列表并结束，调用方按空态提示，不会读到任何配置内容。
     */
    fun observeConfig(): Flow<OcrConfig>

    /**
     * 保存 OCR 配置（覆盖式；地址去空格与末尾斜杠、密钥加密由 core 实现负责）。
     *
     * @return [OcrConfigSaveResult.Saved] 表示已落盘；[OcrConfigSaveResult.Denied] 携带拒绝原因
     */
    suspend fun save(config: OcrConfig): OcrConfigSaveResult
}

/** OCR 配置保存结果 */
sealed interface OcrConfigSaveResult {

    /** 保存成功（已写入 core 的 OcrConfigStore） */
    data object Saved : OcrConfigSaveResult

    /** 分权拒绝：学生会话/未登录会话不允许读写 OCR 配置 */
    data class Denied(val reason: SettingsDenialReason) : OcrConfigSaveResult
}