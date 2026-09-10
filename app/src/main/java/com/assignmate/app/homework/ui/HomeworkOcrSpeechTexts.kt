package com.assignmate.app.homework.ui

import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.core.domain.util.TimeFormatters

/**
 * OCR / 语音相关的界面文案映射与展示工具（纯函数，集中一处便于单测与复用）。
 *
 * 约定：core 的 [SpeechToText.SpeechEvent] 与待重试任务状态在此转换为可读文案，
 * 页面不硬编码错误说明。
 */

/** 语音事件 → 界面文案/状态提示（Partial 为空串时提示「正在聆听」） */
internal fun SpeechToText.SpeechEvent.toPartialHint(): String? = when (this) {
    is SpeechToText.SpeechEvent.Partial -> text.ifBlank { null }
    is SpeechToText.SpeechEvent.Final -> null
    // 错误不写入中间回显：由 describe() 单独走提示通道，避免错误文案混入内容框
    is SpeechToText.SpeechEvent.Error -> null
}

/** 语音错误码 → 可读说明（系统错误码仅在日志/定位时使用，界面统一用 message） */
internal fun SpeechToText.SpeechEvent.Error.describe(): String = when (code) {
    null -> message
    SPEECH_ERROR_NETWORK -> "语音识别需要联网，请检查网络后重试"
    SPEECH_ERROR_NO_MATCH -> "没有听清，请再说一遍"
    SPEECH_ERROR_SPEECH_TIMEOUT -> "没有检测到说话声，请重试"
    SPEECH_ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限，请在系统设置中开启"
    SPEECH_ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍后再试"
    SPEECH_ERROR_AUDIO -> "录音出错，请重试"
    SPEECH_ERROR_CLIENT -> "识别失败，请重试"
    else -> message
}

/** 语音识别系统错误码（与 Android SpeechRecognizer 常量一致，避免在页面引入 Android 依赖） */
internal const val SPEECH_ERROR_NETWORK = 2
internal const val SPEECH_ERROR_AUDIO = 3
internal const val SPEECH_ERROR_CLIENT = 5
internal const val SPEECH_ERROR_SPEECH_TIMEOUT = 6
internal const val SPEECH_ERROR_NO_MATCH = 7
internal const val SPEECH_ERROR_RECOGNIZER_BUSY = 8
internal const val SPEECH_ERROR_INSUFFICIENT_PERMISSIONS = 9

/** 待重试任务状态 → 可读文案 */
internal fun PendingOcrStatus.label(): String = when (this) {
    PendingOcrStatus.PENDING -> "待识别"
    PendingOcrStatus.PROCESSING -> "识别中"
    PendingOcrStatus.SUCCEEDED -> "已识别"
    PendingOcrStatus.FAILED -> "识别失败"
}

/** 待重试任务 → 列表展示文案（登记时间 + 状态） */
internal fun PendingOcrTask.describe(): String = "${TimeFormatters.formatDateTime(createdAtMillis)} · ${status.label()}"