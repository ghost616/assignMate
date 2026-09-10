package com.assignmate.app.timer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.assignmate.app.timer.domain.TimerReminderRules
import com.assignmate.app.timer.service.HomeworkAlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HomeworkAlarmScheduler] 的 Android 实现：基于系统 [AlarmManager] 的 RTC_WAKEUP 闹钟。
 *
 * 关键取舍：
 * - **精确闹钟与降级**：Android 12（API 31）起 `setExactAndAllowWhileIdle` 需要用户在系统设置授予
 *   「闹钟和提醒」权限（Manifest 已声明 `SCHEDULE_EXACT_ALARM`）。未授权时本实现改用
 *   `setAndAllowWhileIdle`（不精确闹钟，系统可能在数分钟窗口内触发）并返回
 *   [AlarmScheduleResult.ScheduledInexact]，**绝不抛异常、绝不崩溃**；
 *   极端情况下精确闹钟授权在调用途中被撤销（SecurityException）时再降级一次，仍失败则视为未设置；
 *   PendingIntent 构造失败（如系统限制）同样收敛为「未设置」——[schedule] / [cancel] 对调用方**绝不抛异常**。
 * - **覆盖与取消**：请求码由 [TimerReminderRules.requestCodeOf] 由作业 id 稳定映射，
 *   配合 `FLAG_UPDATE_CURRENT`，同一作业重设即覆盖；[cancel] 用同一请求码精确取消。
 * - 闹钟到点后由 [HomeworkAlarmReceiver] 发通知并响铃震动（Receiver 的 Manifest 声明由 framework 计划同步）。
 */
@Singleton
class AndroidHomeworkAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : HomeworkAlarmScheduler {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    override fun schedule(
        homeworkId: Long,
        content: String,
        triggerAtMillis: Long,
    ): AlarmScheduleResult {
        val manager = alarmManager ?: return AlarmScheduleResult.NotScheduled
        // PendingIntent 构造本身也可能被系统拒绝（如 PendingIntent 数量超限）：
        // 一并收敛为「未设置」，保证本方法对调用方**绝不抛异常**
        val pendingIntent = runCatching { pendingIntentOf(homeworkId, content) }.getOrNull()
            ?: return AlarmScheduleResult.NotScheduled
        return runCatching {
            if (canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
                AlarmScheduleResult.ScheduledExact
            } else {
                // 未授权精确闹钟：降级为不精确闹钟（可能延迟触发），保证提醒能力可用且不崩溃
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                AlarmScheduleResult.ScheduledInexact
            }
        }.getOrElse {
            // 授权在调用途中被撤销（Android 12+，SecurityException）或系统拒绝：再降级一次；仍失败则视为未设置
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }.fold(
                onSuccess = { AlarmScheduleResult.ScheduledInexact },
                onFailure = { AlarmScheduleResult.NotScheduled },
            )
        }
    }

    override fun cancel(homeworkId: Long) {
        val manager = alarmManager ?: return
        // 请求码由作业 id 稳定映射，取消无需携带内容（PendingIntent 的相等性不含 extras）；
        // 取消同样不抛异常（幂等语义：失败等同于「没有可取消的闹钟」）
        val pendingIntent = runCatching { pendingIntentOf(homeworkId, content = "") }.getOrNull() ?: return
        runCatching { manager.cancel(pendingIntent) }
    }

    /** 构造与作业一一对应的广播 PendingIntent（同一作业的多次调用等价，可互相覆盖/取消） */
    private fun pendingIntentOf(homeworkId: Long, content: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            TimerReminderRules.requestCodeOf(homeworkId),
            HomeworkAlarmReceiver.intentOf(context, homeworkId, content),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
