package com.assignmate.app.timer.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 计时/提醒相关权限种类（UI 引导与状态查询共用） */
enum class TimerPermissionKind {

    /** 通知权限（Android 13+ 运行时权限）：未授权时提醒不可见 */
    NOTIFICATIONS,

    /** 精确闹钟权限（Android 12+ 系统设置授权）：未授权时提醒降级为不精确 */
    EXACT_ALARM,
}

/**
 * 计时/提醒相关权限状态快照。
 *
 * 语义：两项权限**均不影响核心计时能力**（计时靠前台服务 + 库内会话，不依赖通知与精确闹钟），
 * 只影响「提醒是否可见」「提醒是否准点」，因此 UI 引导文案必须传达「不阻断计时」。
 */
data class TimerPermissionStatus(
    val notificationsGranted: Boolean,
    val exactAlarmGranted: Boolean,
) {

    /** 两项均已授权（无需任何引导） */
    val allGranted: Boolean get() = notificationsGranted && exactAlarmGranted
}

/** 权限状态查询契约（UI 依赖本接口，测试可注入替身；单方法故声明为 fun interface，测试可用 lambda 构造） */
fun interface TimerPermissionChecker {

    /** 读取当前权限状态（同步、轻量，可在页面恢复时反复调用） */
    fun status(): TimerPermissionStatus
}

/**
 * [TimerPermissionChecker] 的 Android 实现。
 *
 * 精确闹钟状态直接复用 [HomeworkAlarmScheduler.canScheduleExactAlarms]，保证「UI 提示」与
 * 「实际调度降级」口径一致（单一事实来源）；通知权限在 Android 13 以下恒为已授权。
 */
@Singleton
class AndroidTimerPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmScheduler: HomeworkAlarmScheduler,
) : TimerPermissionChecker {

    override fun status(): TimerPermissionStatus = TimerPermissionStatus(
        notificationsGranted = canPostNotifications(),
        exactAlarmGranted = alarmScheduler.canScheduleExactAlarms(),
    )

    /** Android 13（API 33）起 POST_NOTIFICATIONS 为运行时权限；更低版本默认可发通知 */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
