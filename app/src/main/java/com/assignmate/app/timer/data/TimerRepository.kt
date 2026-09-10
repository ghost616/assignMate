package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.domain.PauseRecord
import com.assignmate.app.timer.domain.TimerPhase
import com.assignmate.app.timer.domain.TimerSession
import com.assignmate.app.timer.domain.TimerSessionDetail
import com.assignmate.app.timer.domain.TimerSessionSummary

/**
 * 计时仓库接口：作业执行会话的全生命周期（开始 / 暂停 / 恢复 / 完成）与执行过程查询。
 *
 * 实现契约（仓库层兜底，避免绕过 UI 直接调用导致规则失效）：
 * - 数据来源：core 的 timer_session / pause_record 两张表（DAO 只做数据访问，状态机在 timer 层）；
 * - 作业状态同步：开始计时经 homework 的 startProgress 把作业置「进行中」；
 *   完成作业经 homework 的 complete 把作业置「已完成」（先同步作业、再收尾会话，
 *   保证失败时留下「作业已完成、会话未收尾」这一可重试状态，而不是反向死局）；
 * - 权限：执行权与 homework 同源（[com.assignmate.app.homework.domain.HomeworkValidators.canOperate]），
 *   学生会话仅可操作本人名下作业，家长可操作名下学生作业；家长归属 id 取自 auth 当前会话；
 * - 状态机：暂停仅允许 RUNNING → PAUSED（会话内至多一条未结束暂停明细），
 *   恢复仅允许 PAUSED → RUNNING，完成仅允许 RUNNING/PAUSED → FINISHED；
 * - 时间：一律取可注入 [com.assignmate.app.core.domain.time.Clock]，
 *   便于测试注入固定时钟构造暂停/超时场景；
 * - 幂等：同一作业已有未结束会话时，[startSession] 直接复用而不重复建会话（页面返回/重建不重复计时）；
 * - 所有方法不抛业务异常，成败统一收敛为密封结果（原因机器可读，用户文案由 UI 层映射）。
 */
interface TimerRepository {

    // ---- 计时执行 ----

    /**
     * 开始计时：作业置「进行中」并写入一条 RUNNING 会话。
     *
     * 幂等：该作业已有未结束会话（RUNNING/PAUSED）时直接返回既有会话（不新建、不重复同步作业状态）。
     * 前置校验：会话有效、执行权、作业状态可开始（待完成/进行中）。
     */
    suspend fun startSession(homeworkId: Long, sessionRole: Role): TimerStartResult

    /** 暂停（有事走开）：写入暂停开始时刻并置会话为 PAUSED（仅 RUNNING 可暂停） */
    suspend fun pauseSession(sessionId: Long): TimerPauseResult

    /** 恢复（我回来啦）：补齐暂停结束时刻，并把累计暂停时长/次数写入会话汇总（仅 PAUSED 可恢复） */
    suspend fun resumeSession(sessionId: Long): TimerResumeResult

    /**
     * 完成作业：作业置「已完成」，会话写入结束时刻与暂停汇总（仅 RUNNING/PAUSED 可完成）。
     * 暂停中直接完成时，先收尾未结束的暂停明细，避免未结束记录污染统计。
     */
    suspend fun completeSession(sessionId: Long, sessionRole: Role): TimerCompleteResult

    // ---- 执行过程查询（计时页恢复现场 + 供 stats 统计） ----

    /** 按 id 读取会话（不存在返回 null） */
    suspend fun loadSession(sessionId: Long): TimerSession?

    /** 读取会话及其暂停明细（不存在返回 null），用于计时页恢复「已暂停次数/累计时长」现场 */
    suspend fun loadSessionDetail(sessionId: Long): TimerSessionDetail?

    /** 读取某学生当前未结束的会话（进行中优先，同为学生则取最近开始的一条）；无则返回 null */
    suspend fun findActiveSession(studentId: Long): TimerSession?

    /** 读取某作业当前未结束的会话（按开始时刻取最近一条）；无则返回 null */
    suspend fun findActiveSessionByHomework(homeworkId: Long): TimerSession?

    /** 读取某作业最近一次会话（含已结束的，供详情页展示「上次用时」） */
    suspend fun findLatestSession(homeworkId: Long): TimerSession?

    /** 读取某作业的全部会话（按开始时刻升序） */
    suspend fun loadSessionsByHomework(homeworkId: Long): List<TimerSession>

    /** 读取某学生的全部会话（按开始时刻升序），供按时段统计使用 */
    suspend fun loadSessionsByStudent(studentId: Long): List<TimerSession>

    /** 读取某会话的全部暂停明细（按暂停开始时刻升序） */
    suspend fun loadPauses(sessionId: Long): List<PauseRecord>

    /** 读取某作业的全部暂停明细（按暂停开始时刻升序），供按作业维度统计使用 */
    suspend fun loadPausesByHomework(homeworkId: Long): List<PauseRecord>

    /** 会话执行小结（已用时长/累计暂停/暂停次数，口径见 TimerCalculations）；会话不存在返回 null */
    suspend fun summarize(sessionId: Long): TimerSessionSummary?
}

/** 开始计时结果 */
sealed class TimerStartResult {

    /** 成功：返回可用于走秒的会话（可能是既有未结束会话，见接口幂等约定） */
    data class Success(val session: TimerSession) : TimerStartResult()

    /** 作业不存在（可能已被删除） */
    data object HomeworkNotFound : TimerStartResult()

    /** 无有效会话（未登录或缺少家长归属维度） */
    data object NoActiveSession : TimerStartResult()

    /** 权限不足（学生尝试对自己的非名下作业计时） */
    data object PermissionDenied : TimerStartResult()

    /** 作业当前状态不可开始（已记录需先排定时间；已完成不可再开始） */
    data class NotStartable(val status: HomeworkStatus) : TimerStartResult()

    /** 作业状态同步失败（如并发下作业已被改为其它状态），会话未创建 */
    data class HomeworkSyncFailed(val reason: HomeworkStatusResult) : TimerStartResult()
}

/** 暂停结果 */
sealed class TimerPauseResult {

    /** 成功：会话已置 PAUSED，暂停开始时刻已落明细 */
    data class Success(val session: TimerSession) : TimerPauseResult()

    /** 会话不存在 */
    data object SessionNotFound : TimerPauseResult()

    /** 阶段不合法（仅进行中的会话可暂停；重复暂停/已完成会话均被拒绝） */
    data class IllegalPhase(val phase: TimerPhase) : TimerPauseResult()
}

/** 恢复结果 */
sealed class TimerResumeResult {

    /** 成功：暂停已收尾，汇总（累计暂停时长/次数）已写入会话 */
    data class Success(val session: TimerSession) : TimerResumeResult()

    /** 会话不存在 */
    data object SessionNotFound : TimerResumeResult()

    /** 阶段不合法（仅暂停中的会话可恢复） */
    data class IllegalPhase(val phase: TimerPhase) : TimerResumeResult()

    /** 会话状态为暂停中但缺少未结束的暂停明细（数据不一致），不写入任何汇总 */
    data object PauseRecordMissing : TimerResumeResult()
}

/** 完成结果 */
sealed class TimerCompleteResult {

    /** 成功：会话已收尾（结束时刻 + 暂停汇总），作业已置已完成 */
    data class Success(
        val session: TimerSession,
        val homework: HomeworkItem,
    ) : TimerCompleteResult()

    /** 会话不存在 */
    data object SessionNotFound : TimerCompleteResult()

    /** 阶段不合法（仅进行中/暂停中的会话可完成；已完成会话重复完成被拒绝） */
    data class IllegalPhase(val phase: TimerPhase) : TimerCompleteResult()

    /** 作业状态同步失败（会话未收尾，可修正后重试） */
    data class HomeworkSyncFailed(val reason: HomeworkStatusResult) : TimerCompleteResult()
}
