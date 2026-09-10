package com.assignmate.app.timer.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.data.db.dao.PauseRecordDao
import com.assignmate.app.core.data.db.dao.TimerSessionDao
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.timer.domain.PauseRecord
import com.assignmate.app.timer.domain.TimerCalculations
import com.assignmate.app.timer.domain.TimerPhase
import com.assignmate.app.timer.domain.TimerSession
import com.assignmate.app.timer.domain.TimerSessionDetail
import com.assignmate.app.timer.domain.TimerSessionSummary
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TimerRepository] 默认实现：基于 core 的 [TimerSessionDao] / [PauseRecordDao]（Room）、
 * homework 的状态流转入口与可注入 [Clock]。
 *
 * 关键规则（仓库层兜底防绕过 UI）：
 * - 会话状态机：RUNNING →(暂停) PAUSED →(恢复) RUNNING →(完成) FINISHED；
 *   非法阶段一律返回结果对象中的原因，不抛异常、不改动数据；
 * - 暂停明细：暂停时插入 pause_end_at 为 null 的明细；恢复/完成时补齐结束时刻，
 *   并把「累计暂停时长 + 暂停次数」汇总写入会话（明细为权威口径，汇总仅为快照）；
 * - 作业同步顺序：完成时先调 homework.complete（幂等），成功后再收尾会话——
 *   万一收尾失败，留下的是「作业已完成、会话未收尾」这一可重试状态；
 * - 权限：执行权与 homework 同源（[HomeworkValidators.canOperate]），
 *   角色取当前会话（authoritative），作业状态流转调用使用入参 [Role]；
 * - 时间：统一取 [Clock]，测试注入固定时钟即可确定性验证暂停时长与超时口径；
 * - 事务边界：凡是「多次 DAO 写必须一起成功」的场景（暂停落明细 + 置状态、完成时结束时刻 + 暂停汇总、
 *   恢复时清理重复暂停 + 收尾 + 更新汇总）统一经 [TimerTransactionRunner] 收敛为一次原子提交，
 *   避免中断留下「会话仍 RUNNING + 未结束暂停明细」这类不一致数据（会导致累计暂停虚高、次数虚增）；
 * - 落库状态统一走 [TimerPhase.persistedName]：页面级阶段（IDLE/RESTING）若被写入会当场抛出，
 *   把「库内 status 取值域」从人工约定变成可执行约束。
 */
@Singleton
class TimerRepositoryImpl @Inject constructor(
    private val timerSessionDao: TimerSessionDao,
    private val pauseRecordDao: PauseRecordDao,
    private val homeworkRepository: HomeworkRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val transactionRunner: TimerTransactionRunner,
) : TimerRepository {

    // ---- 计时执行 ----

    override suspend fun startSession(homeworkId: Long, sessionRole: Role): TimerStartResult {
        val session = authRepository.currentSession()
        val role = session.role ?: return TimerStartResult.NoActiveSession
        val homework = homeworkRepository.getHomework(homeworkId)
            ?: return TimerStartResult.HomeworkNotFound
        // 执行权：学生仅可对本人名下作业计时（含家长布置的），家长可对名下学生作业计时
        if (!HomeworkValidators.canOperate(homework, role, session.studentId)) {
            return TimerStartResult.PermissionDenied
        }
        // 幂等：已有未结束会话直接复用（页面返回/重建不重复建会话、不打断走秒）
        findActiveSessionByHomework(homeworkId)?.let { return TimerStartResult.Success(it) }
        if (homework.status !in TimerCalculations.STARTABLE_STATUSES) {
            // 「已记录」须先排定时间，「已完成」不可再开始（需先撤销完成）
            return TimerStartResult.NotStartable(homework.status)
        }
        val synced = homeworkRepository.startProgress(homeworkId, sessionRole)
        if (synced !is HomeworkStatusResult.Success) {
            return TimerStartResult.HomeworkSyncFailed(synced)
        }
        val entity = TimerSessionEntity(
            homeworkId = homeworkId,
            studentId = homework.studentId,
            parentAccountId = homework.parentAccountId,
            startedAt = Instant.ofEpochMilli(clock.currentTimeMillis()),
            finishedAt = null,
            pausedTotalMillis = 0L,
            pauseCount = 0,
            status = TimerPhase.RUNNING.persistedName,
        )
        val id = timerSessionDao.insert(entity)
        return TimerStartResult.Success(entity.copy(id = id).toDomain())
    }

    override suspend fun pauseSession(sessionId: Long): TimerPauseResult {
        val entity = timerSessionDao.findById(sessionId) ?: return TimerPauseResult.SessionNotFound
        val phase = TimerPhase.fromSessionStatus(entity.status)
        if (phase != TimerPhase.RUNNING) {
            // 重复暂停 / 已完成会话暂停均被拒绝，且不写入任何明细
            return TimerPauseResult.IllegalPhase(phase)
        }
        val now = clock.currentTimeMillis()
        // 事务化：明细与状态要么一起成功、要么都不写（中断不会留下「RUNNING + 未结束明细」）
        transactionRunner.inTransaction {
            pauseRecordDao.insert(
                PauseRecordEntity(
                    sessionId = sessionId,
                    homeworkId = entity.homeworkId,
                    pauseStartAt = Instant.ofEpochMilli(now),
                    pauseEndAt = null,
                ),
            )
            timerSessionDao.updateStatus(sessionId, TimerPhase.PAUSED.persistedName)
        }
        return TimerPauseResult.Success(entity.copy(status = TimerPhase.PAUSED.persistedName).toDomain())
    }

    override suspend fun resumeSession(sessionId: Long): TimerResumeResult {
        val entity = timerSessionDao.findById(sessionId) ?: return TimerResumeResult.SessionNotFound
        val phase = TimerPhase.fromSessionStatus(entity.status)
        if (phase != TimerPhase.PAUSED) {
            return TimerResumeResult.IllegalPhase(phase)
        }
        val now = clock.currentTimeMillis()
        // 事务化：先清理历史中断可能留下的重复未结束暂停，再收尾当前暂停并刷新汇总（一次原子提交）
        return transactionRunner.inTransaction {
            cleanupUnfinishedPauses(sessionId)
            val ongoing = pauseRecordDao.findUnfinishedBySession(sessionId)
                ?: return@inTransaction TimerResumeResult.PauseRecordMissing
            pauseRecordDao.finishPause(ongoing.id, now)
            val pauses = pauseRecordDao.loadBySession(sessionId).map { it.toDomain() }
            val pausedTotal = TimerCalculations.pauseAccumulatedMillis(pauses, now)
            timerSessionDao.updatePauseSummary(
                sessionId = sessionId,
                pausedTotalMillis = pausedTotal,
                pauseCount = pauses.size,
                status = TimerPhase.RUNNING.persistedName,
            )
            TimerResumeResult.Success(
                entity.copy(
                    pausedTotalMillis = pausedTotal,
                    pauseCount = pauses.size,
                    status = TimerPhase.RUNNING.persistedName,
                ).toDomain(),
            )
        }
    }

    override suspend fun completeSession(sessionId: Long, sessionRole: Role): TimerCompleteResult {
        val entity = timerSessionDao.findById(sessionId) ?: return TimerCompleteResult.SessionNotFound
        val phase = TimerPhase.fromSessionStatus(entity.status)
        if (!phase.isActive) {
            return TimerCompleteResult.IllegalPhase(phase)
        }
        // 先同步作业状态（已完成作业重复调用为幂等成功），成功后再收尾会话
        val synced = homeworkRepository.complete(entity.homeworkId, sessionRole)
        if (synced !is HomeworkStatusResult.Success) {
            return TimerCompleteResult.HomeworkSyncFailed(synced)
        }
        val now = clock.currentTimeMillis()
        // 事务化收尾：收尾未结束暂停 + 写结束时刻 + 刷新暂停汇总，一次原子提交
        return transactionRunner.inTransaction {
            // 先清理历史中断留下的重复未结束明细，再收尾唯一一条未结束暂停，避免统计口径被污染
            cleanupUnfinishedPauses(sessionId)
            pauseRecordDao.findUnfinishedBySession(sessionId)?.let { pauseRecordDao.finishPause(it.id, now) }
            val pauses = pauseRecordDao.loadBySession(sessionId).map { it.toDomain() }
            val pausedTotal = TimerCalculations.pauseAccumulatedMillis(pauses, now)
            val pauseCount = pauses.size
            timerSessionDao.updateFinish(sessionId, now, TimerPhase.FINISHED.persistedName)
            timerSessionDao.updatePauseSummary(
                sessionId = sessionId,
                pausedTotalMillis = pausedTotal,
                pauseCount = pauseCount,
                status = TimerPhase.FINISHED.persistedName,
            )
            TimerCompleteResult.Success(
                session = entity.copy(
                    finishedAt = Instant.ofEpochMilli(now),
                    pausedTotalMillis = pausedTotal,
                    pauseCount = pauseCount,
                    status = TimerPhase.FINISHED.persistedName,
                ).toDomain(),
                homework = synced.item,
            )
        }
    }

    /**
     * 清理同一会话中重复的「未结束暂停」明细（历史中断留下的不一致数据：会话仍 RUNNING 却留下未结束明细等）。
     *
     * 规则：保留**最早开始**的一条作为真实暂停（那是孩子真正「走开」的时刻），
     * 其余重复记录**直接删除**——它们不是真实发生的暂停，保留会虚增暂停次数、
     * 也会让累计暂停时长被重复计入。至多一条未结束时不做任何写入。
     *
     * 调用时机：进入恢复流程与完成收尾流程（均在事务内），保证汇总只反映真实暂停。
     */
    private suspend fun cleanupUnfinishedPauses(sessionId: Long) {
        val unfinished = pauseRecordDao.loadBySession(sessionId).filter { it.pauseEndAt == null }
        if (unfinished.size <= 1) {
            return
        }
        val keep = unfinished.minByOrNull { it.pauseStartAt } ?: return
        unfinished
            .filter { it.id != keep.id }
            .forEach { extra -> pauseRecordDao.delete(extra) }
    }

    // ---- 执行过程查询 ----

    override suspend fun loadSession(sessionId: Long): TimerSession? =
        timerSessionDao.findById(sessionId)?.toDomain()

    override suspend fun loadSessionDetail(sessionId: Long): TimerSessionDetail? {
        val session = timerSessionDao.findById(sessionId)?.toDomain() ?: return null
        return TimerSessionDetail(session = session, pauses = loadPauses(sessionId))
    }

    override suspend fun findActiveSession(studentId: Long): TimerSession? {
        val running = timerSessionDao.loadByStudentAndStatus(studentId, TimerPhase.RUNNING.persistedName)
        val paused = timerSessionDao.loadByStudentAndStatus(studentId, TimerPhase.PAUSED.persistedName)
        return (running + paused).maxByOrNull { it.startedAt }?.toDomain()
    }

    override suspend fun findActiveSessionByHomework(homeworkId: Long): TimerSession? =
        timerSessionDao.loadByHomework(homeworkId)
            .filter { TimerPhase.fromSessionStatus(it.status).isActive }
            .maxByOrNull { it.startedAt }
            ?.toDomain()

    override suspend fun findLatestSession(homeworkId: Long): TimerSession? =
        timerSessionDao.loadByHomework(homeworkId).lastOrNull()?.toDomain()

    override suspend fun loadSessionsByHomework(homeworkId: Long): List<TimerSession> =
        timerSessionDao.loadByHomework(homeworkId).map { it.toDomain() }

    override suspend fun loadSessionsByStudent(studentId: Long): List<TimerSession> =
        timerSessionDao.loadByStudent(studentId).map { it.toDomain() }

    override suspend fun loadPauses(sessionId: Long): List<PauseRecord> =
        pauseRecordDao.loadBySession(sessionId).map { it.toDomain() }

    override suspend fun loadPausesByHomework(homeworkId: Long): List<PauseRecord> =
        pauseRecordDao.loadByHomework(homeworkId).map { it.toDomain() }

    override suspend fun summarize(sessionId: Long): TimerSessionSummary? {
        val session = timerSessionDao.findById(sessionId)?.toDomain() ?: return null
        return TimerCalculations.summarize(session, loadPauses(sessionId), clock.currentTimeMillis())
    }

    // ---- 实体 <-> 领域模型 ----

    private fun TimerSessionEntity.toDomain(): TimerSession = TimerSession(
        id = id,
        homeworkId = homeworkId,
        studentId = studentId,
        parentAccountId = parentAccountId,
        startedAt = startedAt,
        finishedAt = finishedAt,
        pausedTotalMillis = pausedTotalMillis,
        pauseCount = pauseCount,
        phase = TimerPhase.fromSessionStatus(status),
    )

    private fun PauseRecordEntity.toDomain(): PauseRecord = PauseRecord(
        id = id,
        sessionId = sessionId,
        homeworkId = homeworkId,
        pauseStartAt = pauseStartAt,
        pauseEndAt = pauseEndAt,
    )
}
