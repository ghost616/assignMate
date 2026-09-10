package com.assignmate.app.timer.domain

import java.time.Instant

/**
 * 计时执行会话领域模型（core 的 timer_session 表互转）。
 *
 * 语义：一次作业的「开始计时 → 完成」过程对应一条会话；会话内的每次暂停以多条
 * [PauseRecord] 明细表达，本模型携带的 [pausedTotalMillis] / [pauseCount] 是会话级汇总快照，
 * 实时口径一律以暂停明细经 [TimerCalculations] 重算为准（明细为权威来源）。
 *
 * 时间字段：未结束会话 [finishedAt] 为 null，[TimerPhase.isActive] 为 true。
 */
data class TimerSession(
    val id: Long,
    val homeworkId: Long,
    val studentId: Long,
    val parentAccountId: Long,
    /** 计时开始时刻 */
    val startedAt: Instant,
    /** 计时结束时刻；会话未结束时为 null */
    val finishedAt: Instant? = null,
    /** 累计暂停毫秒数（汇总快照，默认 0） */
    val pausedTotalMillis: Long = 0L,
    /** 累计暂停次数（汇总快照，默认 0） */
    val pauseCount: Int = 0,
    /** 会话阶段：RUNNING / PAUSED / FINISHED */
    val phase: TimerPhase,
) {

    /** 会话是否未收尾（进行中或暂停中） */
    val isActive: Boolean get() = phase.isActive

    /**
     * 落库用的状态字符串：走 [TimerPhase.persistedName]，
     * 因此把「页面级阶段」（IDLE/RESTING）写库会在源头直接抛出，而不是静默落一个非法值。
     */
    val statusName: String get() = phase.persistedName
}

/**
 * 暂停明细领域模型（core 的 pause_record 表互转）。
 *
 * 「暂停中」表现为 [pauseEndAt] 为 null 的未结束记录；同一会话至多一条未结束记录，
 * 由 timer 状态机保证（重复暂停被拒绝）。
 */
data class PauseRecord(
    val id: Long,
    val sessionId: Long,
    val homeworkId: Long,
    /** 暂停开始时刻 */
    val pauseStartAt: Instant,
    /** 暂停结束时刻；仍处于暂停中时为 null */
    val pauseEndAt: Instant? = null,
) {

    /** 是否为未结束（进行中）的暂停 */
    val isOngoing: Boolean get() = pauseEndAt == null
}

/** 会话 + 暂停明细：供计时页恢复现场与 stats 模块读取执行过程 */
data class TimerSessionDetail(
    val session: TimerSession,
    val pauses: List<PauseRecord>,
)

/**
 * 会话执行小结：已用时长 / 累计暂停 / 暂停次数（统计口径统一由 [TimerCalculations] 计算，
 * 供计时页展示与 stats 盘点复用，避免两处口径漂移）。
 */
data class TimerSessionSummary(
    val sessionId: Long,
    val phase: TimerPhase,
    /** 已用时长（毫秒，已扣除暂停累计） */
    val elapsedMillis: Long,
    /** 累计暂停时长（毫秒，含尚未结束的暂停按入参时刻折算） */
    val pausedTotalMillis: Long,
    /** 累计暂停次数（含尚未结束的暂停） */
    val pauseCount: Int,
)
