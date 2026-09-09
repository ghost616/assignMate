package com.assignmate.app.core.di

import com.assignmate.app.core.data.speech.AndroidSpeechToText
import com.assignmate.app.core.data.speech.AndroidTextToSpeechPlayer
import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.core.domain.speech.TextToSpeechPlayer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 语音能力 Hilt 绑定模块：ASR/TTS 抽象 -> 系统实现（SpeechRecognizer / TextToSpeech）。
 * 业务模块（计时播报、语音录入）直接注入接口使用。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SpeechModule {

    @Binds
    @Singleton
    abstract fun bindSpeechToText(impl: AndroidSpeechToText): SpeechToText

    @Binds
    @Singleton
    abstract fun bindTextToSpeechPlayer(impl: AndroidTextToSpeechPlayer): TextToSpeechPlayer
}
