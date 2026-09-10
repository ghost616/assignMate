package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus

/**
 * 到点提醒与超时鼓励的规则集合（纯函数、无 Android 依赖、时间由调用方传入，集中可单测）。
 *
 * 覆盖三类规则：
 * 1. 触发时刻：以「已排定开始时间 − 提前量」为提醒时刻（未排定时间的作业不设提醒）；
 * 2. 是否值得设置闹钟：仅「待完成」且触发时刻未过期太久（宽限窗口内）的作业；
 * 3. 超时鼓励去重：同一作业两次提醒之间至少间隔 [TimerConstants.OVERDUE_PROMPT_INTERVAL_MILLIS]，
 *    避免到点后反复骚扰；另含「作业 id → 闹钟请求码」的稳定映射（保证同一作业重设闹钟能覆盖旧闹钟）。
 */
object TimerReminderRules {

    /**
     * 提醒触发时刻 = 已排定开始时间 − 提前量；未排定开始时间返回 null（调用方据此不设提醒）。
     */
    fun triggerAtMillisOf(
        startTimeMillis: Long?,
        leadMillis: Long = TimerConstants.REMINDER_LEAD_MILLIS,
    ): Long? = startTimeMillis?.let { it - leadMillis }

    /** 作业的提醒触发时刻（未排定开始时间返回 null） */
    fun triggerAtMillis(item: HomeworkItem): Long? =
        triggerAtMillisOf(item.startTime?.toEpochMilli())

    /**
     * 是否应为该作业设置到点提醒：
     * - 仅状态为「待完成」的作业需要提醒（已记录未排定时间、进行中/已完成都不再提醒）；
     * - 必须有触发时刻；
     * - 触发时刻早于「现在 − 过期宽限」视为过期（例如作业排定时间已过去很久），
     *   不设闹钟以免打开应用后被过期提醒轰炸；宽限窗口内（刚过点）仍设闹钟，
     *   由系统立即触发，符合「到点提醒」的直觉。
     */
    fun canSchedule(item: HomeworkItem, nowMillis: Long): Boolean {
        if (item.status != HomeworkStatus.PENDING) {
            return false
        }
        val trigger = triggerAtMillis(item) ?: return false
        return trigger >= nowMillis - TimerConstants.REMINDER_STALE_GRACE_MILLIS
    }

    /**
     * 是否应再次给出超时鼓励：
     * - 从未提醒过（[lastPromptedAtMillis] 为 null）→ 提醒；
     * - 距上次提醒已达到 [intervalMillis] → 提醒；
     * - 时钟回拨（间隔为负）按「刚提醒过」处理，返回 false，避免异常重复骚扰。
     */
    fun shouldPromptOverdue(
        lastPromptedAtMillis: Long?,
        nowMillis: Long,
        intervalMillis: Long = TimerConstants.OVERDUE_PROMPT_INTERVAL_MILLIS,
    ): Boolean {
        if (lastPromptedAtMillis == null) {
            return true
        }
        val elapsed = nowMillis - lastPromptedAtMillis
        if (elapsed < 0L) {
            return false
        }
        return elapsed >= intervalMillis
    }

    /**
     * 作业 id → 闹钟请求码：同一作业始终映射到同一请求码，
     * 因此重设闹钟会覆盖旧闹钟、取消闹钟也能精确命中（[TimerConstants.ALARM_REQUEST_CODE_BASE] 之上取模避免越界）。
     */
    fun requestCodeOf(homeworkId: Long): Int =
        TimerConstants.ALARM_REQUEST_CODE_BASE +
            homeworkId.mod(TimerConstants.ALARM_REQUEST_CODE_MODULUS.toLong()).toInt()
}
