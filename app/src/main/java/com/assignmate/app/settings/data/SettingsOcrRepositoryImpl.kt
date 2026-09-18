package com.assignmate.app.settings.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrConfigStore
import com.assignmate.app.settings.domain.OcrLogSink
import com.assignmate.app.settings.domain.SettingsFeature
import com.assignmate.app.settings.domain.SettingsRoleGuard
import com.assignmate.app.settings.domain.redacted
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [SettingsOcrRepository] 默认实现。
 *
 * 读路径：会话变更时经 [SettingsRoleGuard] 判定——拒绝则发 null 并被 [filterNotNull] 过滤，
 * 即学生/未登录会话**完全读不到**配置（且不订阅 [OcrConfigStore]，密钥不进入内存）；
 * 会话切换（如家长登出后学生接管）经 `flatMapLatest` 重新判定，拒绝立即生效。
 *
 * 写路径：拒绝时直接返回 [OcrConfigSaveResult.Denied]，**不调用** [OcrConfigStore.save]。
 *
 * 日志：仅输出 [OcrConfig.redacted] 投影（不含密钥明文/密文）。
 */
class SettingsOcrRepositoryImpl @Inject constructor(
    private val configStore: OcrConfigStore,
    private val authRepository: AuthRepository,
    private val guard: SettingsRoleGuard,
    private val logSink: OcrLogSink,
) : SettingsOcrRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeConfig(): Flow<OcrConfig> =
        authRepository.observeSession().flatMapLatest { session ->
            if (guard.denialReasonOf(session, SettingsFeature.OCR_CONFIG) != null) {
                flowOf(null)
            } else {
                configStore.config.map { it as OcrConfig? }
            }
        }.filterNotNull()

    override suspend fun save(config: OcrConfig): OcrConfigSaveResult {
        val denial = guard.denialReason(SettingsFeature.OCR_CONFIG)
        if (denial != null) {
            return OcrConfigSaveResult.Denied(denial)
        }
        configStore.save(config)
        logSink.log(TAG, "OCR 配置已保存：${config.redacted()}")
        return OcrConfigSaveResult.Saved
    }

    private companion object {
        const val TAG = "SettingsOcrRepository"
    }
}