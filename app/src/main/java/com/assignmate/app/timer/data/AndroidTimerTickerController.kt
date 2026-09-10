package com.assignmate.app.timer.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.assignmate.app.timer.service.TimerNotifications
import com.assignmate.app.timer.service.TimerTickerService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TimerTickerController] 的 Android 实现：以「启动/停止前台服务」的方式控制走秒通知。
 *
 * 说明：本类只负责构造指令（Intent）与调用系统 API，通知渠道创建与通知内容拼装
 * 分别落在 [TimerNotifications] 与 [TimerTickerService]；UI 层仅依赖
 * [TimerTickerController] 接口，测试可注入空实现替身。
 */
@Singleton
class AndroidTimerTickerController @Inject constructor(
    @ApplicationContext private val context: Context,
) : TimerTickerController {

    override fun startTicker(info: TimerTickerInfo) {
        sendCommand(TimerTickerService.ACTION_START, info)
    }

    override fun markPaused(info: TimerTickerInfo) {
        sendCommand(TimerTickerService.ACTION_PAUSE, info)
    }

    override fun markRunning(info: TimerTickerInfo) {
        sendCommand(TimerTickerService.ACTION_RESUME, info)
    }

    override fun stopTicker() {
        context.stopService(Intent(context, TimerTickerService::class.java))
    }

    /** 通知渠道需先建好，否则 startForeground 的通知在 Android 8+ 不会展示 */
    private fun sendCommand(action: String, info: TimerTickerInfo) {
        TimerNotifications.ensureChannel(context)
        ContextCompat.startForegroundService(
            context,
            TimerTickerService.intentOf(context, action, info),
        )
    }
}
