package com.assignmate.app.timer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.assignmate.app.R
import com.assignmate.app.timer.domain.TimerReminderRules

/**
 * timer 通知渠道与通知内容工厂（计时走秒通知 + 作业到点提醒通知，均本模块自持，便于独立演进而不动 core）。
 *
 * 两个渠道刻意分开，便于用户按需关闭：
 * - `timer_ticker`（[CHANNEL_ID]）：计时走秒常驻通知，IMPORTANCE_LOW + 静音 + 不显示角标，
 *   只做状态展示，不打扰用户；
 * - `timer_reminder`（[REMINDER_CHANNEL_ID]）：作业到点提醒，IMPORTANCE_HIGH + 震动，
 *   由闹钟到点的 [HomeworkAlarmReceiver] 触发，需要「响铃/震动」的打扰能力。
 *
 * 通知图标复用工程内既有矢量图 [R.drawable.ic_launcher_foreground]，避免为计时单开一套资源。
 */
object TimerNotifications {

    /** 计时走秒通知渠道 id */
    const val CHANNEL_ID = "timer_ticker"

    /** 作业到点提醒通知渠道 id */
    const val REMINDER_CHANNEL_ID = "timer_reminder"

    /** 计时走秒通知 id（固定值：同一时刻只存在一条走秒通知，反复更新不堆叠） */
    const val NOTIFICATION_ID = 1001

    /** 到点提醒通知 id 基数（叠加作业请求码，保证不同作业的提醒互不覆盖） */
    private const val REMINDER_NOTIFICATION_ID_BASE = 2_000

    private const val CHANNEL_NAME = "作业专注计时"
    private const val CHANNEL_DESCRIPTION = "展示当前作业与计时走秒，保证锁屏与后台也持续计时"

    private const val REMINDER_CHANNEL_NAME = "作业到点提醒"
    private const val REMINDER_CHANNEL_DESCRIPTION = "作业排定时间到时提醒并震动，点开即可开始计时"

    /** 到点提醒的震动波形（等待 0ms → 震 400ms → 停 200ms → 震 400ms；渠道与 Receiver 共用同一波形） */
    val VIBRATION_PATTERN = longArrayOf(0L, 400L, 200L, 400L)

    /** 幂等创建计时走秒通知渠道（已存在则直接返回） */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 幂等创建到点提醒渠道（高优先级 + 震动，保证到点可感知） */
    fun ensureReminderChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(REMINDER_CHANNEL_ID) != null) {
            return
        }
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            REMINDER_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = REMINDER_CHANNEL_DESCRIPTION
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** 作业 id -> 到点提醒通知 id（同一作业重复提醒复用同一通知，不堆叠） */
    fun reminderNotificationIdOf(homeworkId: Long): Int =
        REMINDER_NOTIFICATION_ID_BASE + TimerReminderRules.requestCodeOf(homeworkId)

    /**
     * 构造常驻走秒通知。
     *
     * @param title 通知标题（如「计时中 · 语文第 3 课生字」）
     * @param contentText 通知正文（如「已用时 03:25」）
     */
    fun buildTickerNotification(
        context: Context,
        title: String,
        contentText: String,
    ): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(contentText)
        .setContentIntent(contentIntentOf(context))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    /**
     * 构造作业到点提醒通知（高优先级 + 震动，点击回到应用）。
     *
     * 文案由纯规则 [HomeworkReminderDeliveryRules.textsOf] 决定（核对通过才点名作业内容，
     * 否则用中性文案）；本工厂只负责渲染，避免「文案依赖可能失效的作业内容」散落到通知层。
     */
    fun buildReminderNotification(context: Context, texts: ReminderTexts): Notification =
        NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(texts.title)
            .setContentText(texts.body)
            .setContentIntent(contentIntentOf(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(VIBRATION_PATTERN)
            .build()

    /** 点击通知回到应用的 PendingIntent（两个渠道通用） */
    private fun contentIntentOf(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
}
