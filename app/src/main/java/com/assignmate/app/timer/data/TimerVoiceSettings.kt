package com.assignmate.app.timer.data

import com.assignmate.app.core.domain.prefs.KeyValueStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 语音引导开关（播报开关）：家长/孩子可选择是否让计时过程语音播报。
 *
 * 落盘经 core 的 [KeyValueStore]（DataStore），默认开启；关闭后所有播报（休息结束下一项、
 * 全部完成表扬语、超时鼓励语）静默跳过，不影响计时与界面。
 */
interface TimerVoiceSettings {

    /**
     * 观察开关变化（默认开启）。
     *
     * **预留能力**：当前生产代码未订阅该 Flow（各页面在 `start()` 时用 [isEnabled] 读一次快照，
     * 开关变更后本页面即时生效，其它页面在下一次进入时生效）；保留它以便后续「设置页 / 跨页面实时生效」
     * 接入而无需改接口，**不代表当前已实现跨页面实时联动**（详见 current_spec「预留能力」章节）。
     */
    fun observeEnabled(): Flow<Boolean>

    /** 读取当前开关（默认开启） */
    suspend fun isEnabled(): Boolean

    /** 设置开关 */
    suspend fun setEnabled(enabled: Boolean)
}

/** [TimerVoiceSettings] 的默认实现（基于 core KeyValueStore） */
@Singleton
class DataStoreTimerVoiceSettings @Inject constructor(
    private val keyValueStore: KeyValueStore,
) : TimerVoiceSettings {

    override fun observeEnabled(): Flow<Boolean> =
        keyValueStore.observeBoolean(KEY_VOICE_ENABLED).map { it ?: DEFAULT_ENABLED }

    override suspend fun isEnabled(): Boolean =
        keyValueStore.getBoolean(KEY_VOICE_ENABLED, DEFAULT_ENABLED)

    override suspend fun setEnabled(enabled: Boolean) {
        keyValueStore.putBoolean(KEY_VOICE_ENABLED, enabled)
    }

    companion object {

        /** 开关持久化键 */
        const val KEY_VOICE_ENABLED = "timer_voice_enabled"

        /** 默认开启语音引导 */
        const val DEFAULT_ENABLED = true
    }
}
