package com.assignmate.app.timer.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.assignmate.app.timer.domain.TimerReminderRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 到点提醒的「读库核对」入口（fun interface）：生产用 [HomeworkReminderVerifier] 的只读核对，
 * 单测可注入替身以覆盖「核对结果 → 投递 + 清理」这条链路（无需 Android 数据库）。
 */
fun interface ReminderCheck {

    /** 核对（homeworkId, epochDay）是否仍值得提醒 */
    suspend fun verify(homeworkId: Long, epochDay: Long?): ReminderVerification

    companion object {

        /** 生产实现：经 ApplicationContext 只读核对同一数据库（冷启动可用，见核对器 KDoc） */
        fun database(context: Context): ReminderCheck =
            ReminderCheck { homeworkId, epochDay ->
                HomeworkReminderVerifier(context).verify(homeworkId, epochDay)
            }
    }
}

/**
 * 提醒失效时的「调度状态清理」动作（可在纯 JVM 单测中注入替身断言）。
 *
 * 语义：某次到点提醒经核对已失效（作业不存在/已完成/该天已超出阶段覆盖区间）时，
 * 取消「该作业 + 该自然日」对应的那一个闹钟（[epochDay] 为 null 表示单次提醒）。
 * 幂等：无对应闹钟时等价于无操作（视为成功）。
 *
 * **返回值即可观测出口**（修复轮：清理失败不得无痕迹）：`true` = 清理已下发或本就无闹钟可取消；
 * `false` = 清理没能完成（系统服务缺失 / PendingIntent 构造被拒 / 取消抛异常）。
 * 调用方（[HomeworkAlarmReceiver.deliver] / [HomeworkAlarmReceiver.deliverSafely]）据此返回结果，
 * 使「残留闹钟继续打扰用户」这一失效在纯 JVM 单测中可断言、在真机上可经 debug 日志定位。
 */
fun interface ReminderCleanup {

    /**
     * 取消（homeworkId, epochDay）对应的闹钟（epochDay 为 null 表示单次提醒）。
     *
     * @return true 表示清理已完成（含「本无闹钟」的幂等情形），false 表示清理失败
     */
    fun cancel(homeworkId: Long, epochDay: Long?): Boolean

    companion object {

        /**
         * 日志 TAG（与工程约定一致：类名）；正式版仅记录非敏感的诊断信息
         * （只有作业 id / 自然日 / 异常信息，不含作业内容与密钥）。
         */
        private const val TAG = "HomeworkAlarmReceiver"

        /** 空实现：不需要清理的场景（例如只关心投递结果的调用方）；声明为「已清理」不影响任何判定 */
        val NO_OP = ReminderCleanup { _, _ -> true }

        /**
         * Android 实现：用与调度器**同一请求码口径**（[TimerReminderRules.requestCodeOf]）构造
         * 等价的广播 PendingIntent 后 `AlarmManager.cancel`——PendingIntent 的相等性不含 extras，
         * 因此即便这里只带空内容，也能精确命中当初设置的闹钟。
         *
         * 绝不抛异常（清理失败不应影响广播收尾），但**失败必须留痕**：
         * - 异常（PendingIntent 构造被拒 / cancel 抛异常）→ `Log.d` 记录 homeworkId、epochDay 与失败原因，返回 false；
         * - AlarmManager 系统服务缺失 → 同样记录日志并返回 false（本次清理确实没能下发）；
         * - 成功（含 FLAG_UPDATE_CURRENT 命中一个并不存在的闹钟）→ 返回 true。
         *
         * 为什么用 debug 级别：清理失败属于诊断信息，正式版不需要输出；但缺了它就无从判断
         * 「用户仍被残留闹钟打扰」是调度侧还是清理侧的问题（与 [HomeworkAlarmReceiver.deliverSafely]
         * 的「记录 + 清理各自收口」口径一致）。
         */
        fun android(context: Context, content: String = ""): ReminderCleanup =
            ReminderCleanup { homeworkId, epochDay ->
                val requestCode = if (epochDay == null) {
                    TimerReminderRules.requestCodeOf(homeworkId)
                } else {
                    TimerReminderRules.requestCodeOf(homeworkId, epochDay)
                }
                val manager = context.getSystemService(AlarmManager::class.java)
                if (manager == null) {
                    Log.d(
                        TAG,
                        "提醒清理未能下发：AlarmManager 系统服务缺失；" +
                            "homeworkId=$homeworkId epochDay=$epochDay",
                    )
                    return@ReminderCleanup false
                }
                runCatching {
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        HomeworkAlarmReceiver.intentOf(context, homeworkId, content, epochDay),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    manager.cancel(pendingIntent)
                }.fold(
                    onSuccess = { true },
                    onFailure = { failure ->
                        // 失败留痕：残留闹钟会在后续日子继续触发，没有这条日志开发侧无从定位
                        Log.d(
                            TAG,
                            "提醒清理失败：homeworkId=$homeworkId epochDay=$epochDay " +
                                "requestCode=$requestCode",
                            failure,
                        )
                        false
                    },
                )
            }
    }
}

/**
 * 到点提醒广播接收器：闹钟触发时**先核对作业状态与所属自然日**，再决定是否发出「作业时间到」通知并震动。
 *
 * 触发链：[TimerNotifications] 提醒渠道 ← 本接收器 ← AlarmManager 闹钟
 * （调度与取消见 [com.assignmate.app.timer.data.AndroidHomeworkAlarmScheduler]）。
 *
 * 为什么必须核对（评审要求）：闹钟可能在任意时刻唤起进程，触发时作业可能已被删除、已完成，
 * 或**该天已因阶段范围缩短/类型切回 TODAY 而不再属于覆盖区间**；
 * 若直接按 Intent 携带的内容发通知，就会产生「指向已失效/已超出区间作业的陈旧提醒」。因此：
 * - 核对（[HomeworkReminderVerifier]）读库确认「作业仍待完成 且 该天仍在阶段覆盖区间内」→ 点名**库内**内容发通知；
 * - 失效（Stale）→ **静默返回**（不发通知也不震动），并**清理该次闹钟的调度状态**（[ReminderCleanup]，
 *   按 `requestCodeOf(homeworkId, epochDay)` 精确取消），使「同步没跑到就被唤起」的残留闹钟不再继续触发；
 * - 核对不可用（Unknown）→ 发出**中性文案**（「去作业清单看看这一项」），绝不点名可能失效的作业，
 *   也**不做清理**（无法确认失效时不做破坏性动作）。
 *
 * 为什么清理不写库：冷启动路径刻意不碰 Hilt / DataStore（避免与主进程实例冲突），
 * 用「作业 + 自然日 → 请求码」这一既有稳定映射即可精确取消，无需读取逐日登记表；
 * 作业被删除（登记表里可能还有多天）等批量场景由下次进入计时页/清单时协调器兜底清理。
 *
 * 无依赖注入 + 冷启动可用：本类不使用 Hilt（闹钟唤起时进程可能刚启动，不做 DI 装配），
 * 只用 ApplicationContext 核对与发通知；Intent 仍携带作业内容，但**仅作诊断**，
 * 展示内容一律以核对结果为准。
 *
 * 线程与生命周期：读库属 IO，用 `goAsync()` 把广播生命周期交给后台协程，核对（含清理）完成即 `finish()`，
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
        val epochDay = epochDayOf(intent)
        val pendingResult = goAsync()
        scope.launch {
            try {
                deliverSafely(
                    context = appContext,
                    homeworkId = homeworkId,
                    epochDay = epochDay,
                    cleanup = ReminderCleanup.android(appContext),
                    check = ReminderCheck.database(appContext),
                )
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    /**
     * [deliver] 的异常安全外壳（离朱加固建议）：核对或投递任一步骤抛异常时**绝不外抛**，
     * 并按「该次提醒已失效」处理——取消这次（作业 + 自然日）的闹钟（幂等；清理自身失败同样不外抛，
     * 由 [ReminderCleanup.cancel] 的返回值 / 其内部 debug 日志留痕）。
     *
     * 为什么需要：生产核对实现已用 `runCatching` 收敛为 [ReminderVerification.Unknown]，
     * 但 [ReminderCheck] 的其它实现或未来实现变化仍可能抛异常；此时若直接冒泡，
     * 既不会发通知也不会清理，残留闹钟就会在后续日子继续触发（正是本轮要消除的用户可感知缺陷）。
     * 协程取消（`CancellationException`）按结构化并发语义原样重抛，且不带清理副作用。
     *
     * 为什么保留 `Boolean` 返回值（[deliver] 亦汇报清理结果）：`onReceive` 不消费它，
     * 它属诊断/测试契约——单测据此断言「正常返回」与「兜底路径」；未来若要据此做重试或统计，
     * 需先接入调用点。返回 false **不代表一定发过通知**，只表示走了失败兜底路径。
     *
     * @return true 表示 [deliver] 正常返回（不代表一定发了通知，也不代表清理成功——清理结果以
     *   [deliver] 的返回值为准），false 表示走了失败兜底路径
     */
    internal suspend fun deliverSafely(
        context: Context,
        homeworkId: Long,
        epochDay: Long?,
        cleanup: ReminderCleanup = ReminderCleanup.NO_OP,
        check: ReminderCheck = ReminderCheck.database(context),
    ): Boolean = try {
        deliver(
            context = context,
            homeworkId = homeworkId,
            epochDay = epochDay,
            cleanup = cleanup,
            check = check,
        )
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // 记录 + 清理各自包一层：连日志/清理自身失败也不得让广播收尾出问题
        runCatching { Log.d(TAG, "到点提醒投递失败，已取消该次闹钟 homeworkId=$homeworkId epochDay=$epochDay", e) }
        runCatching { cleanup.cancel(homeworkId, epochDay) }
        false
    }

    /**
     * 核对 → 决策 → 投递 → 收口：决策与文案均为纯规则（[HomeworkReminderDeliveryRules]），此处只做副作用。
     *
     * [epochDay] 为**逐日提醒所属的业务自然日**（阶段作业每日提醒才携带）：核对时一并检查
     * 「这一天的每天详情是否已完成」「这一天的阶段覆盖是否仍成立」——已完成当天或已超出区间都不再打扰。
     *
     * 失效（Stale）时额外调用 [cleanup] 取消该次（作业 + 自然日）的闹钟，消除「同步缺失留下的残留闹钟」；
     * 核对不可用（Unknown）时只投中性文案，不做清理。
     *
     * internal 便于单测直接覆盖「决策 + 清理」链路（[check] / [cleanup] 均可注入替身，无需 Android 数据库）。
     *
     * @return [deliverSafely] 的成功语义与清理成败解耦：清理失败**不会**让本方法走异常兜底路径
     *   （故 `deliverSafely` 仍返回 true），清理结果经本返回值单独汇报——
     *   失效路径下即 [ReminderCleanup.cancel] 的结果（false = 残留闹钟可能仍在）；
     *   正常投递与「核对不可用（不清理）」路径恒为 true（无可清理动作视为未失败）。
     */
    internal suspend fun deliver(
        context: Context,
        homeworkId: Long,
        epochDay: Long?,
        cleanup: ReminderCleanup = ReminderCleanup.NO_OP,
        check: ReminderCheck = ReminderCheck.database(context),
    ): Boolean {
        val verification = check.verify(homeworkId, epochDay)
        val delivery = HomeworkReminderDeliveryRules.decide(verification)
        val texts = HomeworkReminderDeliveryRules.textsOf(delivery)
        if (texts == null) {
            // 已失效：不发通知、不震动，并清掉这次（作业 + 自然日）的闹钟，不留可再次触发的残留
            // （是否清理由纯规则 requiresCleanup 决定：只有「明确失效」才清理，核对不可用时不清理）；
            // 清理结果原样汇报：false 表示残留闹钟可能仍在，调用方/单测据此可观测失败
            return if (HomeworkReminderDeliveryRules.requiresCleanup(delivery)) {
                cleanup.cancel(homeworkId, epochDay)
            } else {
                true
            }
        }
        TimerNotifications.ensureReminderChannel(context)
        postReminderNotification(context, homeworkId, texts)
        vibrateOnce(context)
        return true
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

        /** 日志 TAG（与工程约定一致：类名）；正式版仅记录非敏感的诊断信息 */
        private const val TAG = "HomeworkAlarmReceiver"

        /** 到点提醒广播 action（调度器构造 Intent 时使用） */
        const val ACTION_HOMEWORK_REMINDER = "com.assignmate.app.timer.action.HOMEWORK_REMINDER"

        private const val EXTRA_HOMEWORK_ID = "extra_homework_id"
        private const val EXTRA_HOMEWORK_CONTENT = "extra_homework_content"
        private const val EXTRA_EPOCH_DAY = "extra_epoch_day"

        /** 无效/缺失作业 id 的哨兵值 */
        private const val NO_HOMEWORK_ID = 0L

        /** 未携带业务自然日的哨兵值（单次提醒，或旧版本已设的闹钟） */
        private const val NO_EPOCH_DAY = 0L

        /**
         * 广播处理协程作用域：进程级（随进程结束回收）。
         * 只用于「核对 + 发通知」这类毫秒级收尾工作，配合 goAsync() 的 finish() 保证广播生命周期正确。
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 构造提醒广播 Intent（调度器与测试共用，避免各处散落 extra key）。
         *
         * [epochDay] 为逐日提醒（阶段作业每日到点）所属的业务自然日；单次提醒传 null
         * （不带该 extra，投递侧按「不区分逐日」的既有口径核对，向后兼容旧闹钟）。
         */
        fun intentOf(
            context: Context,
            homeworkId: Long,
            content: String,
            epochDay: Long? = null,
        ): Intent {
            val intent = Intent(context, HomeworkAlarmReceiver::class.java)
                .setAction(ACTION_HOMEWORK_REMINDER)
                .putExtra(EXTRA_HOMEWORK_ID, homeworkId)
                .putExtra(EXTRA_HOMEWORK_CONTENT, content)
            if (epochDay != null) {
                intent.putExtra(EXTRA_EPOCH_DAY, epochDay)
            }
            return intent
        }

        /** 解析作业 id（缺失返回 0） */
        fun homeworkIdOf(intent: Intent): Long =
            intent.getLongExtra(EXTRA_HOMEWORK_ID, NO_HOMEWORK_ID)

        /**
         * 解析逐日提醒的业务自然日：缺失（单次提醒/旧闹钟）返回 null，
         * 使投递侧退化为「不区分逐日」的既有核对口径。
         */
        fun epochDayOf(intent: Intent): Long? =
            intent.getLongExtra(EXTRA_EPOCH_DAY, NO_EPOCH_DAY).takeIf { it != NO_EPOCH_DAY }

        /** 解析 Intent 携带的作业内容（缺失返回空串）；仅作诊断，通知展示内容以核对结果为准 */
        fun contentOf(intent: Intent): String =
            intent.getStringExtra(EXTRA_HOMEWORK_CONTENT).orEmpty()
    }
}
