package com.assignmate.app.core.domain.speech

import kotlinx.coroutines.flow.Flow

/**
 * 系统 ASR（语音转文字）抽象。
 *
 * 约定：调用方须先完成 RECORD_AUDIO 运行时权限申请（feature 层负责权限流程）；
 * 一次 [startListening] 对应一次识别会话，产出最终结果或错误后 Flow 自动结束；
 * [stopListening] 可提前结束当前会话并尽量产出最终结果。默认实现基于 Android SpeechRecognizer。
 */
interface SpeechToText {

    /**
     * 开启一次语音识别会话，返回识别事件流。
     *
     * 事件顺序示例：Partial... -> Final（正常结束）或 Error（失败结束）。
     * 采集方在页面销毁时取消收集即可释放底层资源。
     */
    fun startListening(): Flow<SpeechEvent>

    /** 提前结束当前识别会话（不释放底层资源，资源随流结束自动释放） */
    fun stopListening()

    /** 强制结束并释放底层 SpeechRecognizer（应用退出/页面销毁兜底调用） */
    fun release()

    /** 识别会话事件 */
    sealed interface SpeechEvent {

        /** 实时中间结果：随说话持续更新，用于界面回显 */
        data class Partial(val text: String) : SpeechEvent

        /** 一段话识别完成的最终文本 */
        data class Final(val text: String) : SpeechEvent

        /**
         * 识别错误。
         * @param code    RecognitionListener 错误码（如 ERROR_NETWORK）；非系统错误时为 null
         * @param message 面向用户的可读说明
         */
        data class Error(val code: Int?, val message: String) : SpeechEvent
    }
}
