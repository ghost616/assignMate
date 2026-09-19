package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import java.time.ZoneId

/**
 * 到点提醒与超时鼓励的规则集合（纯函数、无 Android 依赖、时间由调用方传入，集中可单测）。
 *
 * 覆盖四类规则：
 * 1. 触发时刻（TODAY 当天作业）：以「已排定开始时间 − 提前量」为提醒时刻（未排定时间的作业不设提醒）；
 * 2. 是否值得设置闹钟：仅「待完成」且触发时刻未过期太久（宽限窗口内）的作业；
 * 3. 阶段作业（STAGE）的**每日到点提醒**：按「每日截止时刻」（阶段 deadline 的当日时刻）
 *    在阶段范围内的每一天各设一个闹钟——阶段作业改为「1 条 + 每天详情」后，
 *    逐日提醒才是「每天到点」的正确表达（见 [stageDailyTriggers]）；
 * 4. 超时鼓励去重：同一作业两次提醒之间至少间隔 [TimerConstants.OVERDUE_PROMPT_INTERVAL_MILLIS]，
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

    /**
     * 「作业 + 自然日」→ 按天提醒的闹钟请求码：阶段作业的每一天各有一个闹钟，
     * 同一（作业，自然日）始终映射到同一请求码，故重设即覆盖、取消能精确命中。
     *
     * 与 [requestCodeOf] 的取值区间刻意错开（[TimerConstants.ALARM_REQUEST_CODE_DAY_BASE]），
     * 否则阶段作业的某一天可能与另一条作业的单次提醒撞码而互相覆盖。
     * 取舍：与单次提醒一样按模取码，理论上存在「不同（作业，日）映射到同一请求码」的可能，
     * 但同一对恒定映射，取消与重设语义因此保持正确。
     */
    fun requestCodeOf(homeworkId: Long, epochDay: Long): Int {
        val mixed = homeworkId * TimerConstants.ALARM_REQUEST_CODE_DAY_MIX + epochDay
        return TimerConstants.ALARM_REQUEST_CODE_DAY_BASE +
            mixed.mod(TimerConstants.ALARM_REQUEST_CODE_MODULUS.toLong()).toInt()
    }

    // ---- 阶段作业（STAGE）每日到点提醒 ----

    /** 阶段作业某一天的提醒（自然日 + 触发时刻），触发时刻按业务时区把「每日截止时刻」落到该天 */
    data class StageDailyTrigger(
        /** 归属业务自然日（epochDay） */
        val epochDay: Long,
        /** 该天的提醒触发时刻（epoch 毫秒） */
        val triggerAtMillis: Long,
    )

    /**
     * 阶段作业自今天起应设置的每日提醒（按自然日升序；非阶段作业、缺少每日截止口径或范围已过返回空列表）。
     *
     * 口径与数据来源（全部复用 homework 的既有领域语义，避免两套「阶段范围 / 每日时刻」口径）：
     * - 每日截止时刻 = [HomeworkItem.dailyDeadlineTime]（阶段作业的 deadline 只承载 time-of-day）；
     * - 阶段覆盖区间 = [HomeworkItem.stageStartEpochDay] ~ [HomeworkItem.stageLastEpochDay]（起始日 + 覆盖天数）；
     * - 某天的触发瞬时由 [HomeworkDailyDeadlineCodec.instantAt] 按业务时区落实。
     *
     * 起点取「阶段起始日」与 [todayEpochDay] 的较晚者：**过去的每一天不补提醒**；
     * 到点口径复用单次提醒的过期宽限：某天的触发时刻早于「现在 − 宽限」时跳过该天，
     * 避免打开应用就被当天已过去的提醒补发轰炸；宽限窗口内（刚过点）仍设置，由系统立即触发。
     *
     * 说明：本函数只按「时间 + 阶段范围」给出应设的提醒，**不做任何锁定**——
     * 到点仅作提醒/逾期标识，当天内仍可完成（「当天是否已完成」由调用方按每天详情过滤）。
     */
    fun stageDailyTriggers(
        item: HomeworkItem,
        todayEpochDay: Long,
        nowMillis: Long,
        zoneId: ZoneId,
    ): List<StageDailyTrigger> {
        val dailyTime = item.dailyDeadlineTime ?: return emptyList()
        val startEpochDay = item.stageStartEpochDay ?: return emptyList()
        val lastEpochDay = item.stageLastEpochDay ?: return emptyList()
        val firstDay = maxOf(startEpochDay, todayEpochDay)
        if (lastEpochDay < firstDay) {
            return emptyList()
        }
        return (firstDay..lastEpochDay)
            .map { epochDay ->
                StageDailyTrigger(
                    epochDay = epochDay,
                    triggerAtMillis = HomeworkDailyDeadlineCodec
                        .instantAt(epochDay, dailyTime, zoneId)
                        .toEpochMilli(),
                )
            }
            .filter { it.triggerAtMillis >= nowMillis - TimerConstants.REMINDER_STALE_GRACE_MILLIS }
    }

    /**
     * 某个业务自然日是否仍落在阶段覆盖区间内（含首尾）——**到点投递侧的核对口径**。
     *
     * 为什么投递侧要再判一次（不能只靠调度侧取消）：调度侧的「取消」依赖「同步动作真的发生过」，
     * 而阶段范围缩短/作业删除/类型切回 TODAY 后，若同步没跑到（进程被杀、页面未进入、作业被删），
     * 旧区间内已排的每日闹钟仍会到点触发——用户会被错误提醒。故投递时按**库内当前事实**再核一次：
     * 该天已不在覆盖区间 → 该次提醒不展示，并由投递侧清理对应调度状态（见接收器）。
     *
     * 口径与调度侧同源：区间 = [HomeworkItem.stageStartEpochDay] ~ [HomeworkItem.stageLastEpochDay]
     * （起始日 + 覆盖天数），即 [HomeworkItem] 自己的两个派生属性，不另算一套。
     *
     * 保守兜底（刻意选择「不静默」）：非阶段作业、缺少每日截止时刻、或阶段范围无法从编码还原
     * （历史脏数据）时一律视为**在区间内**——此时宁可提醒（与修复前行为一致），
     * 也不因元数据缺失而静默丢掉用户本该收到的提醒。
     */
    fun isWithinStageRange(item: HomeworkItem, epochDay: Long): Boolean {
        if (!item.isStage || item.dailyDeadlineTime == null) {
            return true
        }
        val start = item.stageStartEpochDay ?: return true
        val last = item.stageLastEpochDay ?: return true
        return epochDay in start..last
    }
}
