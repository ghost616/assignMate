package com.assignmate.app.timer.data

import com.assignmate.app.core.domain.speech.TextToSpeechPlayer
import com.assignmate.app.timer.domain.TimerSpeechTexts
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音引导门面：把装配好的文案交给系统 TTS 播报，并保证**任何情况下都不阻塞界面**。
 *
 * 与 tts 的关系（职责分离）：
 * - 文案装配在纯函数 [TimerSpeechTexts]；
 * - 播报开关（家长/孩子是否要语音）在 [TimerVoiceSettings]；
 * - 本接口只负责「清洗文案 → 交给 core 的 [TextToSpeechPlayer] → 静默降级」，
 *   因此可被 ViewModel 直接注入并替换为测试替身。
 *
 * 线程约束：core 的 [TextToSpeechPlayer] 要求在主线程调用；调用方（ViewModel 的 viewModelScope
 * 默认主线程）满足该约束，实现内不再切换线程。
 *
 * 资源约定：TTS 引擎是应用级共享单例（core 的 SpeechModule 绑定），本门面**不**在页面销毁时
 * 释放引擎（release），避免影响其它页面/后续播报。
 *
 * [stop] 的使用约定（评审补充）：页面离开时由 ViewModel 的 `onLeavingPage()` 调用一次，
 * 打断未播完的播报（避免用户已离开页面还在念上一屏的内容）；
 * **不打断**的例外是「后台继续走秒」——计时本身与播报生命周期无关，页面离开只停语音、不停计时。
 */
interface TimerVoiceGuide {

    /** 播报一段文案（空白文案自动跳过；TTS 不可用时静默失败） */
    fun speak(text: String)

    /** 停止当前播报（页面离开/导航切换时由 ViewModel 调用一次，打断未播完内容） */
    fun stop()
}

/** 基于 core [TextToSpeechPlayer] 的默认实现 */
@Singleton
class TtsTimerVoiceGuide @Inject constructor(
    private val player: TextToSpeechPlayer,
) : TimerVoiceGuide {

    override fun speak(text: String) {
        val sanitized = TimerSpeechTexts.sanitize(text)
        if (sanitized.isEmpty()) {
            return
        }
        // 静默降级：语音包缺失/引擎异常/系统不支持 TTS 时一律吞掉异常，绝不打断计时流程
        runCatching { player.speak(sanitized) }
    }

    override fun stop() {
        runCatching { player.stop() }
    }
}
