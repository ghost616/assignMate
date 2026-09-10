package com.assignmate.app.timer.data

import com.assignmate.app.core.domain.speech.TextToSpeechPlayer

/**
 * TTS 引擎替身：记录播报文案与 stop 次数；可切换为「引擎不可用（抛异常）」
 * 以验证 [TtsTimerVoiceGuide] 的静默降级（异常不外泄、不阻塞界面）。
 */
class FakeTextToSpeechPlayer(
    /** 置 true 时 speak/stop 抛异常，模拟语音引擎缺失或系统不支持 */
    var failing: Boolean = false,
) : TextToSpeechPlayer {

    /** 实际交给引擎播报的文案（按调用顺序） */
    val spoken = mutableListOf<String>()

    /** stop 调用次数 */
    var stopCount: Int = 0
        private set

    /** release 调用次数 */
    var releaseCount: Int = 0
        private set

    override fun speak(text: String, onFinished: (() -> Unit)?) {
        if (failing) {
            throw IllegalStateException("TTS 引擎不可用")
        }
        spoken += text
        onFinished?.invoke()
    }

    override fun stop() {
        stopCount++
        if (failing) {
            throw IllegalStateException("TTS 引擎不可用")
        }
    }

    override fun release() {
        releaseCount++
    }
}
