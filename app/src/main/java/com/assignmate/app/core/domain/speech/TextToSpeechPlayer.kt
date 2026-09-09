package com.assignmate.app.core.domain.speech

/**
 * 系统 TTS（文字转语音播报）抽象。
 *
 * 供计时结束播报、提醒文案朗读等场景使用；
 * 默认实现基于 Android TextToSpeech，中文语音包缺失时系统回退默认语言（不阻塞业务）。
 */
interface TextToSpeechPlayer {

    /**
     * 播报一段文案；上一条未播完内容会被打断（QUEUE_FLUSH）。
     *
     * @param onFinished 本次播报自然播完后的回调（被新播报打断/引擎不可用时不回调）
     */
    fun speak(text: String, onFinished: (() -> Unit)? = null)

    /** 停止当前播报 */
    fun stop()

    /** 释放引擎资源（页面销毁/应用退出时调用） */
    fun release()
}
