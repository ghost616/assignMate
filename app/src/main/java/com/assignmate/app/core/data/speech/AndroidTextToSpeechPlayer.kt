package com.assignmate.app.core.data.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.assignmate.app.core.domain.speech.TextToSpeechPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

/**
 * [TextToSpeechPlayer] 的 Android 系统 TTS 默认实现。
 *
 * ## 线程约束（重要）
 * - Android TTS 引擎的初始化回调（TextToSpeech 构造的 OnInitListener）与
 *   [UtteranceProgressListener]（onStart/onDone/onError）运行在引擎的内部 binder/回调线程，
 *   并非主线程；本实现将这些回调统一 post 回主线程后再访问内部状态。
 * - 因此内部共享状态（[tts]/[ready]/[pendingText]/[pendingFinished]/[activeFinished]/[utteranceId]）
 *   只会被主线程读写，天然无跨线程竞态（无需额外加锁）；引擎 API（speak/stop/shutdown）也都在主线程调用。
 * - 调用方约束：speak/stop/release 必须在主线程（UI 线程）调用（业务层经 ViewModel/LaunchedEffect 主线程协程即可）。
 *
 * ## 行为说明
 * 引擎初始化是异步的：初始化完成前收到的播报请求进入待播队列（仅保留最新一条，符合 QUEUE_FLUSH 语义）；
 * 中文语音包缺失时语言设置返回异常码但不阻塞播报（系统回退默认语言）。
 */
class AndroidTextToSpeechPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextToSpeechPlayer {

    private val appContext = context.applicationContext

    /** 统一回主线程执行引擎回调，保证共享状态仅在主线程访问 */
    private val mainHandler = Handler(Looper.getMainLooper())

    // 以下共享状态约定：仅在主线程读写（见类 KDoc 线程约束）
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var pendingFinished: (() -> Unit)? = null
    private var activeFinished: (() -> Unit)? = null
    private var utteranceId = 0L

    init {
        val engine = TextToSpeech(appContext) { status ->
            // 引擎初始化回调线程非主线程：切回主线程后再访问状态
            mainHandler.post { handleInit(status) }
        }
        tts = engine
    }

    /** 主线程执行（由 init 回调 post 而来） */
    private fun handleInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            return
        }
        val engine = tts ?: return
        ready = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                // UtteranceProgressListener 回调线程非主线程：状态访问与回调触发切回主线程
                mainHandler.post {
                    activeFinished?.invoke()
                    activeFinished = null
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    activeFinished = null
                }
            }
        })
        // 语言不可用（LANG_MISSING_DATA 等）时保留默认语言回退，不阻塞业务
        runCatching { engine.language = Locale.SIMPLIFIED_CHINESE }

        val text = pendingText
        val finished = pendingFinished
        pendingText = null
        pendingFinished = null
        if (!text.isNullOrBlank()) {
            speakNow(text, finished)
        }
    }

    /** 须在主线程调用（见类 KDoc 线程约束） */
    override fun speak(text: String, onFinished: (() -> Unit)?) {
        if (text.isBlank()) return
        if (!ready) {
            // 引擎尚未就绪：暂存最新一条，就绪后播报
            pendingText = text
            pendingFinished = onFinished
            return
        }
        speakNow(text, onFinished)
    }

    /** 主线程执行（由 [speak] 或主线程中的 [handleInit] 调用） */
    private fun speakNow(text: String, onFinished: (() -> Unit)?) {
        val engine = tts ?: return
        activeFinished = onFinished
        val id = "assignmate_tts_${++utteranceId}"
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            activeFinished = null
        }
    }

    /** 停止当前播报；须在主线程调用（见类 KDoc 线程约束） */
    override fun stop() {
        tts?.stop()
    }

    /** 释放引擎资源；须在主线程调用（见类 KDoc 线程约束） */
    override fun release() {
        pendingText = null
        pendingFinished = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
