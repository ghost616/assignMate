package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidationError
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * 作业仓库接口：作业项全生命周期（录入 / 清单查询 / 优先级调整 / 时间排定 / 内容与类型修改 / 删除 / 状态流转）。
 *
 * 实现契约（仓库层兜底，避免绕过 UI 直接调用导致规则失效）：
 * - 数据范围：按学生维度收敛（studentId），家长归属 id 由 auth 当前会话提供；
 * - 权限规则：学生仅可修改/删除/调整自己录入的作业（created_by_role = STUDENT），家长可改删全部；
 *   权限不符统一返回 PermissionDenied 类结果（不抛异常）；
 * - 时间校验：设定开始时间与预估时长时先校验 deadline 约束与同学生时间段防冲突（排除自身），
 *   失败返回机器可读原因供 UI 提示；
 * - 状态流转：录入时为「已记录」；markPending / startProgress / complete / reopen
 *   仅允许 HomeworkStatus 定义的合法流转，非法流转返回 [HomeworkStatusResult.IllegalTransition]；
 * - 所有方法不抛业务异常，成败统一收敛为密封结果（reason 枚举机器可读，用户文案由 UI 层映射）。
 */
interface HomeworkRepository {

    // ---- 查询 ----

    /** 观察某学生作业清单（按优先级升序，同优先级按创建时间升序，与 DAO 排序一致） */
    fun observeHomework(studentId: Long): Flow<List<HomeworkItem>>

    /** 读取某学生作业清单快照（按优先级升序） */
    suspend fun listHomework(studentId: Long): List<HomeworkItem>

    /** 按 id 读取作业项（不存在返回 null） */
    suspend fun getHomework(homeworkId: Long): HomeworkItem?

    // ---- 录入 ----

    /**
     * 新增作业：按 [HomeworkTemplate] 展开为一条或多条作业项（阶段作业逐日一条），
     * 初始状态为「已记录」、未排定开始时间，优先级追加到清单末尾。
     *
     * 归属校验：家长归属 id 取当前会话；学生会话下 [studentId] 必须等于会话学生 id，
     * 否则返回 [AddHomeworkResult.NoActiveSession]（防止绕过 UI 指向他人学生）。
     */
    suspend fun addHomework(template: HomeworkTemplate, studentId: Long): AddHomeworkResult

    // ---- 优先级 ----

    /** 上移/下移一位（与相邻项交换优先级）；已在边界时为幂等成功 */
    suspend fun reorderHomework(
        homeworkId: Long,
        direction: ReorderDirection,
        sessionRole: Role,
    ): HomeworkOrderResult

    /** 落位到指定位置（0 起，越界自动收敛），其余项顺延并整体重排优先级 */
    suspend fun moveHomeworkTo(
        homeworkId: Long,
        targetIndex: Int,
        sessionRole: Role,
    ): HomeworkOrderResult

    // ---- 时间排定 ----

    /**
     * 设定开始时间与预估时长：先校验 deadline 约束与时间段防冲突（排除自身），
     * 通过后写入并将状态推进到「待完成」（已记录 → 待完成）。
     *
     * 权限（执行权，见 HomeworkValidators.canOperate）：家长可排定名下学生全部作业；
     * 学生可排定**本人名下**全部作业（含家长布置的），否则「家长布置 → 学生计时完成」主闭环不可用。
     * 已完成作业不可再排定（返回 ScheduleUpdateResult.StatusTransitionDenied，保留历史排定），
     * 如需重新排定请先撤销完成（clearSchedule 会把已完成回退为进行中）。
     */
    suspend fun updateSchedule(
        homeworkId: Long,
        startTime: Instant,
        estimatedMinutes: Int,
        sessionRole: Role,
    ): ScheduleUpdateResult

    /**
     * 撤销时间排定（开始时间与预估时长置空）：待完成回退为「已记录」，
     * 已完成回退为「进行中」（保证撤销后仍可重新排定，不留死局）；权限同 updateSchedule。
     */
    suspend fun clearSchedule(homeworkId: Long, sessionRole: Role): HomeworkOperationResult

    // ---- 内容 / 类型 ----

    /** 修改作业内容（家长可改全部，学生仅可改自己录入的） */
    suspend fun updateContent(
        homeworkId: Long,
        content: String,
        sessionRole: Role,
    ): HomeworkOperationResult

    /**
     * 修改作业类型 / 阶段范围 / 截止时间（家长专属）：改为阶段作业必须带阶段范围，
     * 家长录入的阶段作业必须设 deadline（校验不过返回 [HomeworkOperationResult.TemplateInvalid]）；
     * 学生会话调用一律返回 [HomeworkOperationResult.PermissionDenied]。
     */
    suspend fun updateTemplate(
        homeworkId: Long,
        type: HomeworkType,
        stageRange: StageRange?,
        deadline: Instant?,
        sessionRole: Role,
    ): HomeworkOperationResult

    // ---- 删除 ----

    /** 删除作业项（家长可删全部，学生仅可删自己录入的） */
    suspend fun deleteHomework(homeworkId: Long, sessionRole: Role): HomeworkOperationResult

    // ---- 状态流转 ----

    /**
     * 已记录 → 待完成（时间已排定时由 [updateSchedule] 自动推进，本入口供显式确认）。
     * 权限（执行权）：学生可推进本人名下作业（含家长布置的），家长可推进名下学生全部作业。
     */
    suspend fun markPending(homeworkId: Long, sessionRole: Role): HomeworkStatusResult

    /** 开始作业：待完成 → 进行中（timer 模块后续经此入口写入） */
    suspend fun startProgress(homeworkId: Long, sessionRole: Role): HomeworkStatusResult

    /** 完成作业：待完成/进行中 → 已完成 */
    suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult

    /** 撤销完成：已完成 → 进行中（纠正误标记） */
    suspend fun reopen(homeworkId: Long, sessionRole: Role): HomeworkStatusResult
}

/** 优先级调整方向 */
enum class ReorderDirection {

    /** 上移一位（更靠前、更紧急） */
    UP,

    /** 下移一位（更靠后） */
    DOWN,
}

/** 新增作业结果 */
sealed class AddHomeworkResult {

    /** 新增成功：返回落库后的全部作业项（阶段作业为逐日多条） */
    data class Success(val items: List<HomeworkItem>) : AddHomeworkResult()

    /** 模板校验失败（内容为空/超长、缺阶段范围、家长阶段作业缺 deadline 等） */
    data class TemplateInvalid(val error: HomeworkValidationError) : AddHomeworkResult()

    /** 无有效会话（缺少家长归属维度；或目标学生不在会话可见范围内） */
    data object NoActiveSession : AddHomeworkResult()
}

/** 优先级调整结果 */
sealed class HomeworkOrderResult {

    /** 调整成功（已在边界时的幂等场景同样返回本结果） */
    data object Success : HomeworkOrderResult()

    /** 作业不存在（可能已被删除） */
    data object NotFound : HomeworkOrderResult()

    /** 权限不足（学生尝试调整家长录入项的优先级） */
    data object PermissionDenied : HomeworkOrderResult()
}

/** 时间排定结果 */
sealed class ScheduleUpdateResult {

    /**
     * 排定成功：返回更新后的作业项（已记录推进为待完成；待完成/进行中仅更新排定时间不变状态）。
     * 已完成作业不可再排定（见 [StatusTransitionDenied]），需先撤销完成。
     */
    data class Success(val item: HomeworkItem) : ScheduleUpdateResult()

    /** 作业不存在 */
    data object NotFound : ScheduleUpdateResult()

    /** 执行权不足（学生尝试排定他人名下作业的时间，见 [HomeworkValidators.canOperate]） */
    data object PermissionDenied : ScheduleUpdateResult()

    /** 开始时间 + 预估时长晚于截止时间 */
    data object DeadlineExceeded : ScheduleUpdateResult()

    /** 与同学生其它已排定作业时间段重叠 */
    data object TimeConflict : ScheduleUpdateResult()

    /** 预估时长不合法（非正整数或超出允许区间） */
    data object InvalidEstimatedMinutes : ScheduleUpdateResult()

    /** 状态流转不合法（如已完成作业不允许再排定时间） */
    data object StatusTransitionDenied : ScheduleUpdateResult()
}

/** 通用作业操作结果（内容修改 / 类型修改 / 删除 / 撤销排定） */
sealed class HomeworkOperationResult {

    /** 操作成功：返回更新后的作业项（删除成功时为删除前快照） */
    data class Success(val item: HomeworkItem) : HomeworkOperationResult()

    /** 作业不存在 */
    data object NotFound : HomeworkOperationResult()

    /** 权限不足 */
    data object PermissionDenied : HomeworkOperationResult()

    /** 模板校验失败（类型/阶段范围/deadline 修改） */
    data class TemplateInvalid(val error: HomeworkValidationError) : HomeworkOperationResult()

    /** 内容不合法（空白或超长） */
    data class ContentInvalid(val error: HomeworkValidationError) : HomeworkOperationResult()
}

/** 状态流转结果 */
sealed class HomeworkStatusResult {

    /** 流转成功：返回更新后的作业项 */
    data class Success(val item: HomeworkItem) : HomeworkStatusResult()

    /** 作业不存在 */
    data object NotFound : HomeworkStatusResult()

    /** 权限不足 */
    data object PermissionDenied : HomeworkStatusResult()

    /** 非法状态流转（不在 HomeworkStatus 允许的流转集合内） */
    data class IllegalTransition(
        val from: HomeworkStatus,
        val to: HomeworkStatus,
    ) : HomeworkStatusResult()
}