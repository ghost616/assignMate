package com.assignmate.app.homework.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.db.dao.HomeworkItemDao
import com.assignmate.app.core.data.db.dao.MovePositionOutcome
import com.assignmate.app.core.data.db.entity.HomeworkItemEntity
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkTimeSlot
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidation
import com.assignmate.app.homework.domain.HomeworkValidationError
import com.assignmate.app.homework.domain.HomeworkValidationException
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [HomeworkRepository] 默认实现：基于 core 的 [HomeworkItemDao]（Room）、auth 会话与可注入 [Clock]。
 *
 * 关键规则（与 [HomeworkValidators] 同源，仓库层兜底防绕过 UI）：
 * - 写入的家长归属 id 取自 auth 当前会话（parentId），学生 id 由调用方指定并校验会话有效；
 * - 权限分两个维度（[HomeworkValidators]），并统一叠加「家长归属」围栏：
 *   1) 改删/调序（[canModify]/[canDelete]/[canReorder]）：学生仅可操作自己录入项，家长可操作全部；
 *      其中调序额外要求「本人名下」（学生不可调序他人名下、即使该作业由学生录入）；
 *      类型/阶段范围/截止时间（[updateTemplate]）为家长专属，学生会话直接拒绝；
 *   2) 执行权（[canOperate]）：时间排定与状态流转面向「作业的执行者」，
 *      学生可操作本人名下全部作业（含家长布置的），家长可操作名下学生全部作业；
 *   - 归属围栏：家长「作业所属学生必须在当前家长名下」（家长分支经 auth 的
 *     [AuthRepository.isStudentOwnedBy] 判定），学生会话沿用「仅本人名下」；全部按 id 入口
 *     共用 [readAuthorized] 做统一校验（会话有效性与权限维度在前、归属维度随后），
 *     失败一律返回各自密封结果的 PermissionDenied 且不改动任何数据；
 * - 时间：排定前先过 deadline 约束与同学生时间段防冲突（排除自身），失败返回可读原因；
 * - 进行中锁定：状态为 [HomeworkStatus.IN_PROGRESS] 的作业不得再调整优先级与时间排定
 *   （[reorderHomework]/[moveHomeworkTo]/[updateSchedule]/[clearSchedule] 一律拒绝，
 *   返回 LockedWorkInProgress 类结果且不改动数据）；「已完成」保持既有约束；
 * - 状态：新增为「已记录」；排定成功推进到「待完成」；其余流转按 [HomeworkStatus] 合法性校验；
 * - 时间来源统一取 [Clock]，便于测试注入固定时钟。
 */
@Singleton
class HomeworkRepositoryImpl @Inject constructor(
    private val homeworkItemDao: HomeworkItemDao,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    /** 时区口径：与 [com.assignmate.app.homework.di.HomeworkModule] 绑定的业务时区一致，供日期换算使用 */
    private val zoneId: ZoneId,
) : HomeworkRepository {

    // ---- 查询 ----

    override fun observeHomework(studentId: Long): Flow<List<HomeworkItem>> =
        homeworkItemDao.observeByStudent(studentId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun listHomework(studentId: Long): List<HomeworkItem> =
        homeworkItemDao.loadByStudent(studentId).map { it.toDomain() }

    override suspend fun getHomework(homeworkId: Long): HomeworkItem? =
        homeworkItemDao.findById(homeworkId)?.toDomain()

    // ---- 录入 ----

    override suspend fun addHomework(
        template: HomeworkTemplate,
        studentId: Long,
    ): AddHomeworkResult {
        val session = authRepository.currentSession()
        val parentId = session.parentId ?: return AddHomeworkResult.NoActiveSession
        // 目标学生必须是会话可见的作业归属：学生会话仅限本人，家长会话仅限本人名下学生
        if (!isTargetStudentVisible(studentId)) {
            return AddHomeworkResult.NoActiveSession
        }
        return HomeworkValidators.validateTemplate(template).fold(
            onSuccess = { validated ->
                val firstPriority = nextPriority(studentId)
                val items = validated.template.toItems(
                    parentAccountId = parentId,
                    studentId = studentId,
                    createdAt = now(),
                    firstPriority = firstPriority,
                )
                // 阶段作业最多 30 条：事务化写入，避免中途失败留下半套作业
                val ids = homeworkItemDao.insertAllInTransaction(items.map { it.toEntity() })
                AddHomeworkResult.Success(items.mapIndexed { index, item -> item.copy(id = ids[index]) })
            },
            onFailure = { error ->
                AddHomeworkResult.TemplateInvalid(error.asValidationError())
            },
        )
    }

    // ---- 优先级 ----

    override suspend fun reorderHomework(
        homeworkId: Long,
        direction: ReorderDirection,
        sessionRole: Role,
    ): HomeworkOrderResult =
        // 统一判定：调序权（改删权 + 归属维度）+ 家长归属围栏
        readAuthorized(homeworkId, sessionRole) { item ->
            HomeworkValidators.canReorder(item, sessionRole, currentStudentId())
        }.fold(
            onNotFound = { HomeworkOrderResult.NotFound },
            onDenied = { HomeworkOrderResult.PermissionDenied },
            onAllowed = { item ->
                if (item.status == HomeworkStatus.IN_PROGRESS) {
                    // 进行中锁定：优先级已在计时链路中被确认，禁止再调整（不改动数据）
                    HomeworkOrderResult.LockedWorkInProgress
                } else {
                    reorderSiblings(item.id, item.studentId, direction)
                }
            },
        )

    /** 相邻交换（边界幂等成功）：清单自身位置与同学生可见范围一致，故排序后按 id 定位被调项 */
    private suspend fun reorderSiblings(
        homeworkId: Long,
        studentId: Long,
        direction: ReorderDirection,
    ): HomeworkOrderResult {
        val ordered = homeworkItemDao.loadByStudent(studentId)
        val index = ordered.indexOfFirst { it.id == homeworkId }
        if (index < 0) {
            return HomeworkOrderResult.NotFound
        }
        val neighborIndex = when (direction) {
            ReorderDirection.UP -> index - 1
            ReorderDirection.DOWN -> index + 1
        }
        if (neighborIndex !in ordered.indices) {
            // 已在边界：幂等成功，不改动任何数据
            return HomeworkOrderResult.Success
        }
        swapPriority(ordered[index], ordered[neighborIndex])
        return HomeworkOrderResult.Success
    }

    override suspend fun moveHomeworkTo(
        homeworkId: Long,
        targetIndex: Int,
        sessionRole: Role,
    ): HomeworkOrderResult =
        readAuthorized(homeworkId, sessionRole) { item ->
            HomeworkValidators.canReorder(item, sessionRole, currentStudentId())
        }.fold(
            onNotFound = { HomeworkOrderResult.NotFound },
            onDenied = { HomeworkOrderResult.PermissionDenied },
            onAllowed = { item ->
                if (item.status == HomeworkStatus.IN_PROGRESS) {
                    // 进行中锁定：落位调整同样被拒绝（与 reorderHomework 同源）
                    HomeworkOrderResult.LockedWorkInProgress
                } else {
                    // 落位需要「先读全清单 → 校验 → 批量写优先级」三段原子完成：
                    // 统一交给 DAO 的 @Transaction 方法（事务内重读并在写入前复查进行中锁定），
                    // 避免读与写之间作业被并发置为进行中后仍完成一次已失效的重排。
                    when (homeworkItemDao.moveToPositionInTransaction(homeworkId, targetIndex)) {
                        MovePositionOutcome.SUCCESS -> HomeworkOrderResult.Success
                        MovePositionOutcome.NOT_FOUND -> HomeworkOrderResult.NotFound
                        MovePositionOutcome.LOCKED -> HomeworkOrderResult.LockedWorkInProgress
                    }
                }
            },
        )

    // ---- 时间排定 ----

    override suspend fun updateSchedule(
        homeworkId: Long,
        startTime: Instant,
        estimatedMinutes: Int,
        sessionRole: Role,
    ): ScheduleUpdateResult {
        // 统一判定：执行权（学生可为本人名下含家长布置的作业排定时间）+ 归属围栏
        val access = readAuthorized(homeworkId, sessionRole) { item ->
            canOperate(item, sessionRole)
        }
        if (access == TargetAccess.NotFound) {
            return ScheduleUpdateResult.NotFound
        }
        if (access == TargetAccess.Denied) {
            return ScheduleUpdateResult.PermissionDenied
        }
        val domain = (access as TargetAccess.Allowed).item
        // 进行中锁定：开始时刻是计时链路的既有事实，进行中不得再改时间（保留原排定）
        if (domain.status == HomeworkStatus.IN_PROGRESS) {
            return ScheduleUpdateResult.LockedWorkInProgress
        }
        if (HomeworkValidators.validateEstimatedMinutes(estimatedMinutes) is HomeworkValidation.Invalid) {
            return ScheduleUpdateResult.InvalidEstimatedMinutes
        }
        if (!domain.status.canTransitionTo(HomeworkStatus.PENDING)) {
            return ScheduleUpdateResult.StatusTransitionDenied
        }
        val startMillis = startTime.toEpochMilli()
        val deadlineCheck = HomeworkValidators.validateDeadline(
            startMillis = startMillis,
            estimatedMinutes = estimatedMinutes,
            deadlineMillis = domain.deadline?.toEpochMilli(),
        )
        if (deadlineCheck is HomeworkValidation.Invalid) {
            return ScheduleUpdateResult.DeadlineExceeded
        }
        val conflictCheck = HomeworkValidators.validateTimeSlot(
            candidate = HomeworkTimeSlot.of(startMillis, estimatedMinutes),
            existing = homeworkItemDao.loadScheduledByStudent(domain.studentId).map { it.toDomain() },
            excludeItemId = domain.id,
        )
        if (conflictCheck is HomeworkValidation.Invalid) {
            return ScheduleUpdateResult.TimeConflict
        }
        val nextStatus = if (domain.status == HomeworkStatus.RECORDED) {
            HomeworkStatus.PENDING
        } else {
            domain.status
        }
        val updated = domain.copy(
            startTime = startTime,
            estimatedMinutes = estimatedMinutes,
            status = nextStatus,
        )
        homeworkItemDao.update(updated.toEntity())
        return ScheduleUpdateResult.Success(updated)
    }

    override suspend fun clearSchedule(
        homeworkId: Long,
        sessionRole: Role,
    ): HomeworkOperationResult =
        // 统一判定：执行权（学生可撤销本人名下作业的时间排定）+ 归属围栏
        readAuthorized(homeworkId, sessionRole) { item ->
            canOperate(item, sessionRole)
        }.fold(
            onNotFound = { HomeworkOperationResult.NotFound },
            onDenied = { HomeworkOperationResult.PermissionDenied },
            onAllowed = { domain ->
                if (domain.status == HomeworkStatus.IN_PROGRESS) {
                    // 进行中锁定：排定时间段是计时与防冲突校验的依据，进行中不允许撤销
                    HomeworkOperationResult.LockedWorkInProgress
                } else {
                    clearScheduleFor(domain)
                }
            },
        )

    /** 落库撤销排定：已完成回退为进行中（保证撤销后仍可重新排定，不留死局） */
    private suspend fun clearScheduleFor(domain: HomeworkItem): HomeworkOperationResult {
        val nextStatus = when (domain.status) {
            HomeworkStatus.PENDING -> HomeworkStatus.RECORDED
            HomeworkStatus.COMPLETED -> HomeworkStatus.IN_PROGRESS
            else -> domain.status
        }
        val updated = domain.copy(startTime = null, estimatedMinutes = null, status = nextStatus)
        homeworkItemDao.update(updated.toEntity())
        return HomeworkOperationResult.Success(updated)
    }

    // ---- 内容 / 类型 ----

    override suspend fun updateContent(
        homeworkId: Long,
        content: String,
        sessionRole: Role,
    ): HomeworkOperationResult =
        // 统一判定：改删权（学生仅可改自己录入项）+ 归属围栏
        readAuthorized(homeworkId, sessionRole) { item ->
            HomeworkValidators.canModify(item, sessionRole)
        }.fold(
            onNotFound = { HomeworkOperationResult.NotFound },
            onDenied = { HomeworkOperationResult.PermissionDenied },
            onAllowed = { domain ->
                val trimmed = content.trim()
                when {
                    trimmed.isEmpty() -> HomeworkOperationResult.ContentInvalid(
                        HomeworkValidationError.BLANK_CONTENT,
                    )

                    trimmed.length > HomeworkConstants.MAX_CONTENT_LENGTH -> HomeworkOperationResult.ContentInvalid(
                        HomeworkValidationError.CONTENT_TOO_LONG,
                    )

                    else -> {
                        val updated = domain.copy(content = trimmed)
                        homeworkItemDao.update(updated.toEntity())
                        HomeworkOperationResult.Success(updated)
                    }
                }
            },
        )

    override suspend fun updateTemplate(
        homeworkId: Long,
        type: HomeworkType,
        stageRange: StageRange?,
        deadline: Instant?,
        sessionRole: Role,
    ): HomeworkOperationResult {
        // 统一判定：家长专属（改删权维度）+ 家长归属围栏。
        // 必须走 readAuthorized 而非只比对传入的 sessionRole——后者会让「传入角色」替代真实会话角色，
        // 学生会话一旦被误传 Role.PARENT 即可改本人名下作业的类型/阶段范围/截止时间（纵深防御缺口）。
        val access = readAuthorized(homeworkId, sessionRole) { domain ->
            HomeworkValidators.canModify(domain, sessionRole)
        }
        return access.fold(
            onNotFound = { HomeworkOperationResult.NotFound },
            onDenied = { HomeworkOperationResult.PermissionDenied },
            onAllowed = { domain ->
                // 类型/阶段范围/截止时间为家长专属修改项（学生仅可改自己录入项的内容与时间排定）
                if (sessionRole != Role.PARENT) {
                    return@fold HomeworkOperationResult.PermissionDenied
                }
                val check = HomeworkValidators.validateTypeChange(
                    type = type,
                    stageRange = stageRange,
                    deadlineMillis = deadline?.toEpochMilli(),
                    creatorRole = domain.createdByRole,
                    startEpochDay = startEpochDayOf(domain),
                )
                if (check is HomeworkValidation.Invalid) {
                    return@fold HomeworkOperationResult.TemplateInvalid(check.error)
                }
                // 新 deadline 必须同时容纳既有排定时间段（与 updateSchedule 同一套 deadline 约束）
                if (domain.startTime != null && domain.estimatedMinutes != null) {
                    val scheduleCheck = HomeworkValidators.validateDeadline(
                        startMillis = domain.startTime.toEpochMilli(),
                        estimatedMinutes = domain.estimatedMinutes,
                        deadlineMillis = deadline?.toEpochMilli(),
                    )
                    if (scheduleCheck is HomeworkValidation.Invalid) {
                        return@fold HomeworkOperationResult.TemplateInvalid(scheduleCheck.error)
                    }
                }
                val updated = domain.copy(
                    type = type,
                    stageRange = if (type == HomeworkType.STAGE) stageRange else null,
                    deadline = deadline,
                )
                homeworkItemDao.update(updated.toEntity())
                HomeworkOperationResult.Success(updated)
            },
        )
    }

    // ---- 删除 ----

    override suspend fun deleteHomework(
        homeworkId: Long,
        sessionRole: Role,
    ): HomeworkOperationResult {
        // 统一判定：改删权（学生仅可删自己录入项）+ 归属围栏；失败不改动任何数据
        val access = readAuthorized(homeworkId, sessionRole) { domain ->
            HomeworkValidators.canDelete(domain, sessionRole)
        }
        return access.fold(
            onNotFound = { HomeworkOperationResult.NotFound },
            onDenied = { HomeworkOperationResult.PermissionDenied },
            onAllowed = { domain ->
                // 通过统一判定的实体即库中当前行（判定与删除之间无挂起写入）
                val entity = (access as TargetAccess.Allowed).entity
                homeworkItemDao.delete(entity)
                HomeworkOperationResult.Success(domain)
            },
        )
    }

    // ---- 状态流转 ----

    override suspend fun markPending(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        transition(homeworkId, sessionRole, HomeworkStatus.PENDING)

    override suspend fun startProgress(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        transition(homeworkId, sessionRole, HomeworkStatus.IN_PROGRESS)

    override suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        transition(homeworkId, sessionRole, HomeworkStatus.COMPLETED)

    override suspend fun reopen(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        transition(homeworkId, sessionRole, HomeworkStatus.IN_PROGRESS)

    // ---- 私有工具 ----

    /** 统一状态流转入口：会话有效性 + 执行权 + 归属围栏 → 流转合法性校验 → 落库 */
    private suspend fun transition(
        homeworkId: Long,
        sessionRole: Role,
        target: HomeworkStatus,
    ): HomeworkStatusResult =
        // 执行权：学生可推进本人名下（含家长布置的）作业状态：待完成 → 进行中 → 已完成
        readAuthorized(homeworkId, sessionRole) { item ->
            canOperate(item, sessionRole)
        }.fold(
            onNotFound = { HomeworkStatusResult.NotFound },
            onDenied = { HomeworkStatusResult.PermissionDenied },
            onAllowed = { domain ->
                when {
                    !HomeworkValidators.canTransition(domain.status, target) ->
                        HomeworkStatusResult.IllegalTransition(domain.status, target)

                    domain.status == target -> HomeworkStatusResult.Success(domain)
                    else -> {
                        val updated = domain.copy(status = target)
                        homeworkItemDao.update(updated.toEntity())
                        HomeworkStatusResult.Success(updated)
                    }
                }
            },
        )

    // ---- 统一访问判定（会话有效性 + 权限维度 + 家长归属围栏） ----

    /**
     * 按 id 入口的统一访问判定（唯一的「是否可操作目标作业」口径）：读取作业后依次做
     * 「会话有效性/角色 + 权限维度」判定，最后补「归属围栏」判定。
     *
     * 判定顺序（失败即返回、不改动任何数据）：
     * 1) 作业不存在 → [TargetAccess.NotFound]；
     * 2) 会话无角色（登出等）或权限维度拒绝 → [TargetAccess.Denied]；
     * 3) 归属围栏（[isTargetStudentVisible]）：学生仅本人名下、家长仅名下学生 → 否则 [TargetAccess.Denied]。
     *
     * 权限维度由调用方以 [permission] 传入（改删权 / 调序权 / 执行权），故本入口保持既有权限语义不变，
     * 仅在其后叠加统一的归属维。[permission] 声明为 suspend 以便内联处直接取会话（学生 id）。
     */
    private suspend fun readAuthorized(
        homeworkId: Long,
        sessionRole: Role,
        permission: suspend (HomeworkItem) -> Boolean,
    ): TargetAccess {
        val entity = homeworkItemDao.findById(homeworkId) ?: return TargetAccess.NotFound
        val domain = entity.toDomain()
        val session = authRepository.currentSession()
        if (session.role != sessionRole || !permission(domain)) {
            return TargetAccess.Denied
        }
        return if (isTargetStudentVisible(session, domain.studentId)) {
            TargetAccess.Allowed(domain, entity)
        } else {
            TargetAccess.Denied
        }
    }

    /**
     * 执行权判定：家长可操作名下学生全部作业；学生仅可操作本人名下作业
     * （[CreatorRole] 不参与判定，故学生可为家长布置的作业排定时间与推进状态）。
     * 家长归属围栏由 [readAuthorized] 在权限维度之后统一补齐。
     */
    private suspend fun canOperate(domain: HomeworkItem, sessionRole: Role): Boolean =
        HomeworkValidators.canOperate(
            item = domain,
            sessionRole = sessionRole,
            sessionStudentId = currentStudentId(),
        )

    /** 当前会话的学生 id（家长会话为 null）：调序归属校验与执行权判定共用同一口径 */
    private suspend fun currentStudentId(): Long? = authRepository.currentSession().studentId

    /** 归属围栏读取当前会话后的判定入口（新增/修改模板等非「按 id 统一判定」调用点复用） */
    private suspend fun isTargetStudentVisible(studentId: Long): Boolean =
        isTargetStudentVisible(authRepository.currentSession(), studentId)

    /**
     * 目标学生是否属于会话 [session] 的可见范围（归属围栏的唯一口径）：
     * 学生会话仅限本人；家长会话仅限名下学生（经 auth 的 [AuthRepository.isStudentOwnedBy]，
     * 与 timer/stats 等模块共用同一归属判定，替代本模块原先私有的等价实现）；无会话一律拒绝。
     *
     * 异常语义：不在此处用 runCatching 包裹——[AuthRepository.isStudentOwnedBy] 契约已把数据层异常
     * 收敛为 false 并显式重抛 [CancellationException]（结构化并发语义得以保留），外层再包一层
     * runCatching 反而可能吞掉协程取消；若换用会抛异常的实现，异常将按「判定失败」向上传播而非被静默吞掉。
     */
    private suspend fun isTargetStudentVisible(
        session: SessionState,
        studentId: Long,
    ): Boolean {
        val parentId = session.parentId
        return when (session.role) {
            Role.STUDENT -> session.studentId == studentId
            Role.PARENT -> parentId != null && authRepository.isStudentOwnedBy(parentId, studentId)
            null -> false
        }
    }

    /** 统一访问判定结果：越权/无权与「作业不存在」必须可区分（分别映射到各自密封结果） */
    private sealed class TargetAccess {

        /** 作业不存在（可能已被删除） */
        data object NotFound : TargetAccess()

        /** 会话无效、权限维度不足或归属围栏不通过 */
        data object Denied : TargetAccess()

        /** 通过全部判定：携带领域模型与实体（供调用方继续业务流程/落库） */
        data class Allowed(
            val item: HomeworkItem,
            val entity: HomeworkItemEntity,
        ) : TargetAccess()
    }

    /** 三态判定的统一收口：各入口据此映射到自己的密封结果（失败分支不改动任何数据） */
    private suspend fun <T> TargetAccess.fold(
        onNotFound: () -> T,
        onDenied: () -> T,
        onAllowed: suspend (HomeworkItem) -> T,
    ): T = when (this) {
        TargetAccess.NotFound -> onNotFound()
        TargetAccess.Denied -> onDenied()
        is TargetAccess.Allowed -> onAllowed(item)
    }

    /** 作业的归属日（epochDay）：阶段作业展开日已在写入时固化，此处以创建时刻所在日为准做范围校验 */
    private fun startEpochDayOf(domain: HomeworkItem): Long =
        HomeworkValidators.epochDayOf(domain.createdAt.toEpochMilli(), zoneId)

    /** 交换两条作业项的优先级（上移/下移的基本操作） */
    private suspend fun swapPriority(first: HomeworkItemEntity, second: HomeworkItemEntity) {
        homeworkItemDao.update(first.copy(priority = second.priority))
        homeworkItemDao.update(second.copy(priority = first.priority))
    }

    /** 追加排序：空清单取最小值，否则为当前最大值 + 步长（保证新增项落在清单末尾） */
    private suspend fun nextPriority(studentId: Long): Int {
        val max = homeworkItemDao.maxPriority(studentId) ?: return HomeworkConstants.MIN_PRIORITY
        return max + HomeworkConstants.PRIORITY_STEP
    }

    private fun now(): Instant = Instant.ofEpochMilli(clock.currentTimeMillis())

    private fun Throwable.asValidationError(): HomeworkValidationError =
        (this as? HomeworkValidationException)?.error
            ?: throw IllegalStateException("非校验异常，不应作为模板校验失败处理", this)

    private fun HomeworkItemEntity.toDomain(): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = parentAccountId,
        studentId = studentId,
        content = content,
        type = HomeworkType.fromName(type) ?: HomeworkType.TODAY,
        stageRange = StageRange.fromName(stageRange),
        deadline = deadline,
        priority = priority,
        startTime = startTime,
        estimatedMinutes = estimatedMinutes,
        status = HomeworkStatus.fromName(status) ?: HomeworkStatus.INITIAL,
        createdByRole = CreatorRole.fromName(createdByRole) ?: CreatorRole.PARENT,
        createdAt = createdAt,
    )

    private fun HomeworkItem.toEntity(): HomeworkItemEntity = HomeworkItemEntity(
        id = id,
        parentAccountId = parentAccountId,
        studentId = studentId,
        content = content,
        type = type.name,
        stageRange = stageRange?.name,
        deadline = deadline,
        priority = priority,
        startTime = startTime,
        estimatedMinutes = estimatedMinutes,
        status = status.name,
        createdByRole = createdByRole.name,
        createdAt = createdAt,
    )
}