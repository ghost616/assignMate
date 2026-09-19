package com.assignmate.app.timer.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.db.dao.PauseRecordDao
import com.assignmate.app.core.data.db.dao.TimerSessionDao
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.timer.domain.PauseRecord
import com.assignmate.app.timer.domain.TimerCalculations
import com.assignmate.app.timer.domain.TimerConstants
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
 * - 权限（写入口围栏，口径全模块统一）：
 *   1) 执行权与 homework 同源（[HomeworkValidators.canOperate]），角色取当前会话（authoritative），
 *      作业状态流转调用使用入参 [Role]；
 *   2) 归属围栏：家长仅限**名下学生**（经 auth 的 [AuthRepository.isStudentOwnedBy]，与 homework 共用同一口径），
 *      学生仅限本人名下；[startSession] 以作业所属学生为目标，暂停/恢复/完成以**会话库内所属 studentId** 为目标；
 *   3) 拒绝统一为各自结果的 `PermissionDenied`，且拒绝分支不产生任何写入
 *      （不建会话、不改作业状态、不写暂停明细、不改会话状态）；
 * - 时间：统一取 [Clock]，测试注入固定时钟即可确定性验证暂停时长与超时口径；
 * - 事务边界：凡是「多次 DAO 写必须一起成功」的场景（暂停落明细 + 置状态、完成时结束时刻 + 暂停汇总、
 *   恢复时清理重复暂停 + 收尾 + 更新汇总）统一经 [TimerTransactionRunner] 收敛为一次原子提交，
 *   避免中断留下「会话仍 RUNNING + 未结束暂停明细」这类不一致数据（会导致累计暂停虚高、次数虚增）；
 * - 落库状态统一走 [TimerPhase.persistedName]：页面级阶段（IDLE/RESTING）若被写入会当场抛出，
 *   把「库内 status 取值域」从人工约定变成可执行约束；
 * - **逐日归属（阶段作业改为「1 条 + 每天详情」后的口径）**：
 *   1) 归属日以**会话开始时刻**所在的业务自然日为准（`epochDay` 同时落到 timer_session 与 pause_record），
 *      跨过午夜的计时/暂停仍计入**开始那一天**，同一会话绝不会写两条不同天的详情；
 *   2) 四个动作（开始/暂停/恢复/完成）都把当日执行数据回写 core 的「作业每天详情」
 *      （开始时刻、预估/实际时长、暂停次数/暂停总时长、完成时刻；完成时状态置已完成），
 *      写入经 [HomeworkDailyRecordRepository] 领域接口，不直接碰 core 的 Room DAO；
 *   3) 当天详情的暂停与时长按**归属日**聚合（当日全部会话与暂停），因此同一天多次开始会累加而非互相覆盖；
 *   4) 跨天完成时，作业状态流转会按「此刻」写下第二天的完成记录，本模块随后把这条**不属于本会话**的记录
 *      回退为「未开始」（见 [rollbackForeignDayCompletion]），保证跨天会话只留下一条计入执行数据的归属日详情；
 *   5) 「到点不锁定、跨日才判未完成」由 homework 的 StageDayRecords 统一投影，本模块不做第二套缺卡判定：
 *      每日截止时刻到点只提醒（见 TimerReminderRules），当天仍可开始/完成并正常计入当天详情。
 */
@Singleton
class TimerRepositoryImpl @Inject constructor(
    private val timerSessionDao: TimerSessionDao,
    private val pauseRecordDao: PauseRecordDao,
    private val homeworkRepository: HomeworkRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val transactionRunner: TimerTransactionRunner,
    private val dailyRecordRepository: HomeworkDailyRecordRepository,
) : TimerRepository {

    // ---- 计时执行 ----

    override suspend fun startSession(homeworkId: Long, sessionRole: Role): TimerStartResult {
        val session = authRepository.currentSession()
        // 无有效会话与越权分别映射为不同结果：前者提示「重新进入」，后者提示「这项作业不是你负责的」
        if (session.role == null) {
            return TimerStartResult.NoActiveSession
        }
        val homework = homeworkRepository.getHomework(homeworkId)
            ?: return TimerStartResult.HomeworkNotFound
        // 执行权（与 homework 同源：学生仅可对本人名下作业计时）+ 家长归属围栏（家长仅可对名下学生的作业计时），
        // 越权时不建会话、不改作业状态
        if (!canOperateHomework(homework, session)) {
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
        val startedAtMillis = clock.currentTimeMillis()
        // 归属日 = 会话开始时刻所在业务自然日（后续暂停/恢复/完成一律沿用该日，跨天不分裂）
        val epochDay = dailyRecordRepository.epochDayOf(startedAtMillis)
        val entity = TimerSessionEntity(
            homeworkId = homeworkId,
            studentId = homework.studentId,
            parentAccountId = homework.parentAccountId,
            epochDay = epochDay,
            startedAt = Instant.ofEpochMilli(startedAtMillis),
            finishedAt = null,
            pausedTotalMillis = 0L,
            pauseCount = 0,
            status = TimerPhase.RUNNING.persistedName,
        )
        // 事务化：会话落库 + 当天详情回写要么一起成功，避免「有会话但当天详情空白」
        return transactionRunner.inTransaction {
            val id = timerSessionDao.insert(entity)
            val created = entity.copy(id = id)
            syncDayRecord(created, HomeworkDayStatus.IN_PROGRESS, homework.estimatedMinutes, startedAtMillis)
            TimerStartResult.Success(created.toDomain())
        }
    }

    override suspend fun pauseSession(sessionId: Long): TimerPauseResult {
        val entity = timerSessionDao.findById(sessionId) ?: return TimerPauseResult.SessionNotFound
        // 归属围栏先于阶段判定：越权调用无论会话处于什么阶段都一律拒绝，且不写入任何数据
        if (!canOperateSession(entity)) {
            return TimerPauseResult.PermissionDenied
        }
        val phase = TimerPhase.fromSessionStatus(entity.status)
        if (phase != TimerPhase.RUNNING) {
            // 重复暂停 / 已完成会话暂停均被拒绝，且不写入任何明细
            return TimerPauseResult.IllegalPhase(phase)
        }
        val now = clock.currentTimeMillis()
        // 暂停明细的归属日取**会话归属日**而非暂停时刻所在日：跨过午夜的暂停仍归属到开始那一天
        val epochDay = entity.attributedEpochDay()
        // 事务化：明细 + 状态 + 当天详情（暂停次数/暂停总时长）要么一起成功、要么都不写
        // （中断不会留下「RUNNING + 未结束明细」，也不会留下与明细不一致的当天汇总）
        transactionRunner.inTransaction {
            pauseRecordDao.insert(
                PauseRecordEntity(
                    sessionId = sessionId,
                    homeworkId = entity.homeworkId,
                    epochDay = epochDay,
                    pauseStartAt = Instant.ofEpochMilli(now),
                    pauseEndAt = null,
                ),
            )
            timerSessionDao.updateStatus(sessionId, TimerPhase.PAUSED.persistedName)
            syncDayRecord(entity, HomeworkDayStatus.IN_PROGRESS, estimatedMinutes = null, nowMillis = now)
        }
        return TimerPauseResult.Success(
            entity.copy(status = TimerPhase.PAUSED.persistedName, epochDay = epochDay).toDomain(),
        )
    }

    override suspend fun resumeSession(sessionId: Long): TimerResumeResult {
        val entity = timerSessionDao.findById(sessionId) ?: return TimerResumeResult.SessionNotFound
        // 归属围栏先于阶段判定：越权调用不结束暂停明细、不刷新汇总、不改状态
        if (!canOperateSession(entity)) {
            return TimerResumeResult.PermissionDenied
        }
        val phase = TimerPhase.fromSessionStatus(entity.status)
        if (phase != TimerPhase.PAUSED) {
            return TimerResumeResult.IllegalPhase(phase)
        }
        val now = clock.currentTimeMillis()
        val epochDay = entity.attributedEpochDay()
        // 事务化：先清理历史中断可能留下的重复未结束暂停，再收尾当前暂停并刷新汇总、回写当天详情（一次原子提交）
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
            syncDayRecord(entity, HomeworkDayStatus.IN_PROGRESS, estimatedMinutes = null, nowMillis = now)
            TimerResumeResult.Success(
                entity.copy(
                    pausedTotalMillis = pausedTotal,
                    pauseCount = pauses.size,
                    status = TimerPhase.RUNNING.persistedName,
                    epochDay = epochDay,
                ).toDomain(),
            )
        }
    }

    override suspend fun completeSession(sessionId: Long, sessionRole: Role): TimerCompleteResult {
        val entity = timerSessionDao.findById(sessionId) ?: return TimerCompleteResult.SessionNotFound
        // 入口归属围栏（不再只依赖下游 homework.complete 间接拦截）：越权时不收尾会话、也不同步作业状态
        if (!canOperateSession(entity)) {
            return TimerCompleteResult.PermissionDenied
        }
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
        val epochDay = entity.attributedEpochDay()
        // 事务化收尾：收尾未结束暂停 + 写结束时刻 + 刷新暂停汇总 + 当天详情置已完成并回写实际时长，一次原子提交
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
            // 当天详情必须以「已完成 + 实际时长 + 完成时刻」收口（归属日仍是会话开始那一天）
            syncDayRecord(
                session = entity,
                status = HomeworkDayStatus.COMPLETED,
                estimatedMinutes = synced.item.estimatedMinutes,
                nowMillis = now,
            )
            // 跨天补偿：作业状态流转会按「此刻」写当天详情，跨天会话不应把第二天也记成完成
            rollbackForeignDayCompletion(entity, now)
            TimerCompleteResult.Success(
                session = entity.copy(
                    finishedAt = Instant.ofEpochMilli(now),
                    pausedTotalMillis = pausedTotal,
                    pauseCount = pauseCount,
                    status = TimerPhase.FINISHED.persistedName,
                    epochDay = epochDay,
                ).toDomain(),
                homework = synced.item,
            )
        }
    }

    // ---- 逐日归属：「作业每天详情」回写（口径见类注释） ----

    /**
     * 会话归属的业务自然日：优先取库内已落库的 `epoch_day`；为未指定哨兵
     * （[TimerConstants.UNSPECIFIED_EPOCH_DAY]，仅 v4 旧库迁移行会出现）时按**会话开始时刻**业务时区重新折算，
     * 从而让历史行也能正确落到归属日，而不是写出一条 epoch_day = 0 的垃圾详情。
     */
    private fun TimerSessionEntity.attributedEpochDay(): Long =
        epochDay.takeIf { it != TimerConstants.UNSPECIFIED_EPOCH_DAY }
            ?: dailyRecordRepository.epochDayOf(startedAt.toEpochMilli())

    /**
     * 把会话的当日执行数据回写「作业每天详情」（core 领域接口，不经 Room DAO）。
     *
     * 关键口径：
     * - 归属日取 [attributedEpochDay]（会话开始日），故跨天会话不会写第二天；
     * - 当日数据按**归属日内该作业的全部会话与暂停**聚合，因此同一天多次开始会累加而不是互相覆盖
     *   （第二天的新会话归属到第二天，自然与第一天互不影响）；
     * - [estimatedMinutes] 传 null 表示「沿用当天详情既有预估」（暂停/恢复不改预估）；
     * - 实际时长仅在当天有会话收尾后才写（否则保持 null，避免把进行中的时间记成实际时长）。
     */
    private suspend fun syncDayRecord(
        session: TimerSessionEntity,
        status: HomeworkDayStatus,
        estimatedMinutes: Int?,
        nowMillis: Long,
    ) {
        val epochDay = session.attributedEpochDay()
        val existing = dailyRecordRepository.find(session.homeworkId, epochDay)
        val daySessions = timerSessionDao.loadByHomeworkAndDay(session.homeworkId, epochDay)
            .ifEmpty { listOfNotNull(timerSessionDao.findById(session.id)) }
        val daySessionIds = daySessions.map { it.id }.toSet()
        val dayPauses = pauseRecordDao.loadByHomeworkAndDay(session.homeworkId, epochDay)
            .filter { it.sessionId in daySessionIds }
            .map { it.toDomain() }
        val recordId = dailyRecordRepository.upsertStatus(
            homeworkId = session.homeworkId,
            studentId = session.studentId,
            epochDay = epochDay,
            status = status,
            nowMillis = nowMillis,
        )
        dailyRecordRepository.updateExecution(
            id = recordId,
            startedAtMillis = dayStartedAtMillisOf(daySessions, session),
            estimatedMinutes = estimatedMinutes ?: existing?.estimatedMinutes,
            actualMinutes = dayActualMinutesOf(daySessions),
            pauseCount = TimerCalculations.pauseCount(dayPauses),
            pausedTotalMinutes = TimerCalculations.minutesOf(
                TimerCalculations.pauseAccumulatedMillis(dayPauses, nowMillis),
            ),
            finishedAtMillis = daySessions.mapNotNull { it.finishedAt?.toEpochMilli() }.maxOrNull(),
        )
    }

    /**
     * 当天详情的「开始时刻」：取当天全部会话中**最早**的开始时刻（同一天多次开始不会把起点推后）。
     *
     * 空列表安全兜底（修复轮 #8）：原实现用 `minOf`，在「当天查不到会话且自身会话也查不到」
     * （DAO 异常/数据被并发清理）这一极端空列表下会抛 `NoSuchElementException`，把一次详情回写变成崩溃。
     * 改为 `minOfOrNull` 并以**当前会话的开始时刻**兜底：既不抛异常，写下的也是这次执行的真实起点。
     */
    private fun dayStartedAtMillisOf(
        daySessions: List<TimerSessionEntity>,
        session: TimerSessionEntity,
    ): Long = daySessions.minOfOrNull { it.startedAt.toEpochMilli() }
        ?: session.startedAt.toEpochMilli()

    /**
     * 当天实际时长（分钟）：对当天**已收尾**的会话求和「结束时刻 − 开始时刻 − 暂停累计」后向上取整；
     * 当天尚无会话收尾时返回 null（不写实际时长）。
     */
    private fun dayActualMinutesOf(daySessions: List<TimerSessionEntity>): Int? {
        val finished = daySessions.filter { it.finishedAt != null }
        if (finished.isEmpty()) {
            return null
        }
        val elapsedMillis = finished.sumOf { session ->
            (session.finishedAt!!.toEpochMilli() - session.startedAt.toEpochMilli() - session.pausedTotalMillis)
                .coerceAtLeast(0L)
        }
        return TimerCalculations.minutesOf(elapsedMillis)
    }

    /**
     * 跨天补偿：把「完成时刻所在日」上由**作业状态流转**写下的完成记录回退为「未开始」。
     *
     * 背景：`homework.complete` 会把「此刻」所在自然日的每天详情置为已完成，而本模块的归属口径是
     * **会话开始日**——跨过午夜完成时（例：23:50 开始、次日 00:10 完成）就会多出一条「第二天已完成」，
     * 使这一天的作业被误判成已完成（孩子第二天其实还没做）。故此时把第二天那条记录回退为「未开始」，
     * 让跨天会话只留下**一条计入执行数据的归属日详情**，第二天保持「未开始/仍可完成」。
     *
     * 安全边界（避免误伤真实数据）：仅当「完成时刻所在日 ≠ 会话归属日」、该日记录当前为「已完成」、
     * 且该日在 timer_session 里**没有任何会话**（说明它并非孩子真实做过的一天）时才回退；
     * 回退同时清空执行数据（完成时刻等），状态回到「未开始」。
     */
    private suspend fun rollbackForeignDayCompletion(session: TimerSessionEntity, nowMillis: Long) {
        val completionDay = dailyRecordRepository.epochDayOf(nowMillis)
        if (completionDay == session.attributedEpochDay()) {
            return
        }
        val record = dailyRecordRepository.find(session.homeworkId, completionDay) ?: return
        if (record.status != HomeworkDayStatus.COMPLETED) {
            return
        }
        if (timerSessionDao.loadByHomeworkAndDay(session.homeworkId, completionDay).isNotEmpty()) {
            return
        }
        dailyRecordRepository.updateStatus(record.id, HomeworkDayStatus.NOT_STARTED)
        dailyRecordRepository.updateExecution(
            id = record.id,
            startedAtMillis = null,
            estimatedMinutes = null,
            actualMinutes = null,
            pauseCount = 0,
            pausedTotalMinutes = 0,
            finishedAtMillis = null,
        )
    }

    // ---- 权限围栏（写入口统一口径） ----

    /**
     * 作业是否可被当前会话 [session] 执行：执行权与归属围栏**两条判定缺一不可**。
     *
     * 为什么需要第二条：[HomeworkValidators.canOperate] 的家长分支恒为 true（「家长可操作名下学生的作业」），
     * 归属维度不参与其中，故家长会话对**任意学生**的作业都会通过执行权判定；
     * 由 [isStudentVisible] 经 auth 的 [AuthRepository.isStudentOwnedBy] 补齐「仅限名下学生」。
     * 学生分支两条判定语义相同（均为「本人名下」），保留 canOperate 是为了与 homework 保持同一执行权口径。
     */
    private suspend fun canOperateHomework(homework: HomeworkItem, session: SessionState): Boolean {
        val role = session.role ?: return false
        return HomeworkValidators.canOperate(homework, role, session.studentId) &&
            isStudentVisible(session, homework.studentId)
    }

    /**
     * 会话是否可被当前会话操作（暂停/恢复/完成的统一围栏）：
     * 目标归属取会话**库内所属 studentId**（权威数据，不信任调用方传入），而非入参角色。
     *
     * 拒绝场景：无有效会话（未登录/缺角色维度）、学生会话操作他人会话、家长会话操作非名下学生的会话。
     */
    private suspend fun canOperateSession(entity: TimerSessionEntity): Boolean =
        isStudentVisible(authRepository.currentSession(), entity.studentId)

    /**
     * 学生 [studentId] 是否在当前会话 [session] 的可见范围内（timer 模块归属判定的唯一口径）：
     * 学生会话仅限本人（`session.studentId == studentId`）；家长会话仅限名下学生（经 auth 的
     * [AuthRepository.isStudentOwnedBy]，与 homework 模块共用同一归属能力，避免口径分叉）；无会话一律不可见。
     *
     * 异常语义：不在此处用 runCatching 包裹——[AuthRepository.isStudentOwnedBy] 契约已把数据层异常收敛为 false
     * 并显式重抛 [kotlin.coroutines.cancellation.CancellationException]（结构化并发语义得以保留），
     * 外层再包一层 runCatching 反而可能吞掉协程取消。
     */
    private suspend fun isStudentVisible(session: SessionState, studentId: Long): Boolean {
        val parentId = session.parentId
        return when (session.role) {
            Role.STUDENT -> session.studentId == studentId
            Role.PARENT -> parentId != null && authRepository.isStudentOwnedBy(parentId, studentId)
            null -> false
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
        epochDay = epochDay,
    )

    private fun PauseRecordEntity.toDomain(): PauseRecord = PauseRecord(
        id = id,
        sessionId = sessionId,
        homeworkId = homeworkId,
        pauseStartAt = pauseStartAt,
        pauseEndAt = pauseEndAt,
        epochDay = epochDay,
    )
}
