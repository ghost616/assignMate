package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageDayRecords
import java.time.ZoneId

/**
 * 计时纯函数计算与规则集合（无副作用、无 IO、时间由调用方传入，集中可单测）。
 *
 * 覆盖四类业务规则：
 * 1. 已用时长：`当前时刻 - 开始时刻 - 暂停累计`（负数收敛为 0，容忍时钟回拨）；
 * 2. 暂停汇总：累计暂停毫秒（含未结束暂停按入参时刻折算）与暂停次数；
 * 3. 超时判定：超过「开始时间 + 预估时长（+ 宽限）」或超过截止时刻仍未完成；
 *    **截止时刻按作业类型分流取数**（[absoluteDeadlineMillisOf]）：TODAY 取绝对时刻、
 *    STAGE 取「当天 + 每日截止时刻」折算出的该天到点瞬时（deadline 列对阶段作业不是绝对时间戳）；
 * 4. 下一项选取：按清单优先级顺序取下一条可开始（待完成/进行中）的作业；
 *    以及休息倒计时的剩余时长计算。
 *
 * 所有方法均为纯函数：不读时钟、不访问数据库，时间来源由调用方（仓库/ViewModel）经可注入 Clock 提供。
 */
object TimerCalculations {

    // ---- 1. 已用时长 ----

    /**
     * 已用时长（毫秒）= 参考时刻 - 开始时刻 - 暂停累计。
     *
     * @param pausedTotalMillis 会话累计暂停毫秒（含「仍在暂停中」那一段按参考时刻折算的部分，
     *   由 [pauseAccumulatedMillis] 汇总得到）
     * @param referenceMillis 参考时刻：进行中会话取「现在」，已完成会话取结束时刻
     */
    fun elapsedMillis(
        startedAtMillis: Long,
        pausedTotalMillis: Long,
        referenceMillis: Long,
    ): Long = (referenceMillis - startedAtMillis - pausedTotalMillis).coerceAtLeast(0L)

    /** 会话的参考时刻：已完成会话取结束时刻（固定），未结束会话取入参的「现在」 */
    fun referenceMillisOf(session: TimerSession, nowMillis: Long): Long =
        session.finishedAt?.toEpochMilli() ?: nowMillis

    // ---- 2. 暂停汇总 ----

    /**
     * 累计暂停时长（毫秒）：已结束的暂停按「结束 - 开始」累加；
     * 未结束的暂停（暂停中）按「参考时刻 - 开始」折算，故暂停期间该值随时间增长。
     *
     * 结束时刻晚于参考时刻（例如暂停明细跨越了会话结束时刻的脏数据）按参考时刻截断，
     * 保证累计暂停不会超过会话总跨度；开始时刻晚于结束时刻的异常记录按 0 计。
     */
    fun pauseAccumulatedMillis(pauses: List<PauseRecord>, referenceMillis: Long): Long =
        pauses.sumOf { pause ->
            val start = pause.pauseStartAt.toEpochMilli()
            val end = (pause.pauseEndAt?.toEpochMilli() ?: referenceMillis).coerceAtMost(referenceMillis)
            (end - start).coerceAtLeast(0L)
        }

    /** 累计暂停次数：与暂停明细条数同源（未结束的暂停同样计入，「暂停中」界面显示已有 1 次） */
    fun pauseCount(pauses: List<PauseRecord>): Int = pauses.size

    /**
     * 毫秒时长 → 分钟数（**向上取整**）：不足 1 分钟的非零时长记 1 分钟，
     * 避免「做了 59 秒」在「作业每天详情」与实际时长口径里被记成 0 分钟；非正时长记 0 分钟。
     *
     * 用途：计时收尾时把「当日实际时长」「当日暂停总时长」折算为每天详情的分钟字段。
     */
    fun minutesOf(millis: Long): Int {
        if (millis <= 0L) {
            return 0
        }
        return ((millis + TimerConstants.MILLIS_PER_MINUTE - 1) / TimerConstants.MILLIS_PER_MINUTE).toInt()
    }

    /**
     * 会话已用时长：以会话自身时刻为参考（已完成会话用结束时刻固定口径），
     * 暂停累计由暂停明细实时重算——汇总列仅作快照，明细才是权威来源。
     */
    fun elapsedOf(session: TimerSession, pauses: List<PauseRecord>, nowMillis: Long): Long {
        val reference = referenceMillisOf(session, nowMillis)
        return elapsedMillis(
            startedAtMillis = session.startedAt.toEpochMilli(),
            pausedTotalMillis = pauseAccumulatedMillis(pauses, reference),
            referenceMillis = reference,
        )
    }

    /** 会话执行小结（已用时长 / 累计暂停 / 暂停次数），供计时页展示与 stats 统计复用 */
    fun summarize(session: TimerSession, pauses: List<PauseRecord>, nowMillis: Long): TimerSessionSummary {
        val reference = referenceMillisOf(session, nowMillis)
        val pausedTotal = pauseAccumulatedMillis(pauses, reference)
        return TimerSessionSummary(
            sessionId = session.id,
            phase = session.phase,
            elapsedMillis = elapsedMillis(session.startedAt.toEpochMilli(), pausedTotal, reference),
            pausedTotalMillis = pausedTotal,
            pauseCount = pauses.size,
        )
    }

    // ---- 3. 超时判定 ----

    /**
     * 是否超时：超过「开始时间 + 预估时长 + 宽限」或超过 [deadlineMillis] 仍未完成。
     *
     * 口径（与 [TimerConstants.OVERDUE_GRACE_MINUTES] 一致）：
     * - 仅当开始时间与预估时长都存在时才做预估超时判定（未排定时间不判超时）；
     * - [deadlineMillis] 单独成立即可判定超时（阶段作业有每日截止时刻但可能未排定开始时间）；
     * - 恰好在边界时刻（等于预估完成时刻 / 等于 deadline）不算超时，超过 1 毫秒即算。
     *
     * 重要：[deadlineMillis] 必须是**绝对瞬时**。作业的 deadline 列对阶段作业只承载「每日截止时刻」，
     * 因此调用方一律经 [absoluteDeadlineMillisOf] 取数，**不得**直接传 `item.deadline.toEpochMilli()`。
     *
     * @param referenceMillis 参考时刻：进行中会话传「现在」，已完成会话传入结束时刻可判定
     *   「这次作业当时是否已超时」。
     */
    fun isOverdue(
        startTimeMillis: Long?,
        estimatedMinutes: Int?,
        deadlineMillis: Long?,
        referenceMillis: Long,
    ): Boolean {
        val estimatedFinishedAt = if (startTimeMillis != null && estimatedMinutes != null) {
            startTimeMillis + estimatedMinutes * TimerConstants.MILLIS_PER_MINUTE +
                TimerConstants.OVERDUE_GRACE_MILLIS
        } else {
            null
        }
        if (estimatedFinishedAt != null && referenceMillis > estimatedFinishedAt) {
            return true
        }
        return deadlineMillis != null && referenceMillis > deadlineMillis
    }

    /**
     * 作业「绝对截止时刻」取数的**唯一入口**（按作业类型分流，避免各消费方各自解释同一列）。
     *
     * 为什么必须分流：homework 已把阶段作业（STAGE）的 `deadline` 改为「每日截止时刻」的载体
     * （`HomeworkDailyDeadlineCodec` 把「阶段起始日 + 当日时刻」编码进原 deadline 列），
     * 因此该列的裸数值**对阶段作业不是绝对时间戳**——按毫秒直接比较会让阶段作业在起始日之后恒判超时。
     *
     * - TODAY：deadline 仍是绝对「日期 + 时刻」→ 原样取毫秒（语义不变）；
     * - STAGE：deadline 只提供每日时刻，需按 [epochDay]（业务自然日）折算为「**该天**的到点瞬时」，
     *   取数复用 homework 的阶段口径唯一来源 [StageDayRecords.dailyDeadlineOf]（不再自行解释裸数值）；
     *   缺每日时刻或脏数据时返回 null。
     *
     * **「未设每日截止时刻」的统一口径 = 该天不产生约束（返回 null）**，与 homework / UI 侧逐例一致：
     * - 校验侧：[HomeworkValidators.validateScheduleWithinItemDeadline] 遇空每日时刻直接放行
     *   （学生录入的阶段作业允许留空，故「起始日 23:01 开始 600 分钟」这类跨日排定被放行）；
     * - 取数侧：本方法与 homework 的 [HomeworkItem.absoluteDeadlineAt] 都委托
     *   [StageDayRecords.dailyDeadlineOf]，两侧恒等价（无第二口径）；
     * - 判定侧：[isHomeworkOverdue] 在取数为 null 时**不按截止时刻判超时**（只保留预估时长口径），
     *   故「校验放行 + 计时判超时」的分叉不会出现（跨日点 23:01 / 次日 00:05 有一致性用例守着）。
     * 分工：**校验**一律走 [HomeworkValidators.validateScheduleWithinItemDeadline]；
     * 取数（含判定）一律走本方法——timer 侧不出现第二个把阶段作业 `item.deadline` 当绝对时刻的消费方。
     *
     * @param epochDay 目标业务自然日（阶段作业「算哪一天」由调用方按业务时区给出）
     */
    fun absoluteDeadlineMillisOf(item: HomeworkItem, epochDay: Long, zoneId: ZoneId): Long? =
        if (item.isStage) {
            StageDayRecords.dailyDeadlineOf(item, epochDay, zoneId)?.toEpochMilli()
        } else {
            item.deadline?.toEpochMilli()
        }

    /**
     * 作业当前是否超时（按作业类型分流，已完成会话以会话结束时刻为参考、未开始/进行中以「现在」为参考）。
     *
     * - **TODAY**：保持既有「绝对日期 + 时刻」语义不变——排定时间段「开始 + 预估 + 宽限」与绝对 deadline
     *   各自成立即可判超时；deadline 为空时只保留预估口径；
     * - **STAGE**：一律按**参考时刻所在业务自然日**判定「当天是否已到点」：
     *   · 截止口径 = 该天的每日截止时刻（[absoluteDeadlineMillisOf] 折算，到点前不超时）；
     *   · 排定时间段只约束**它自己那一天**（与 homework 的
     *     [HomeworkValidators.validateScheduleWithinDailyDeadline]「以开始时刻所在自然日为该天」一致），
     *     因此在别的日子不参与判定——否则一条跨多天的阶段作业会被一个早已过去的排定时间段恒判超时；
     *   · 到点后仅作**提醒/逾期标识**，**不锁定**（当天内仍可完成，锁与不锁由仓库/UI 决定）；
     *   · 跨入次日按新一天的截止时刻重新判定，因此**不会恒判超时**。
     *   「当天是否算未完成（缺卡）」由 homework 的 [StageDayRecords] 投影表达，本函数不承担该语义。
     *
     * @param zoneId 业务时区：既用于折算参考时刻的业务自然日，也用于把每日时刻落到该天的瞬时。
     *   **必须由调用方显式给出**（不提供系统时区默认值）：全应用唯一的业务时区来源是 core 的
     *   `DailyRecordModule.provideBusinessZoneId`，生产由 Hilt 注入该绑定，测试显式传固定时区；
     *   timer 内自建 `ZoneId.systemDefault()` 兜底会让跨零点的「今天」口径与 homework / core 漂移。
     */
    fun isHomeworkOverdue(
        item: HomeworkItem,
        session: TimerSession?,
        nowMillis: Long,
        zoneId: ZoneId,
    ): Boolean {
        val referenceMillis = session?.let { referenceMillisOf(it, nowMillis) } ?: nowMillis
        val referenceEpochDay = epochDayOf(referenceMillis, zoneId)
        // 排定时间段是否属于「参考时刻那一天」：TODAY 恒为真（绝对时间段语义），
        // STAGE 仅在排定开始时刻与参考时刻同一业务自然日时参与判定
        val slotApplies = !item.isStage || item.startTime?.let {
            epochDayOf(it.toEpochMilli(), zoneId) == referenceEpochDay
        } == true
        return isOverdue(
            startTimeMillis = item.startTime?.toEpochMilli().takeIf { slotApplies },
            estimatedMinutes = item.estimatedMinutes.takeIf { slotApplies },
            deadlineMillis = absoluteDeadlineMillisOf(item, referenceEpochDay, zoneId),
            referenceMillis = referenceMillis,
        )
    }

    /**
     * 时刻 → 业务自然日（epochDay）：与 homework / core 的「作业每天详情」同一折算入口，
     * 禁止 `millis / 86_400_000` 形式的 UTC 折算（UTC+8 凌晨会取到昨天）。
     */
    private fun epochDayOf(millis: Long, zoneId: ZoneId): Long =
        HomeworkValidators.epochDayOf(millis, zoneId)

    // ---- 4. 下一项选取与休息倒计时 ----

    /** 可开始（未完成且可进入计时）的作业状态：待完成与进行中 */
    val STARTABLE_STATUSES: Set<HomeworkStatus> = setOf(HomeworkStatus.PENDING, HomeworkStatus.IN_PROGRESS)

    /**
     * 下一项选取：按清单优先级顺序（priority 升序，同优先级按创建时间升序）取下一条
     * 待完成/进行中的作业；清单已无未完成项时返回 null（调用方据此进入完成反馈页）。
     *
     * @param excludeHomeworkId 需排除的作业 id（如刚完成的那一项，避免回退到自身）
     */
    fun pickNextItem(
        items: List<HomeworkItem>,
        excludeHomeworkId: Long? = null,
    ): HomeworkItem? = items
        .filter { it.status in STARTABLE_STATUSES && it.id != excludeHomeworkId }
        .minWithOrNull(compareBy<HomeworkItem> { it.priority }.thenBy { it.createdAt })

    /** 剩余未完成（待完成/进行中）作业数量 */
    fun remainingCount(items: List<HomeworkItem>): Int =
        items.count { it.status in STARTABLE_STATUSES }

    /** 已完成作业数量（完成反馈页「今天完成了 X / Y 项」的分子） */
    fun completedCount(items: List<HomeworkItem>): Int =
        items.count { it.status == HomeworkStatus.COMPLETED }

    /**
     * 休息倒计时剩余毫秒：`休息开始时刻 + 休息时长 - 现在`，结束时收敛为 0。
     * 休息时长默认 [TimerConstants.REST_DURATION_MILLIS]（10 分钟），可显式传入便于测试。
     */
    fun restRemainingMillis(
        restStartedAtMillis: Long,
        nowMillis: Long,
        durationMillis: Long = TimerConstants.REST_DURATION_MILLIS,
    ): Long = (restStartedAtMillis + durationMillis - nowMillis).coerceAtLeast(0L)

    /** 休息是否已结束（剩余为 0） */
    fun isRestFinished(
        restStartedAtMillis: Long,
        nowMillis: Long,
        durationMillis: Long = TimerConstants.REST_DURATION_MILLIS,
    ): Boolean = restRemainingMillis(restStartedAtMillis, nowMillis, durationMillis) == 0L

    /**
     * 倒计时展示秒数：剩余毫秒向上取整到秒（避免「还剩 1 秒」时因取整直接显示 00:00）。
     */
    fun displaySeconds(millis: Long): Long =
        if (millis <= 0L) 0L else (millis - 1L) / TimerConstants.MILLIS_PER_SECOND + 1L
}
