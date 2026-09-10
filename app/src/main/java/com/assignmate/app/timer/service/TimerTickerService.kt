package com.assignmate.app.timer.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.core.domain.util.TimeFormatters
import com.assignmate.app.timer.data.TimerTickerInfo
import com.assignmate.app.timer.domain.TimerCalculations
import com.assignmate.app.timer.domain.TimerConstants
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 计时走秒前台服务：保证退到后台/锁屏时计时持续走秒，并以常驻通知展示当前作业与已用时。
 *
 * 职责边界：
 * - 本服务只负责「按秒刷新通知」，不持有作业状态机——计时事实以 timer_session / pause_record
 *   为准（经 [com.assignmate.app.timer.data.TimerRepository] 落库），服务被系统回收也不会丢计时；
 * - 启停由计时页面经 [com.assignmate.app.timer.data.TimerTickerController] 下发
 *   （ACTION_START / ACTION_PAUSE / ACTION_RESUME，以及 stopService 收尾），
 *   每次指令都携带完整基准信息 [TimerTickerInfo]，故服务重建后仍能正确显示。
 *
 * 走秒口径：每 [TimerConstants.TICK_INTERVAL_MILLIS] 重新用
 * `参考时刻 - 开始时刻 - 暂停累计` 计算（而非按跳数累加），因此系统调度延迟不会造成累计误差；
 * 暂停期间参考时刻冻结为暂停开始时刻，通知不会继续涨秒。
 *
 * 唤醒锁：仅 RUNNING 期间持有 PARTIAL_WAKE_LOCK（保证锁屏/休眠时通知仍每秒刷新），
 * 暂停与销毁立即释放（Manifest 已声明 WAKE_LOCK，使声明与实现一致）；耗电取舍见 [acquireWakeLock]。
 *
 * 清单依赖（由 framework 计划的 AndroidManifest 同步，缺一不可）：
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 * <service
 *     android:name=".timer.service.TimerTickerService"
 *     android:exported="false"
 *     android:foregroundServiceType="specialUse">
 *     <property
 *         android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
 *         android:value="作业专注计时走秒" />
 * </service>
 * ```
 * 其中 foregroundServiceType 为 Android 14（API 34）起前台服务的强制要求，
 * POST_NOTIFICATIONS 仅影响 Android 13+ 通知是否可见（不影响服务运行与计时正确性）。
 */
@AndroidEntryPoint
class TimerTickerService : Service() {

    /** 可注入时钟：通知里的走秒与测试口径一致，禁止直接调用 System.currentTimeMillis() */
    @Inject
    lateinit var clock: Clock

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tickJob: Job? = null
    private var foregroundStarted = false
    private var info: TimerTickerInfo? = null

    /** 走秒唤醒锁（仅 RUNNING 期间持有，见 [acquireWakeLock] 的耗电取舍说明） */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TimerNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val payload = intent?.let { infoOf(it) }
        if (action == null || payload == null) {
            // 进程被系统重建但未带回有效基准（START_STICKY 重投 null Intent）：直接收尾，
            // 计时事实仍在库中，用户回到计时页会按会话重建走秒与通知。
            stopTicker()
            return START_NOT_STICKY
        }
        info = payload
        // 立即进入前台：startForegroundService 要求 5 秒内调用 startForeground
        enterForeground()
        when (action) {
            ACTION_PAUSE -> {
                stopTicking()
                publish()
            }

            ACTION_START, ACTION_RESUME -> startTicking()

            else -> stopTicker()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTicking()
        releaseWakeLock()
        scope.cancel()
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    // ---- 走秒与通知 ----

    /** 启动走秒循环：先立即刷新一次（避免首帧延迟 1 秒），随后按刷新间隔重算 */
    private fun startTicking() {
        stopTicking()
        acquireWakeLock()
        tickJob = scope.launch {
            while (isActive) {
                publish()
                delay(TimerConstants.TICK_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
        // 暂停/停止后不再需要保持 CPU 唤醒（通知已冻结在同一显示值）
        releaseWakeLock()
    }

    /**
     * 持有走秒唤醒锁（仅 RUNNING 期间）。
     *
     * 取舍：Manifest 已声明 `WAKE_LOCK`，此前服务从未持有、声明与实现不一致；现在仅在进行中持有，
     * 保证屏幕熄灭/系统休眠时通知仍能每秒刷新（锁屏走秒可见）。代价是明显的耗电（CPU 不进入深度休眠），
     * 因此暂停、完成、服务销毁都会立即释放，且 acquire 带 [TimerConstants.WAKE_LOCK_TIMEOUT_MILLIS] 兜底；
     * 计时正确性本身不依赖该锁（走秒按时间函数重算），需要省电时可调大刷新间隔或去掉本锁。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = runCatching {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TimerConstants.WAKE_LOCK_TAG).apply {
                // 非引用计数：重复 release 不会抛异常，配合下方 isHeld 判断足够
                setReferenceCounted(false)
                acquire(TimerConstants.WAKE_LOCK_TIMEOUT_MILLIS)
            }
        }.getOrNull()
    }

    /** 释放走秒唤醒锁（幂等；系统限制/异常时静默跳过，不影响走秒与通知） */
    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    private fun enterForeground() {
        startForeground(TimerNotifications.NOTIFICATION_ID, buildNotification())
        foregroundStarted = true
    }

    /**
     * 刷新常驻通知；因走秒是时间函数而非累加值，暂停状态下重复刷新得到同一结果（幂等）。
     *
     * Android 13+ 未授予 POST_NOTIFICATIONS 时跳过刷新：服务本身仍在前台运行、计时不受影响，
     * 仅通知不可见（用自定义守卫替代 lint 无法识别的权限判断）。
     */
    @SuppressLint("MissingPermission")
    private fun publish() {
        if (!foregroundStarted) {
            return
        }
        if (!canPostNotifications()) {
            return
        }
        NotificationManagerCompat.from(this)
            .notify(TimerNotifications.NOTIFICATION_ID, buildNotification())
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val current = info
        val content = current?.homeworkContent.orEmpty()
        if (current == null) {
            return TimerNotifications.buildTickerNotification(
                context = this,
                title = "作业计时",
                contentText = "准备开始计时",
            )
        }
        val reference = current.frozenAtMillis ?: clock.currentTimeMillis()
        val elapsed = TimerCalculations.elapsedMillis(
            startedAtMillis = current.startedAtMillis,
            pausedTotalMillis = current.pausedTotalMillis,
            referenceMillis = reference,
        )
        val elapsedText = TimeFormatters.formatDuration(elapsed / TimerConstants.MILLIS_PER_SECOND)
        return TimerNotifications.buildTickerNotification(
            context = this,
            title = if (current.isPaused) "已暂停 · $content" else "计时中 · $content",
            contentText = if (current.isPaused) {
                "已用时 $elapsedText（有事走开中）"
            } else {
                "已用时 $elapsedText"
            },
        )
    }

    private fun stopTicker() {
        stopTicking()
        stopSelf()
    }

    companion object {

        /** 开始/继续走秒 */
        const val ACTION_START = "com.assignmate.app.timer.action.START"

        /** 冻结走秒（暂停） */
        const val ACTION_PAUSE = "com.assignmate.app.timer.action.PAUSE"

        /** 解除冻结（恢复） */
        const val ACTION_RESUME = "com.assignmate.app.timer.action.RESUME"

        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val EXTRA_HOMEWORK_CONTENT = "extra_homework_content"
        private const val EXTRA_STARTED_AT_MILLIS = "extra_started_at_millis"
        private const val EXTRA_PAUSED_TOTAL_MILLIS = "extra_paused_total_millis"
        private const val EXTRA_FROZEN_AT_MILLIS = "extra_frozen_at_millis"

        /** 「未暂停」哨兵值（Bundle 无 null Long，用 -1 表达 null 语义） */
        private const val NO_FROZEN_AT = -1L

        /** 「无会话」哨兵值：sessionId 必须为正，非正值一律视为无效指令 */
        private const val NO_SESSION_ID = 0L

        /** 构造走秒指令（UI/控制器统一经此，避免各处散落 extra key） */
        fun intentOf(context: Context, action: String, info: TimerTickerInfo): Intent =
            Intent(context, TimerTickerService::class.java)
                .setAction(action)
                .putExtra(EXTRA_SESSION_ID, info.sessionId)
                .putExtra(EXTRA_HOMEWORK_CONTENT, info.homeworkContent)
                .putExtra(EXTRA_STARTED_AT_MILLIS, info.startedAtMillis)
                .putExtra(EXTRA_PAUSED_TOTAL_MILLIS, info.pausedTotalMillis)
                .putExtra(EXTRA_FROZEN_AT_MILLIS, info.frozenAtMillis ?: NO_FROZEN_AT)

        /** 解析走秒指令；缺少有效 sessionId 时返回 null（视为无效指令） */
        fun infoOf(intent: Intent): TimerTickerInfo? {
            val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, NO_SESSION_ID)
            if (sessionId <= 0L) {
                return null
            }
            val frozenAt = intent.getLongExtra(EXTRA_FROZEN_AT_MILLIS, NO_FROZEN_AT)
            return TimerTickerInfo(
                sessionId = sessionId,
                homeworkContent = intent.getStringExtra(EXTRA_HOMEWORK_CONTENT).orEmpty(),
                startedAtMillis = intent.getLongExtra(EXTRA_STARTED_AT_MILLIS, 0L),
                pausedTotalMillis = intent.getLongExtra(EXTRA_PAUSED_TOTAL_MILLIS, 0L),
                frozenAtMillis = frozenAt.takeIf { it != NO_FROZEN_AT },
            )
        }
    }
}
