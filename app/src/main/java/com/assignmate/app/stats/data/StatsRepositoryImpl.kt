package com.assignmate.app.stats.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.stats.domain.DaySummary
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.ItemDetail
import com.assignmate.app.stats.domain.StatsCalculations
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StatsRepository] 默认实现：**只读聚合** homework 的作业清单与 core 的**作业每天详情**，
 * 不新增数据库表、不改动任何既有数据、不触发任何写操作。
 *
 * 关键口径（与 homework 清单页同源，避免跨模块漂移）：
 * - **当天应做**（完成率分母）由**作业项**推导：「当天作业（归属日即当天）+ 阶段作业（今天落在阶段覆盖范围内）」，
 *   判定复用 homework 领域的 [com.assignmate.app.homework.domain.StageDayRecords.isVisibleOnDay] /
 *   [com.assignmate.app.homework.domain.HomeworkValidators.epochDayOf]；**不能**拿「当天有没有每天详情」当分母
 *   （每天详情是按需写入的，没动过的作业没有详情，会让完成率虚高）；
 * - **当天状态/完成数**：阶段作业由每天详情推导、当天作业用状态列（同 homework 的完成判定口径）；
 * - **暂停三项**取自当天应做作业在这一天的每天详情（跨天计时按开始日归属，写入侧已定）；
 * - 因此仓库仍需要可注入 [Clock] 与业务 [ZoneId]：前者取「今天」（阶段缺卡判定）、后者**原样透传**给
 *   纯函数与 homework 侧的自然日判定（当天作业归属日、阶段可见性与阶段打卡进度），
 *   本模块不得自建时区来源、也不得依赖对方签名上的默认值——覆写 core 的唯一业务时区绑定即全局生效。
 *
 * 归属与越权（与 homework/timer 同一口径）：目标学生由调用方显式传入，仓库层按当前会话兜底校验——
 * 学生会话仅可为本人，家长会话仅可为其名下学生（统一经 auth 的 [AuthRepository.isStudentOwnedBy] /
 * [AuthRepository.ownedStudentIds] 判定，本模块不自建等价的归属实现）；
 * 校验不通过统一返回 [StatsFailure.ACCESS_DENIED]，**不抛业务异常**、也不返回他人数据。
 * 数据访问异常统一收敛为 [StatsFailure.READ_FAILED]。
 *
 * 性能取向：一次查询只读一遍作业清单与其每天详情（详情按作业维度读，查询次数 = 作业条数，
 * 与跨多少天无关；历史范围查询因此不随天数放大数据库访问），再交给纯函数内存聚合。
 */
@Singleton
class StatsRepositoryImpl @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val dailyRecordRepository: HomeworkDailyRecordRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : StatsRepository {

    override suspend fun summarizeDay(studentId: Long, epochDay: Long): StatsResult<DaySummary> {
        val access = checkAccess(studentId)
        if (access != null) {
            return StatsResult.Failure(access)
        }
        // 当天没有应做作业时返回空盘点（非失败）：页面据此展示空态而非错误提示
        return readSnapshot(studentId).map { snapshot ->
            StatsCalculations.summarizeDay(
                epochDay = epochDay,
                todayEpochDay = todayEpochDay(),
                items = snapshot.items,
                recordsByHomework = snapshot.recordsByHomework,
                zoneId = zoneId,
            )
        }
    }

    override suspend fun itemDetail(homeworkId: Long, epochDay: Long): StatsResult<ItemDetail> {
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
        val records = runCatching { dailyRecordRepository.loadByHomework(homeworkId) }
            .getOrElse { return StatsResult.Failure(StatsFailure.READ_FAILED) }
        return StatsResult.Success(
            StatsCalculations.dayDetail(
                item = item,
                records = records,
                epochDay = epochDay,
                todayEpochDay = todayEpochDay(),
                zoneId = zoneId,
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
                todayEpochDay = todayEpochDay(),
                items = snapshot.items,
                recordsByHomework = snapshot.recordsByHomework,
                zoneId = zoneId,
            )
        }
    }

    // ---- 取数 ----

    /**
     * 读取某学生的作业清单与其每天详情索引（读取失败收敛为 [StatsFailure.READ_FAILED]）。
     *
     * 每天详情**按作业维度一次性读全**（每条作业一次查询，与该作业跨多少天无关），
     * 由纯函数再按自然日切分；阶段打卡进度也直接来自这份数据，无需二次查询。
     */
    private suspend fun readSnapshot(studentId: Long): StatsResult<Snapshot> = runCatching {
        val items = homeworkRepository.listHomework(studentId)
        Snapshot(
            items = items,
            recordsByHomework = items.associate { item ->
                item.id to dailyRecordRepository.loadByHomework(item.id)
            },
        )
    }.fold(
        onSuccess = { StatsResult.Success(it) },
        onFailure = { StatsResult.Failure(StatsFailure.READ_FAILED) },
    )

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
     * [AuthRepository.isStudentOwnedBy]，与 homework/timer 共用同一归属能力）；无会话一律不可见。
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
     * 业务自然日的「今天」：直接复用领域层统一入口（可注入时钟 + 业务时区），
     * 供「阶段作业在该天是否已过去（缺卡）」判定与页面展示同源，避免跨零点/时区口径漂移。
     */
    private fun todayEpochDay(): Long = StatsCalculations.epochDayOfToday(zoneId, clock.currentTimeMillis())
}

/**
 * 结果映射：成功时转换数据，失败时原样传递（统计聚合链路常用，避免层层 when 判空）。
 */
internal fun <T, R> StatsResult<T>.map(transform: (T) -> R): StatsResult<R> = when (this) {
    is StatsResult.Success -> StatsResult.Success(transform(data))
    is StatsResult.Failure -> this
}

/** 取数快照：作业清单（按优先级升序）+ 作业 id -> 该作业全部天详情 */
private data class Snapshot(
    val items: List<HomeworkItem>,
    val recordsByHomework: Map<Long, List<HomeworkDailyRecord>>,
)
