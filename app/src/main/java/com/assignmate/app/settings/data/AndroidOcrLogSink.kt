package com.assignmate.app.settings.data

import com.assignmate.app.settings.domain.OcrLogSink

/**
 * [OcrLogSink] 的 Android 实现：仅 debug 可调试构建输出，正式版静默丢弃。
 *
 * 与 core 的网络层日志策略一致（core.di.NetworkModule 亦按 ApplicationInfo.FLAG_DEBUGGABLE 判定），
 * 由 DI 提供该布尔值，本类不直接依赖 Context，便于替换与测试。
 */
class AndroidOcrLogSink(
    private val isDebuggable: Boolean,
) : OcrLogSink {

    override fun log(tag: String, message: String) {
        if (isDebuggable) {
            android.util.Log.d(tag, message)
        }
    }
}