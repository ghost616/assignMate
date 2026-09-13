package com.assignmate.app.stats.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.stats.domain.DaySummary
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.ItemDetail
import com.assignmate.app.stats.domain.PauseFact
import com.assignmate.app.stats.domain.StatsCalculations
import com.assignmate.app.stats.domain.TimerSessionFacts
import com.assignmate.app.timer.data.TimerRepository
import com.assignmate.app.timer.domain.PauseRecord
import com.assignmate.app.timer.domain.TimerSession
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StatsRepository] 默认实现：**只读聚合** homework 的作业清单/状态与 timer 的执行会话/暂停明细，
 * 不新增数据库表、不改动任何既有数据、不触发任何写操作。
 *
 * 关键约定：
 * - 归属与越权（与 homework/timer 同一口径）：目标学生由调用方显式传入，仓库层按当前会话兜底校验——
 *   学生会话仅可为本人，家长会话仅可为其名下学生（统一经 auth 的 [AuthRepository.isStudentOwnedBy] /
 *   [AuthRepository.ownedStudentIds] 判定，本模块不再自建等价的归属实现）；
 *   校验不通过统一返回 [StatsFailure.ACCESS_DENIED]，**不抛业务异常**、也不返回他人数据。
 * - 时间口径：所有时刻经可注入 [Clock] 取「现在」，业务自然日由注入的 [ZoneId] 决定
 *   （与 homework 模块绑定的业务时区同源，避免日期口径漂移）；聚合口径
 *   （当日纳入范围、完成率分母、暂停裁剪）集中在 [StatsCalculations] 纯函数中，
 *   并已在 [StatsRepository] KDoc 写明。
 * - 失败收敛：数据访问异常统一收敛为 [StatsResult.Failure]（[StatsFailure.READ_FAILED]），
 *   不向上抛异常。
 *
 * 性能取向：一次查询只读一遍数据（作业清单 + 该学生全部会话 + 涉及作业的暂停明细），
 * 再交给纯函数内存聚合；不按天/按作业重复访问数据库。
 */
@Singleton
class StatsRepositoryImpl @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val timerRepository: TimerRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : StatsRepository {

    override suspend fun summarizeDay(studentId: Long, epochDay: Long): StatsResult<DaySummary> {
        val access = checkAccess(studentId)
        if (access != null) {
            return StatsResult.Failure(access)
        }
        // 无执行/完成记录时返回空盘点（非失败）：页面据此展示空态而非错误提示
        return readSnapshot(studentId).map { snapshot ->
            StatsCalculations.summarizeDay(
                epochDay = epochDay,
                items = snapshot.items,
                sessions = snapshot.sessions,
                dayStartMillis = dayStartMillisOf(epochDay),
                dayEndMillis = dayStartMillisOf(epochDay + 1L),
                referenceMillis = clock.currentTimeMillis(),
            )
        }
    }

    override suspend fun itemDetail(homeworkId: Long): StatsResult<ItemDetail> {
        val session = authRepository.currentSession()
        if (session.role == null) {
            return StatsResult.Failure(StatsFailure.NO_ACTIVE_SESSION)
        }
        val item = runCatching { homeworkRepository.getHomework(homeworkId) }.getOrNull()
            ?: return StatsResult.Failure(StatsFailure.HOMEWORK_NOT_FOUND)
        if (!isStudentVisible(session, item.studentId)) {
            return StatsResult.Failure(StatsFailure.ACCESS_DENIED)
        }
        // 详情页以**作业归属**为准取数（作业的 studentId 即数据维度），此处显式校验「会话可见学生」
        // 与「作业归属学生」一致：路由学生与作业归属不符时收敛为 ACCESS_DENIED，
        // 避免静默按作业归属展示出与调用方预期不同的学生数据（可见学生经 auth 的
        // [AuthRepository.ownedStudentIds] 统一解析，多学生家长无法唯一确定时不做该校验）。
        val targetStudentId = resolveVisibleStudentId(session)
        if (targetStudentId != null && targetStudentId != item.studentId) {
            return StatsResult.Failure(StatsFailure.ACCESS_DENIED)
        }
        val facts = runCatching {
            val sessions = timerRepository.loadSessionsByStudent(item.studentId)
            val pauses = timerRepository.loadPausesByHomework(homeworkId)
            sessions.map { it.toFacts(pauses) }
        }.getOrElse {
            return StatsResult.Failure(StatsFailure.READ_FAILED)
        }
        return StatsResult.Success(
            StatsCalculations.itemDetail(
                item = item,
                sessions = facts,
                referenceMillis = clock.currentTimeMillis(),
            ),
        )
    }

    override suspend fun history(
        studentId: Long,
        query: HistoryQuery,
    ): StatsResult<List<DaySummary>> {
        if (!query.isValid) {
            return StatsResult.Failure(StatsFailure.INVALID_QUERY)
        }
        val access = checkAccess(studentId)
        if (access != null) {
            return StatsResult.Failure(access)
        }
        val normalized = query.normalized()
        return readSnapshot(studentId).map { snapshot ->
            StatsCalculations.summarizeRange(
                fromEpochDay = normalized.startEpochDay,
                toEpochDay = normalized.endEpochDay,
                items = snapshot.items,
                sessions = snapshot.sessions,
                referenceMillis = clock.currentTimeMillis(),
                dayStartMillisOf = ::dayStartMillisOf,
            )
        }
    }

    // ---- 取数 ----

    /** 读取某学生的作业清单与会话事实快照（读取失败收敛为 [StatsFailure.READ_FAILED]） */
    private suspend fun readSnapshot(studentId: Long): StatsResult<Snapshot> = runCatching {
        Snapshot(
            items = homeworkRepository.listHomework(studentId),
            sessions = sessionsOf(studentId),
        )
    }.fold(
        onSuccess = { StatsResult.Success(it) },
        onFailure = { StatsResult.Failure(StatsFailure.READ_FAILED) },
    )

    /**
     * 某学生的全部执行会话（含各自归属的暂停明细）。
     *
     * 暂停明细按作业维度一次性读取（与 timer 仓库 [TimerRepository.loadPausesByHomework] 同源口径），
     * 再把作业明细分发给该作业下的各条会话，避免「按会话逐条查询」产生 N+1 次数据库访问。
     */
    private suspend fun sessionsOf(studentId: Long): List<TimerSessionFacts> {
        val sessions = timerRepository.loadSessionsByStudent(studentId)
        if (sessions.isEmpty()) {
            return emptyList()
        }
        val pausesByHomework = sessions
            .map { it.homeworkId }
            .distinct()
            .associateWith { homeworkId -> timerRepository.loadPausesByHomework(homeworkId) }
        return sessions.map { session ->
            session.toFacts(pausesByHomework[session.homeworkId].orEmpty())
        }
    }

    // ---- 权限 ----

    /** 权限校验：通过返回 null，不通过返回对应失败原因 */
    private suspend fun checkAccess(studentId: Long): StatsFailure? {
        val session = authRepository.currentSession()
        if (session.role == null) {
            return StatsFailure.NO_ACTIVE_SESSION
        }
        return if (isStudentVisible(session, studentId)) null else StatsFailure.ACCESS_DENIED
    }

    /**
     * 学生 [studentId] 是否在当前会话 [session] 的可见范围内（本模块归属判定的唯一口径）：
     * 学生会话仅限本人（`session.studentId == studentId`）；家长会话仅限名下学生（经 auth 的
     * [AuthRepository.isStudentOwnedBy]，与 homework/timer 共用同一归属能力，替代本模块原先私有的
     * 等价实现）；无会话一律不可见。
     *
     * 异常语义：不在此处用 runCatching 包裹——[AuthRepository.isStudentOwnedBy] 契约已把数据层异常
     * 收敛为 false 并显式重抛 [kotlin.coroutines.cancellation.CancellationException]（结构化并发语义
     * 得以保留），外层再包一层 runCatching 反而可能吞掉协程取消，使取消信号被降级成 ACCESS_DENIED。
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
     * 当前会话**唯一确定**的可见学生 id：学生会话固定为本人；家长会话在仅有一名学生时可确定，
     * 多学生时无法从会话本身推断（需由调用方显式传学生维度），故返回 null（不做一致性校验）。
     *
     * 家长分支经 auth 的统一归属能力 [AuthRepository.ownedStudentIds] 取数（与 homework/timer 同源），
     * 其契约已把数据层异常收敛为空集并显式重抛协程取消异常，故此处无需再包 runCatching。
     */
    private suspend fun resolveVisibleStudentId(session: SessionState): Long? = when (session.role) {
        Role.STUDENT -> session.studentId
        Role.PARENT -> session.parentId?.let { parentId -> authRepository.ownedStudentIds(parentId).singleOrNull() }

        null -> null
    }

    // ---- 时间 ----

    /**
     * 某自然日 00:00 的 epoch 毫秒：直接委托 [StatsCalculations.dayStartMillisOf]，
     * 保证与各 ViewModel 的「今天」判定共用同一处正午偏移换算，避免两处独立实现漂移。
     */
    private fun dayStartMillisOf(epochDay: Long): Long = StatsCalculations.dayStartMillisOf(zoneId, epochDay)
}

/**
 * 结果映射：成功时转换数据，失败时原样传递（统计聚合链路常用，避免层层 when 判空）。
 */
internal fun <T, R> StatsResult<T>.map(transform: (T) -> R): StatsResult<R> = when (this) {
    is StatsResult.Success -> StatsResult.Success(transform(data))
    is StatsResult.Failure -> this
}

/** 取数快照：作业清单（按优先级升序）+ 该学生的会话事实 */
private data class Snapshot(
    val items: List<HomeworkItem>,
    val sessions: List<TimerSessionFacts>,
)

/**
 * 会话领域模型 -> 统计事实投影。
 * 暂停明细按 sessionId 归属：同一作业可能有多条会话，各会话只取属于自己的暂停段。
 */
private fun TimerSession.toFacts(homeworkPauses: List<PauseRecord>): TimerSessionFacts = TimerSessionFacts(
    sessionId = id,
    homeworkId = homeworkId,
    studentId = studentId,
    startedAtMillis = startedAt.toEpochMilli(),
    finishedAtMillis = finishedAt?.toEpochMilli(),
    pauses = homeworkPauses
        .filter { it.sessionId == id }
        .map { pause ->
            PauseFact(
                pauseStartAtMillis = pause.pauseStartAt.toEpochMilli(),
                pauseEndAtMillis = pause.pauseEndAt?.toEpochMilli(),
            )
        },
)