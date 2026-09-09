package com.assignmate.app.core.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.core.domain.speech.SpeechToText.SpeechEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * [SpeechToText] 的 Android 系统 ASR（SpeechRecognizer）默认实现。
 *
 * 说明：
 * - 需要 RECORD_AUDIO 运行时权限，由 feature 层申请后调用；权限缺失时引擎回以 Error 事件而非崩溃；
 * - 识别回调均发生在主线程，流内 trySend 天然线程安全，flowOn(Main.immediate) 保证会话在主线程创建/销毁；
 * - [release] 仅停止当前会话，底层 SpeechRecognizer 随收集协程取消（awaitClose）统一 destroy，避免双销毁。
 */
class AndroidSpeechToText @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechToText {

    private val appContext = context.applicationContext

    @Volatile
    private var activeRecognizer: SpeechRecognizer? = null

    override fun startListening(): Flow<SpeechEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            trySend(SpeechEvent.Error(null, "当前设备不支持语音识别"))
            close()
            return@callbackFlow
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        activeRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                trySend(SpeechEvent.Error(error, describeError(error)))
                close()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    trySend(SpeechEvent.Final(text))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    trySend(SpeechEvent.Partial(text))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            // 权限未授予/服务异常时 startListening 可能直接抛异常：转为事件而非崩溃
            trySend(SpeechEvent.Error(null, e.message ?: "无法启动语音识别"))
            close()
        }
        awaitClose {
            recognizer.destroy()
            if (activeRecognizer === recognizer) {
                activeRecognizer = null
            }
        }
    }.flowOn(Dispatchers.Main.immediate)

    override fun stopListening() {
        val recognizer = activeRecognizer ?: return
        // SpeechRecognizer 要求主线程调用
        Handler(Looper.getMainLooper()).post {
            runCatching { recognizer.stopListening() }
        }
    }

    override fun release() {
        val recognizer = activeRecognizer ?: return
        Handler(Looper.getMainLooper()).post {
            runCatching { recognizer.destroy() }
            if (activeRecognizer === recognizer) {
                activeRecognizer = null
            }
        }
    }

    private fun describeError(errorCode: Int): String = when (errorCode) {
        SpeechRecognizer.ERROR_NETWORK -> "网络不可用，请联网后重试"
        SpeechRecognizer.ERROR_AUDIO -> "未捕获到声音，请靠近麦克风重试"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限，请在系统设置中开启"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到内容，请重试"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务繁忙，请稍后重试"
        else -> "语音识别失败（错误码 $errorCode）"
    }
}
