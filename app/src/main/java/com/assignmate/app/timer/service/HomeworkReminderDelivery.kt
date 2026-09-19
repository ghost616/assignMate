package com.assignmate.app.timer.service

/**
 * 到点提醒的作业状态核对结果（由 [HomeworkReminderVerifier] 产出，纯数据便于单测）。
 *
 * 引入原因：闹钟可能在任意时刻唤起进程，触发时作业可能已被删除或已完成，
 * 若直接按 Intent 里携带的内容发通知，就会出现「指向已失效作业的陈旧提醒」。
 */
sealed class ReminderVerification {

    /** 核对通过：作业仍存在且处于「待完成」，[content] 为库内权威内容 */
    data class ConfirmedPending(val content: String) : ReminderVerification()

    /** 提醒已失效：作业不存在（已删除）或状态已不是「待完成」 */
    data object Stale : ReminderVerification()

    /** 无法核对（数据库不可用/核对异常）：既不能确认也不能否认 */
    data object Unknown : ReminderVerification()
}

/** 到点提醒的投递动作 */
sealed class ReminderDelivery {

    /** 发通知并点名作业内容（仅在核对通过时使用，内容取自库内） */
    data class Named(val content: String) : ReminderDelivery()

    /** 发通知但用中性文案（核对不可用时退化为「去清单看看这一项」，不点名可能失效的作业） */
    data object Neutral : ReminderDelivery()

    /** 静默：提醒已失效，不发通知也不震动 */
    data object Silent : ReminderDelivery()
}

/** 提醒通知文案（标题 + 正文，纯数据便于单测） */
data class ReminderTexts(val title: String, val body: String)

/**
 * 到点提醒的投递决策与文案规则（纯函数、集中可单测）。
 *
 * 决策表：
 * - [ReminderVerification.ConfirmedPending] → [ReminderDelivery.Named]（点名库内内容）；
 * - [ReminderVerification.Stale] → [ReminderDelivery.Silent]（作业已删除/已完成：完全不打扰）；
 * - [ReminderVerification.Unknown] → [ReminderDelivery.Neutral]（无法核对：只提示「去清单看看」，
 *   绝不点名可能失效的作业）。
 */
object HomeworkReminderDeliveryRules {

    /** 中性文案：不依赖任何可能失效的作业内容（评审要求的最低加固口径） */
    val NEUTRAL_TEXTS = ReminderTexts(
        title = "作业时间到啦",
        body = "去作业清单看看这一项，准备好就开始计时吧！",
    )

    /**
     * 该投递动作是否应当触发「调度状态清理」（取消该次闹钟，不留可再次触发的残留）。
     *
     * 只在 [ReminderDelivery.Silent]（核对明确判定失效）时为真：
     * - 阶段范围缩短/类型切回 TODAY/作业被删后，旧区间内的每日闹钟可能因「同步没跑到」而残留并触发，
     *   投递侧按库内事实确认失效后顺手精确取消该天，避免用户在后续日子被反复打扰；
     * - [ReminderDelivery.Neutral]（核对不可用）**不清理**：无法确认失效时不做破坏性动作。
     */
    fun requiresCleanup(delivery: ReminderDelivery): Boolean = delivery is ReminderDelivery.Silent

    /** 由核对结果推导投递动作 */
    fun decide(verification: ReminderVerification): ReminderDelivery = when (verification) {
        is ReminderVerification.ConfirmedPending -> ReminderDelivery.Named(verification.content)
        ReminderVerification.Stale -> ReminderDelivery.Silent
        ReminderVerification.Unknown -> ReminderDelivery.Neutral
    }

    /**
     * 由投递动作推导通知文案；[ReminderDelivery.Silent] 返回 null（调用方据此静默返回，不发通知）。
     * 点名的内容为空白时退化为中性文案（与核对通过但内容缺失的情况一致）。
     */
    fun textsOf(delivery: ReminderDelivery): ReminderTexts? = when (delivery) {
        is ReminderDelivery.Named -> {
            val trimmed = delivery.content.trim()
            if (trimmed.isEmpty()) {
                NEUTRAL_TEXTS
            } else {
                ReminderTexts(
                    title = "作业时间到啦：$trimmed",
                    body = "点开开始计时，一起专心完成这一项吧！",
                )
            }
        }

        ReminderDelivery.Neutral -> NEUTRAL_TEXTS
        ReminderDelivery.Silent -> null
    }
}
