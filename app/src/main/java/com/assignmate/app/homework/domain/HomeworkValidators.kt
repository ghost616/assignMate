package com.assignmate.app.homework.domain

import com.assignmate.app.auth.domain.Role
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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

    /** 家长录入的阶段作业未设「每日截止时刻」 */
    MISSING_STAGE_DEADLINE,

    /** 开始时间 + 预估时长晚于截止时间（当天作业的单日截止时间；或阶段作业的当日截止时刻） */
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
 * 1. 截止时间约束（**按作业类型分两种语义**）：
 *    - 当天作业（TODAY）：绝对时刻语义——开始时间 + 预估时长不得晚于 deadline（[validateDeadline]）；
 *    - 阶段作业（STAGE）：**每日时刻**语义——某天的「开始时刻 + 预估时长」不得跨过「该天的截止时刻」
 *      （[validateScheduleWithinDailyDeadline]），**不再有「阶段覆盖末日 ≤ deadline 所在日」这条旧规则**；
 * 2. 时间段防冲突：候选时间段与同学生其它已排定作业不重叠（排除自身）；
 * 3. 权限规则：两个维度刻意分开——
 *    改删权（[canModify]/[canDelete]）只看录入者角色：学生仅可改删自己新增项，家长可改删全部；
 *    调序权（[canReorder]）在改删权之上补「作业归属学生」校验（学生仅可调序本人名下、自己录入的作业）；
 *    执行权（[canOperate]）只看作业归属：时间排定与状态流转面向作业的执行者，与录入者角色无关；
 * 4. 状态流转合法性：见 [HomeworkStatus.allowedTransitions]。
 *
 * 所有方法均为纯函数：不读时钟、不访问数据库，时间来源由调用方（仓库/ViewModel）注入。
 */
object HomeworkValidators {

    // ---- 1. 截止时间约束 ----

    /**
     * 校验候选时间段是否满足**当天作业**的 deadline 约束：开始时间 + 预估时长不得晚于 [deadline]。
     * deadline 为空视为无约束；正好等于视为通过。
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

    /**
     * 校验候选开始时刻与预估时长是否满足**阶段作业的每日截止时刻**：
     * 从开始时刻起的时长不得跨过「当日截止时刻」。
     *
     * 语义说明：阶段作业既没有「阶段末日」约束（已推翻），也不允许把一段常规时长的作业跨过当日截止时刻——
     * 若只比对钟面时刻，「21:00 开始 600 分钟」这类跨日取值会被钟面比较误判为通过，
     * 故按「开始时刻 + 时长」的绝对瞬时与「当天截止时刻」的绝对瞬时比较（严格晚于即失败，等于通过）。
     *
     * @param startMillis 候选开始时刻（epoch 毫秒）
     * @param estimatedMinutes 预估时长（分钟）
     * @param dailyDeadlineTime 阶段作业的每日截止时刻；为 null 视为无约束（学生录入可留空）
     * @param zoneId 业务时区（把钟面时刻折算为绝对瞬时的唯一口径）
     */
    fun validateScheduleWithinDailyDeadline(
        startMillis: Long,
        estimatedMinutes: Int,
        dailyDeadlineTime: LocalTime?,
        zoneId: ZoneId,
    ): HomeworkValidation {
        if (dailyDeadlineTime == null) {
            return HomeworkValidation.Valid
        }
        val start = Instant.ofEpochMilli(startMillis).atZone(zoneId)
        val deadlineMillis = start.toLocalDate().atTime(dailyDeadlineTime).atZone(zoneId).toInstant().toEpochMilli()
        return validateDeadline(
            startMillis = startMillis,
            estimatedMinutes = estimatedMinutes,
            deadlineMillis = deadlineMillis,
        )
    }

    /**
     * 校验候选开始时刻与预估时长是否满足**某条作业**的截止约束（按类型分流的单一入口）：
     * - 当天作业（TODAY）：绝对 deadline 语义（[validateDeadline]）；
     * - 阶段作业（STAGE）：该天「每日截止时刻」语义（[validateScheduleWithinDailyDeadline]），
     *   以**开始时刻所在的自然日**为「该天」，与仓库 updateSchedule 判定完全一致。
     *
     * 存在意义：两类语义共存于 deadline 一列，各消费方若各自解释同一列就会互相矛盾——
     * 时间设定页曾用绝对时刻口径预校验阶段作业（deadline = timeOfDayCarrier ≈ 75_600_000 ms），
     * 导致阶段作业在「设时间/改时间」页永远提交不了（UI 先拦、仓库本会放行）。
     *
     * @param item 目标作业（提供类型、每日时刻与绝对 deadline）
     * @param startMillis 候选开始时刻（epoch 毫秒）
     * @param estimatedMinutes 预估时长（分钟）
     * @param zoneId 业务时区
     */
    fun validateScheduleWithinItemDeadline(
        item: HomeworkItem,
        startMillis: Long,
        estimatedMinutes: Int,
        zoneId: ZoneId,
    ): HomeworkValidation = if (item.isStage) {
        validateScheduleWithinDailyDeadline(
            startMillis = startMillis,
            estimatedMinutes = estimatedMinutes,
            dailyDeadlineTime = item.dailyDeadlineTime,
            zoneId = zoneId,
        )
    } else {
        validateDeadline(
            startMillis = startMillis,
            estimatedMinutes = estimatedMinutes,
            deadlineMillis = item.deadline?.toEpochMilli(),
        )
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

    /**
     * 是否可调整优先级：在改删权（[canModify]，学生不可调家长录入项）之上再补「作业归属学生」维度。
     *
     * 归属维度的必要性：改删权只比对录入者角色，故「学生录入但归属另一个学生」的作业会被误判为可调序，
     * 形成跨学生越权面（同一作业在时间维度 [canOperate] 已返回拒绝，两个口径必须一致）。
     * - 家长：可调序名下学生的全部作业（「名下」由仓库层统一归属判定圈定：经 auth 的
     *   `AuthRepository.isStudentOwnedBy` 校验「作业所属学生属于当前家长」）；
     * - 学生：必须同时满足「自己录入」与「本人名下」（[HomeworkItem.studentId] == [sessionStudentId]），
     *   会话未携带 studentId 时一律拒绝。
     */
    fun canReorder(
        item: HomeworkItem,
        sessionRole: Role,
        sessionStudentId: Long?,
    ): Boolean = when (sessionRole) {
        Role.PARENT -> canModify(item, sessionRole)
        Role.STUDENT -> canModify(item, sessionRole) &&
            sessionStudentId != null && item.studentId == sessionStudentId
    }

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
     * - 阶段作业必须选阶段范围；
     * - 家长录入的阶段作业必须设**每日截止时刻**（time-of-day 语义，不再要求日期；
     *   旧规则「阶段覆盖最后一天不得晚于 deadline 所在日」已推翻，见类注释）。
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
            if (template.creatorRole == CreatorRole.PARENT && template.dailyDeadlineTime == null) {
                return failure(HomeworkValidationError.MISSING_STAGE_DEADLINE)
            }
        }
        return Result.success(ValidatedTemplate(template))
    }

    /**
     * 由既有作业项与新的类型/阶段范围/deadline 推导出的候选值是否合法（家长修改场景）。
     *
     * 与 [validateTemplate] 同一套规则：阶段作业必须带阶段范围、家长录入的阶段作业必须设每日截止时刻；
     * 旧的「阶段覆盖末日不得晚于 deadline 所在日」约束已推翻（阶段 deadline 只表达每日时刻）。
     *
     * @param dailyDeadlineTime 候选的每日截止时刻（阶段作业；由新的 deadline 解码得到）
     */
    fun validateTypeChange(
        type: HomeworkType,
        stageRange: StageRange?,
        creatorRole: CreatorRole,
        dailyDeadlineTime: LocalTime?,
    ): HomeworkValidation = when {
        type == HomeworkType.STAGE && stageRange == null ->
            HomeworkValidation.Invalid(HomeworkValidationError.MISSING_STAGE_RANGE)

        type == HomeworkType.STAGE && creatorRole == CreatorRole.PARENT && dailyDeadlineTime == null ->
            HomeworkValidation.Invalid(HomeworkValidationError.MISSING_STAGE_DEADLINE)

        else -> HomeworkValidation.Valid
    }

    // ---- 业务时间口径 ----

    /**
     * 时刻 -> 自然日（epochDay），一律按业务时区折算。
     *
     * data 层（阶段覆盖日 / 当天作业归属日）与 ui 层（各页面「今天」）共用本函数，
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
