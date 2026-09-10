package com.assignmate.app.homework.data

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.db.dao.HomeworkItemDao
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [HomeworkRepository] 默认实现：基于 core 的 [HomeworkItemDao]（Room）、auth 会话与可注入 [Clock]。
 *
 * 关键规则（与 [HomeworkValidators] 同源，仓库层兜底防绕过 UI）：
 * - 写入的家长归属 id 取自 auth 当前会话（parentId），学生 id 由调用方指定并校验会话有效；
 * - 权限分两个维度（[HomeworkValidators]）：
 *   1) 改删/调序（[canModify]/[canDelete]/[canReorder]）：学生仅可操作自己录入项，家长可操作全部；
 *      类型/阶段范围/截止时间（[updateTemplate]）为家长专属，学生会话直接拒绝；
 *   2) 执行权（[canOperate]）：时间排定与状态流转面向「作业的执行者」，
 *      学生可操作本人名下全部作业（含家长布置的），家长可操作名下学生全部作业；
 * - 时间：排定前先过 deadline 约束与同学生时间段防冲突（排除自身），失败返回可读原因；
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
        if (!canTargetStudent(session, studentId)) {
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
    ): HomeworkOrderResult {
        val item = homeworkItemDao.findById(homeworkId) ?: return HomeworkOrderResult.NotFound
        if (!HomeworkValidators.canReorder(item.toDomain(), sessionRole)) {
            return HomeworkOrderResult.PermissionDenied
        }
        val ordered = homeworkItemDao.loadByStudent(item.studentId)
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
    ): HomeworkOrderResult {
        val item = homeworkItemDao.findById(homeworkId) ?: return HomeworkOrderResult.NotFound
        if (!HomeworkValidators.canReorder(item.toDomain(), sessionRole)) {
            return HomeworkOrderResult.PermissionDenied
        }
        val ordered = homeworkItemDao.loadByStudent(item.studentId)
        val from = ordered.indexOfFirst { it.id == homeworkId }
        if (from < 0) {
            return HomeworkOrderResult.NotFound
        }
        val to = targetIndex.coerceIn(0, ordered.lastIndex)
        if (from == to) {
            return HomeworkOrderResult.Success
        }
        val reordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
        reordered.forEachIndexed { index, entity ->
            val expected = HomeworkConstants.MIN_PRIORITY + index * HomeworkConstants.PRIORITY_STEP
            if (entity.priority != expected) {
                homeworkItemDao.update(entity.copy(priority = expected))
            }
        }
        return HomeworkOrderResult.Success
    }

    // ---- 时间排定 ----

    override suspend fun updateSchedule(
        homeworkId: Long,
        startTime: Instant,
        estimatedMinutes: Int,
        sessionRole: Role,
    ): ScheduleUpdateResult {
        val item = homeworkItemDao.findById(homeworkId) ?: return ScheduleUpdateResult.NotFound
        val domain = item.toDomain()
        // 执行权：学生可为本人名下（含家长布置的）作业排定时间
        if (!canOperate(domain, sessionRole)) {
            return ScheduleUpdateResult.PermissionDenied
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
            deadlineMillis = item.deadline?.toEpochMilli(),
        )
        if (deadlineCheck is HomeworkValidation.Invalid) {
            return ScheduleUpdateResult.DeadlineExceeded
        }
        val conflictCheck = HomeworkValidators.validateTimeSlot(
            candidate = HomeworkTimeSlot.of(startMillis, estimatedMinutes),
            existing = homeworkItemDao.loadScheduledByStudent(item.studentId).map { it.toDomain() },
            excludeItemId = item.id,
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
    ): HomeworkOperationResult {
        val item = homeworkItemDao.findById(homeworkId) ?: return HomeworkOperationResult.NotFound
        val domain = item.toDomain()
        // 执行权：学生可撤销本人名下作业的时间排定
        if (!canOperate(domain, sessionRole)) {
            return HomeworkOperationResult.PermissionDenied
        }
        // 已完成的作业排在清除后允许重新排定：状态回退为进行中（与 undoBlockedScheduling 语义一致）
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
    ): HomeworkOperationResult {
        val item = homeworkItemDao.findById(homeworkId) ?: return HomeworkOperationResult.NotFound
        val domain = item.toDomain()
        if (!HomeworkValidators.canModify(domain, sessionRole)) {
            return HomeworkOperationResult.PermissionDenied
        }
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return HomeworkOperationResult.ContentInvalid(HomeworkValidationError.BLANK_CONTENT)
        }
        if (trimmed.length > HomeworkConstants.MAX_CONTENT_LENGTH) {
            return HomeworkOperationResult.ContentInvalid(HomeworkValidationError.CONTENT_TOO_LONG)
        }
        val updated = domain.copy(content = trimmed)
        homeworkItemDao.update(updated.toEntity())
        return HomeworkOperationResult.Success(updated)
    }

    override suspend fun updateTemplate(
        homeworkId: Long,
        type: HomeworkType,
        stageRange: StageRange?,
        deadline: Instant?,
        sessionRole: Role,
    ): HomeworkOperationResult {
        val item = homeworkItemDao.findById(homeworkId) ?: return HomeworkOperationResult.NotFound
        val domain = item.toDomain()
        // 类型/阶段范围/截止时间为家长专属修改项（学生仅可改自己录入项的内容与时间排定）
        if (sessionRole != Role.PARENT) {
            return HomeworkOperationResult.PermissionDenied
        }
        // 家长只能改自己名下学生的作业，避免跨家长越权（聚合口径与 addHomework 对齐）
        if (!canTargetStudent(authRepository.currentSession(), domain.studentId)) {
            return HomeworkOperationResult.PermissionDenied
        }
        val check = HomeworkValidators.validateTypeChange(
            type = type,
            stageRange = stageRange,
            deadlineMillis = deadline?.toEpochMilli(),
            creatorRole = domain.createdByRole,
            startEpochDay = startEpochDayOf(domain),
        )
        if (check is HomeworkValidation.Invalid) {
            return HomeworkOperationResult.TemplateInvalid(check.error)
        }
        // 新 deadline 必须同时容纳既有排定时间段（与 updateSchedule 同一套 deadline 约束）
        if (domain.startTime != null && domain.estimatedMinutes != null) {
            val scheduleCheck = HomeworkValidators.validateDeadline(
                startMillis = domain.startTime.toEpochMilli(),
                estimatedMinutes = domain.estimatedMinutes,
                deadlineMillis = deadline?.toEpochMilli(),
            )
            if (scheduleCheck is HomeworkValidation.Invalid) {
                return HomeworkOperationResult.TemplateInvalid(scheduleCheck.error)
            }
        }
        val updated = domain.copy(
            type = type,
            stageRange = if (type == HomeworkType.STAGE) stageRange else null,
            deadline = deadline,
        )
        homeworkItemDao.update(updated.toEntity())
        return HomeworkOperationResult.Success(updated)
    }

    // ---- 删除 ----

    override suspend fun deleteHomework(
        homeworkId: Long,
        sessionRole: Role,
    ): HomeworkOperationResult {
        val item = homeworkItemDao.findById(homeworkId) ?: return HomeworkOperationResult.NotFound
        val domain = item.toDomain()
        if (!HomeworkValidators.canDelete(domain, sessionRole)) {
            return HomeworkOperationResult.PermissionDenied
        }
        homeworkItemDao.delete(item)
        return HomeworkOperationResult.Success(domain)
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

    /** 统一状态流转入口：权限校验 → 流转合法性校验 → 落库 */
    private suspend fun transition(
        homeworkId: Long,
        sessionRole: Role,
        target: HomeworkStatus,
    ): HomeworkStatusResult {
        val item = homeworkItemDao.findById(homeworkId) ?: return HomeworkStatusResult.NotFound
        val domain = item.toDomain()
        // 执行权：学生可推进本人名下（含家长布置的）作业状态：待完成 → 进行中 → 已完成
        if (!canOperate(domain, sessionRole)) {
            return HomeworkStatusResult.PermissionDenied
        }
        if (!HomeworkValidators.canTransition(domain.status, target)) {
            return HomeworkStatusResult.IllegalTransition(domain.status, target)
        }
        if (domain.status == target) {
            return HomeworkStatusResult.Success(domain)
        }
        val updated = domain.copy(status = target)
        homeworkItemDao.update(updated.toEntity())
        return HomeworkStatusResult.Success(updated)
    }

    /**
     * 执行权判定：家长可操作名下学生全部作业；学生仅可操作本人名下作业
     * （[creatorRole] 不参与判定，故学生可为家长布置的作业排定时间与推进状态）。
     */
    private suspend fun canOperate(domain: HomeworkItem, sessionRole: Role): Boolean =
        HomeworkValidators.canOperate(
            item = domain,
            sessionRole = sessionRole,
            sessionStudentId = authRepository.currentSession().studentId,
        )

    /** 目标学生是否属于当前会话可见范围：学生会话仅限本人，家长会话仅限本人名下学生 */
    private suspend fun canTargetStudent(session: SessionState, studentId: Long): Boolean =
        when (session.role) {
            Role.STUDENT -> session.studentId == studentId
            Role.PARENT -> session.parentId != null &&
                authRepository.getStudent(studentId)?.parentAccountId == session.parentId

            null -> false
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