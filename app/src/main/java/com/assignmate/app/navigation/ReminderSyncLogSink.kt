package com.assignmate.app.navigation

import android.util.Log

/**
 * 提醒同步接线层的日志出口（framework）。
 *
 * 为什么单独抽出入口（而不是在 [ReminderSyncDispatcher] 里直接调 [Log]）：
 * - android.util.Log 在纯 JVM 单测下未桩实现（调用即 `Method d in android.util.Log not mocked`），
 *   而提醒同步的「静默 no-op」分支必须能被测试断言为**确实留痕**，
 *   否则提醒同步缺口会重新退化为不可观测（正是本次要修的问题）；
 * - 抽出接口后测试可注入替身出口，逐条断言「该分支留痕 + 日志内容」，
 *   生产实现仍是与工程一致的一行 [Log.d]（TAG = 类名）；
 * - 只记录 id 类等非敏感信息，绝不打印密钥、验证码或图片内容。
 *
 * 生产绑定由 [com.assignmate.app.di.ApplicationScopeModule.provideReminderSyncLogSink]（Hilt @Provides）
 * 提供，构造参数另带默认值以使既有测试调用点零改动。
 */
fun interface ReminderSyncLogSink {

    /**
     * 输出一条 debug 日志。
     *
     * 形参不带默认值（Kotlin 规定 `fun interface` 的抽象方法不可带默认值），
     * 无异常场景由调用方显式传 null。
     *
     * @param tag 日志 TAG（与工程约定一致：类名）
     * @param message 日志正文（仅 id 类信息，无敏感内容）
     * @param cause 关联异常；无异常时为 null
     */
    fun debug(tag: String, message: String, cause: Throwable?)
}

/**
 * 生产日志出口：与工程日志约定一致（debug 级、TAG = 类名）。
 */
object AndroidReminderSyncLogSink : ReminderSyncLogSink {

    override fun debug(tag: String, message: String, cause: Throwable?) {
        if (cause == null) {
            Log.d(tag, message)
        } else {
            Log.d(tag, message, cause)
        }
    }
}