package com.assignmate.app.timer.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 到点提醒广播接收器：闹钟触发时**先核对作业状态**，再决定是否发出「作业时间到」通知并震动。
 *
 * 触发链：[TimerNotifications] 提醒渠道 ← 本接收器 ← AlarmManager 闹钟
 * （调度与取消见 [com.assignmate.app.timer.data.AndroidHomeworkAlarmScheduler]）。
 *
 * 为什么必须核对（评审要求）：闹钟可能在任意时刻唤起进程，触发时作业可能已被删除或已完成；
 * 若直接按 Intent 携带的内容发通知，就会产生「指向已失效/已删除作业的陈旧提醒」。因此：
 * - 核对（[HomeworkReminderVerifier]）读库确认作业仍为「待完成」→ 点名**库内**内容发通知；
 * - 作业不存在或状态已变（Stale）→ **静默返回**，不发通知也不震动；
 * - 核对不可用（Unknown）→ 发出**中性文案**（「去作业清单看看这一项」），绝不点名可能失效的作业。
 *
 * 无依赖注入 + 冷启动可用：本类不使用 Hilt（闹钟唤起时进程可能刚启动，不做 DI 装配），
 * 只用 ApplicationContext 核对与发通知；Intent 仍携带作业内容，但**仅作诊断**，
 * 展示内容一律以核对结果为准。
 *
 * 线程与生命周期：读库属 IO，用 `goAsync()` 把广播生命周期交给后台协程，核对完成即 `finish()`，
 * 不在主线程做 IO；通知不可见时（Android 13+ 未授予 POST_NOTIFICATIONS）仍然震动（Manifest 已声明 VIBRATE）。
 *
 * Manifest 声明（**由 framework 计划同步**）：
 * ```xml
 * <receiver
 *     android:name=".timer.service.HomeworkAlarmReceiver"
 *     android:exported="false" />
 * ```
 */
class HomeworkAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HOMEWORK_REMINDER) {
            return
        }
        val homeworkId = homeworkIdOf(intent)
        if (homeworkId <= NO_HOMEWORK_ID) {
            // 无有效作业 id：无效指令直接忽略（不崩溃、不打扰）
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                deliver(appContext, homeworkId)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    /** 核对 → 决策 → 投递：决策与文案均为纯规则（[HomeworkReminderDeliveryRules]），此处只做副作用 */
    private suspend fun deliver(context: Context, homeworkId: Long) {
        val verification = HomeworkReminderVerifier(context).verify(homeworkId)
        val texts = HomeworkReminderDeliveryRules.textsOf(
            HomeworkReminderDeliveryRules.decide(verification),
        ) ?: return
        TimerNotifications.ensureReminderChannel(context)
        postReminderNotification(context, homeworkId, texts)
        vibrateOnce(context)
    }

    /** 发出到点提醒通知（Android 13+ 未授权通知时静默跳过，仅保留震动） */
    private fun postReminderNotification(context: Context, homeworkId: Long, texts: ReminderTexts) {
        if (!canPostNotifications(context)) {
            return
        }
        NotificationManagerCompat.from(context).notify(
            TimerNotifications.reminderNotificationIdOf(homeworkId),
            TimerNotifications.buildReminderNotification(context, texts),
        )
    }

    /** 到点震动（Manifest 已声明 VIBRATE；波形与提醒渠道保持一致） */
    private fun vibrateOnce(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        // 无马达/系统限制震动时静默跳过，绝不影响提醒通知本身
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(TimerNotifications.VIBRATION_PATTERN, -1))
        }
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {

        /** 到点提醒广播 action（调度器构造 Intent 时使用） */
        const val ACTION_HOMEWORK_REMINDER = "com.assignmate.app.timer.action.HOMEWORK_REMINDER"

        private const val EXTRA_HOMEWORK_ID = "extra_homework_id"
        private const val EXTRA_HOMEWORK_CONTENT = "extra_homework_content"

        /** 无效/缺失作业 id 的哨兵值 */
        private const val NO_HOMEWORK_ID = 0L

        /**
         * 广播处理协程作用域：进程级（随进程结束回收）。
         * 只用于「核对 + 发通知」这类毫秒级收尾工作，配合 goAsync() 的 finish() 保证广播生命周期正确。
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 构造提醒广播 Intent（调度器与测试共用，避免各处散落 extra key） */
        fun intentOf(context: Context, homeworkId: Long, content: String): Intent =
            Intent(context, HomeworkAlarmReceiver::class.java)
                .setAction(ACTION_HOMEWORK_REMINDER)
                .putExtra(EXTRA_HOMEWORK_ID, homeworkId)
                .putExtra(EXTRA_HOMEWORK_CONTENT, content)

        /** 解析作业 id（缺失返回 0） */
        fun homeworkIdOf(intent: Intent): Long =
            intent.getLongExtra(EXTRA_HOMEWORK_ID, NO_HOMEWORK_ID)

        /** 解析 Intent 携带的作业内容（缺失返回空串）；仅作诊断，通知展示内容以核对结果为准 */
        fun contentOf(intent: Intent): String =
            intent.getStringExtra(EXTRA_HOMEWORK_CONTENT).orEmpty()
    }
}
