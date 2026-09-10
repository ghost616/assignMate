package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus

/**
 * 计时纯函数计算与规则集合（无副作用、无 IO、时间由调用方传入，集中可单测）。
 *
 * 覆盖四类业务规则：
 * 1. 已用时长：`当前时刻 - 开始时刻 - 暂停累计`（负数收敛为 0，容忍时钟回拨）；
 * 2. 暂停汇总：累计暂停毫秒（含未结束暂停按入参时刻折算）与暂停次数；
 * 3. 超时判定：超过「开始时间 + 预估时长（+ 宽限）」或超过 deadline 仍未完成；
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
     * 是否超时：超过「开始时间 + 预估时长 + 宽限」或超过 deadline 仍未完成。
     *
     * 口径（与 [TimerConstants.OVERDUE_GRACE_MINUTES] 一致）：
     * - 仅当开始时间与预估时长都存在时才做预估超时判定（未排定时间不判超时）；
     * - deadline 单独成立即可判定超时（阶段作业有 deadline 但可能未排定开始时间）；
     * - 恰好在边界时刻（等于预估完成时刻）不算超时，超过 1 毫秒即算。
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
     * 作业当前是否超时：已完成会话以会话结束时刻为参考（判定「本次是否超时」），
     * 未开始/进行中以「现在」为参考。
     */
    fun isHomeworkOverdue(item: HomeworkItem, session: TimerSession?, nowMillis: Long): Boolean =
        isOverdue(
            startTimeMillis = item.startTime?.toEpochMilli(),
            estimatedMinutes = item.estimatedMinutes,
            deadlineMillis = item.deadline?.toEpochMilli(),
            referenceMillis = session?.let { referenceMillisOf(it, nowMillis) } ?: nowMillis,
        )

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
