package com.assignmate.app.homework.domain

import com.assignmate.app.auth.domain.Role
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * homework 校验结果：合格或携带机器可读原因（用户文案由 UI 层集中映射）。
 * 校验一律不抛异常，失败原因可被单测直接断言。
 */
sealed class HomeworkValidation {

    /** 校验通过 */
    data object Valid : HomeworkValidation()

    /** 校验失败（[error] 为机器可读原因） */
    data class Invalid(val error: HomeworkValidationError) : HomeworkValidation()
}

/** 校验失败原因（机器可读，UI 经 HomeworkErrorMessages 统一映射为可读文案） */
enum class HomeworkValidationError {

    /** 作业内容为空 */
    BLANK_CONTENT,

    /** 作业内容超长 */
    CONTENT_TOO_LONG,

    /** 阶段作业未选阶段范围 */
    MISSING_STAGE_RANGE,

    /** 家长录入的阶段作业未设截止时间 */
    MISSING_STAGE_DEADLINE,

    /** 开始时间 + 预估时长晚于截止时间 */
    DEADLINE_EXCEEDED,

    /** 预估时长不合法（非正整数或超出允许区间） */
    INVALID_ESTIMATED_MINUTES,

    /** 候选时间段与同学生其它已排定作业时间重叠 */
    TIME_CONFLICT,

    /** 当前角色对该作业无操作权限 */
    PERMISSION_DENIED,

    /** 作业状态流转不合法 */
    ILLEGAL_STATUS_TRANSITION,
}

/** 已排定时间段（[startMillis, endMillis)），用于时间段重叠判定 */
data class HomeworkTimeSlot(val startMillis: Long, val endMillis: Long) {

    /** 半开区间重叠判定：端点相接（前一结束 == 后一开始）不算冲突 */
    fun overlapsWith(other: HomeworkTimeSlot): Boolean =
        startMillis < other.endMillis && other.startMillis < endMillis

    companion object {

        /** 由开始时刻与时长（分钟）构造 */
        fun of(startMillis: Long, durationMinutes: Int): HomeworkTimeSlot =
            HomeworkTimeSlot(startMillis, startMillis + durationMinutes * MILLIS_PER_MINUTE)
    }
}

/** 合法的阶段作业模板 */
data class ValidatedTemplate(val template: HomeworkTemplate)

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * homework 纯函数校验集合（无副作用、无 IO、依赖可注入时钟传入的"当前时间"，集中可单测）。
 *
 * 覆盖四类业务规则：
 * 1. deadline 约束：开始时间 + 预估时长不得晚于 deadline；
 * 2. 时间段防冲突：候选时间段与同学生其它已排定作业不重叠（排除自身）；
 * 3. 权限规则：学生仅可修改/删除自己新增项、不可动家长录入项，家长可改删全部；
 * 4. 状态流转合法性：见 [HomeworkStatus.allowedTransitions]。
 *
 * 所有方法均为纯函数：不读时钟、不访问数据库，时间来源由调用方（仓库/ViewModel）注入。
 */
object HomeworkValidators {

    // ---- 1. deadline 约束 ----

    /**
     * 校验候选时间段是否满足 deadline 约束：开始时间 + 预估时长不得晚于 [deadline]。
     * deadline 为空视为无约束。
     */
    fun validateDeadline(
        startMillis: Long,
        estimatedMinutes: Int,
        deadlineMillis: Long?,
    ): HomeworkValidation {
        if (deadlineMillis == null) {
            return HomeworkValidation.Valid
        }
        val finishedAt = HomeworkTimeSlot.of(startMillis, estimatedMinutes).endMillis
        return if (finishedAt > deadlineMillis) {
            HomeworkValidation.Invalid(HomeworkValidationError.DEADLINE_EXCEEDED)
        } else {
            HomeworkValidation.Valid
        }
    }

    // ---- 2. 时间段防冲突 ----

    /**
     * 校验候选时间段是否与同学生其它已排定作业冲突。
     *
     * @param candidate 候选时间段（开始时间 + 预估时长）
     * @param existing 该学生全部已排定（start_time 非空）作业；时长缺失时按 [MIN_SLOT_MINUTES] 计
     * @param excludeItemId 需排除的作业项 id（修改自身时排除，避免与自己冲突）
     */
    fun validateTimeSlot(
        candidate: HomeworkTimeSlot,
        existing: List<HomeworkItem>,
        excludeItemId: Long? = null,
    ): HomeworkValidation {
        val conflict = existing.any { item ->
            if (excludeItemId != null && item.id == excludeItemId) {
                return@any false
            }
            val slot = item.toTimeSlot() ?: return@any false
            slot.overlapsWith(candidate)
        }
        return if (conflict) {
            HomeworkValidation.Invalid(HomeworkValidationError.TIME_CONFLICT)
        } else {
            HomeworkValidation.Valid
        }
    }

    /** 作业项已排定的时间段（未排定开始时间时为 null） */
    fun HomeworkItem.toTimeSlot(): HomeworkTimeSlot? =
        startTime?.let { start ->
            HomeworkTimeSlot.of(
                startMillis = start.toEpochMilli(),
                durationMinutes = estimatedMinutes ?: MIN_SLOT_MINUTES,
            )
        }

    /** 已排定作业缺预估时长时的兜底占用时长（分钟）：按最短任务处理，避免完全忽略其占用 */
    const val MIN_SLOT_MINUTES = 1

    // ---- 3. 权限规则 ----

    /**
     * 学生录入的作业是否只有学生本人可操作：
     * 学生仅可修改/删除自己新增的作业，不可改删家长录入的作业；家长可改删全部。
     */
    fun canModify(item: HomeworkItem, sessionRole: Role): Boolean = when (sessionRole) {
        Role.PARENT -> true
        Role.STUDENT -> item.createdByRole == CreatorRole.STUDENT
    }

    /** 删除权限与修改权限同源（家长全部可删，学生仅可删自己录入的） */
    fun canDelete(item: HomeworkItem, sessionRole: Role): Boolean = canModify(item, sessionRole)

    /** 是否可调整优先级：与修改权限同源（学生不可调整家长录入项的优先级） */
    fun canReorder(item: HomeworkItem, sessionRole: Role): Boolean = canModify(item, sessionRole)

    // ---- 3.1 执行权（时间排定与状态流转） ----

    /**
     * 是否可对该作业执行「时间排定与状态流转」。
     *
     * 与改删权限（[canModify]）刻意区分：作业的执行者是学生本人，与「谁录入」无关。
     * - 家长：可对名下学生的全部作业排定时间与流转状态；
     * - 学生：仅可操作本人名下作业（[HomeworkItem.studentId] == [sessionStudentId]），
     *   包括家长布置的作业——否则「家长布置 → 学生排定时间 → 计时完成」主闭环不可用；
     * - 学生会话未携带 studentId 时一律拒绝。
     */
    fun canOperate(
        item: HomeworkItem,
        sessionRole: Role,
        sessionStudentId: Long?,
    ): Boolean = when (sessionRole) {
        Role.PARENT -> true
        Role.STUDENT -> sessionStudentId != null && item.studentId == sessionStudentId
    }

    // ---- 4. 状态流转合法性 ----

    /** 校验状态流转是否合法（记录→待完成→进行中→已完成，见 [HomeworkStatus]） */
    fun validateStatusTransition(
        from: HomeworkStatus,
        to: HomeworkStatus,
    ): HomeworkValidation =
        if (from.canTransitionTo(to)) {
            HomeworkValidation.Valid
        } else {
            HomeworkValidation.Invalid(HomeworkValidationError.ILLEGAL_STATUS_TRANSITION)
        }

    /** 布尔便捷形式（幂等更新允许 from == to） */
    fun canTransition(from: HomeworkStatus, to: HomeworkStatus): Boolean = from.canTransitionTo(to)

    // ---- 录入模板校验 ----

    /**
     * 校验录入模板：
     * - 作业内容非空且不超长（家长录入必填，学生录入可为空）；
     * - 阶段作业必须选阶段范围；家长录入的阶段作业必须设 deadline；
     * - 阶段范围覆盖的最后一天不得晚于 deadline 所在日。
     */
    fun validateTemplate(template: HomeworkTemplate): Result<ValidatedTemplate> {
        val content = template.content.trim()
        if (template.creatorRole == CreatorRole.PARENT) {
            if (content.isEmpty()) {
                return failure(HomeworkValidationError.BLANK_CONTENT)
            }
            if (content.length > HomeworkConstants.MAX_CONTENT_LENGTH) {
                return failure(HomeworkValidationError.CONTENT_TOO_LONG)
            }
        } else if (content.length > HomeworkConstants.MAX_CONTENT_LENGTH) {
            return failure(HomeworkValidationError.CONTENT_TOO_LONG)
        }
        if (template.type == HomeworkType.STAGE) {
            if (template.stageRange == null) {
                return failure(HomeworkValidationError.MISSING_STAGE_RANGE)
            }
            if (template.creatorRole == CreatorRole.PARENT && template.deadline == null) {
                return failure(HomeworkValidationError.MISSING_STAGE_DEADLINE)
            }
            if (!template.fitsWithinDeadline()) {
                return failure(HomeworkValidationError.DEADLINE_EXCEEDED)
            }
        }
        return Result.success(ValidatedTemplate(template))
    }

    /**
     * 由既有作业项与新的类型/阶段范围/deadline 推导出的候选值是否合法（家长修改场景）。
     *
     * 与 [validateTemplate] 同一套规则：阶段作业必须带阶段范围、家长录入的阶段作业必须设 deadline，
     * 且阶段范围覆盖的最后一天不得晚于 deadline 所在日（避免"改了阶段范围/缩短 deadline 后覆盖越界"）。
     *
     * @param startEpochDay 阶段范围的起始日（epochDay，取自作业创建日），仅阶段作业参与计算
     */
    fun validateTypeChange(
        type: HomeworkType,
        stageRange: StageRange?,
        deadlineMillis: Long?,
        creatorRole: CreatorRole,
        startEpochDay: Long = 0L,
    ): HomeworkValidation = when {
        type == HomeworkType.STAGE && stageRange == null ->
            HomeworkValidation.Invalid(HomeworkValidationError.MISSING_STAGE_RANGE)

        type == HomeworkType.STAGE && creatorRole == CreatorRole.PARENT && deadlineMillis == null ->
            HomeworkValidation.Invalid(HomeworkValidationError.MISSING_STAGE_DEADLINE)

        type == HomeworkType.STAGE && deadlineMillis != null &&
            !stageRangeWithinDeadline(startEpochDay, stageRange?.days ?: 1, deadlineMillis) ->
            HomeworkValidation.Invalid(HomeworkValidationError.DEADLINE_EXCEEDED)

        else -> HomeworkValidation.Valid
    }

    /**
     * 阶段范围（自 [startEpochDay] 起 [days] 天）覆盖的最后一天是否不晚于 [deadlineMillis] 所在日。
     * deadline 为空视为无约束；口径与 [HomeworkTemplate.fitsWithinDeadline] 一致。
     */
    fun stageRangeWithinDeadline(
        startEpochDay: Long,
        days: Int,
        deadlineMillis: Long?,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val deadline = deadlineMillis ?: return true
        val deadlineEpochDay = epochDayOf(deadline, zoneId)
        return startEpochDay + (days - 1L) <= deadlineEpochDay
    }

    // ---- 业务时间口径 ----

    /**
     * 时刻 -> 自然日（epochDay），一律按业务时区折算。
     *
     * data 层（阶段范围覆盖校验）与 ui 层（各页面「今天」）共用本函数，
     * 避免出现 `currentTimeMillis() / 86_400_000` 这类 UTC 折算——
     * 那会让 UTC+8 的凌晨（00:00-07:59）取到昨天，且与页面展示口径不一致。
     */
    fun epochDayOf(millis: Long, zoneId: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().toEpochDay()

    /** 预估时长合法性（正整数且位于 [HomeworkConstants] 允许区间内） */
    fun validateEstimatedMinutes(minutes: Int?): HomeworkValidation =
        if (minutes != null && minutes in HomeworkConstants.MIN_ESTIMATED_MINUTES..
            HomeworkConstants.MAX_ESTIMATED_MINUTES
        ) {
            HomeworkValidation.Valid
        } else {
            HomeworkValidation.Invalid(HomeworkValidationError.INVALID_ESTIMATED_MINUTES)
        }

    private fun failure(error: HomeworkValidationError): Result<ValidatedTemplate> =
        Result.failure(HomeworkValidationException(error))
}

/** 模板校验失败时携带的机器可读原因（经 Result 传递，业务层按 [error] 映射文案） */
class HomeworkValidationException(val error: HomeworkValidationError) : Exception(error.name)