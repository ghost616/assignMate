package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
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
 * - 家长归属围栏（全部「按 id 入口」统一叠加，实现为 HomeworkRepositoryImpl 的私有统一判定）：
 *   目标作业所属学生必须在当前会话可见范围内——家长会话经 auth 的 `AuthRepository.isStudentOwnedBy`
 *   判定为「本人名下学生」，学生会话沿用「仅本人名下」；判定顺序为「会话有效性 + 权限维度在前、
 *   归属维随后」，失败一律返回各自 PermissionDenied 类结果且不改动任何数据；
 * - 时间校验：设定开始时间与预估时长时先校验 deadline 约束与同学生时间段防冲突（排除自身），
 *   失败返回机器可读原因供 UI 提示；
 * - 状态流转：录入时为「已记录」；markPending / startProgress / complete / reopen
 *   仅允许 HomeworkStatus 定义的合法流转，非法流转返回 [HomeworkStatusResult.IllegalTransition]；
 * - 进行中锁定：作业进入「进行中」后不得再调整优先级与时间排定
 *   （reorderHomework / moveHomeworkTo / updateSchedule / clearSchedule 一律拒绝，
 *   分别返回 [HomeworkOrderResult.LockedWorkInProgress] / [ScheduleUpdateResult.LockedWorkInProgress] /
 *   [HomeworkOperationResult.LockedWorkInProgress]，均不抛异常，且不改动任何数据）；
 *   内容修改、类型修改与删除不受该锁定约束（沿用各自权限维度）；
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

    /**
     * 读取某学生**全部作业的每天详情**（键 = 作业 id，值 = 该作业按自然日升序的全部天详情）。
     *
     * 数据来源是 core 的「作业每天详情」契约（[com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository]），
     * homework 模块不直接访问 Room DAO。清单页据此推导「今日状态」与阶段进度（已打卡 N/M 天）。
     *
     * 默认返回空表：仅依赖清单快照的既有调用方/替身无需实现本方法即可编译。
     */
    suspend fun dailyRecordsOf(studentId: Long): Map<Long, List<HomeworkDailyRecord>> = emptyMap()

    /** 读取单条作业的全部每天详情（按自然日升序；无记录返回空表） */
    suspend fun dailyRecords(homeworkId: Long): List<HomeworkDailyRecord> = emptyList()

    // ---- 录入 ----

    /**
     * 新增作业：按 [HomeworkTemplate] 落库**恰好 1 条**作业项（阶段作业同样只 1 条，
     * 阶段范围只决定覆盖起止日与进度分母），初始状态为「已记录」、未排定开始时间，优先级追加到清单末尾。
     *
     * 归属校验：家长归属 id 取当前会话；学生会话下 [studentId] 必须等于会话学生 id，
     * 否则返回 [AddHomeworkResult.NoActiveSession]（防止绕过 UI 指向他人学生）。
     */
    suspend fun addHomework(template: HomeworkTemplate, studentId: Long): AddHomeworkResult

    // ---- 优先级 ----

    /**
     * 上移/下移一位（与相邻项交换优先级）；已在边界时为幂等成功。
     *
     * 进行中锁定：状态为「进行中」的作业不允许再调整优先级（返回
     * [HomeworkOrderResult.LockedWorkInProgress]），避免与计时链路已确认的执行顺序冲突；
     * 「已完成」保持既有约束（可调序，仅改删权受限）。
     */
    suspend fun reorderHomework(
        homeworkId: Long,
        direction: ReorderDirection,
        sessionRole: Role,
    ): HomeworkOrderResult

    /** 落位到指定位置（0 起，越界自动收敛），其余项顺延并整体重排优先级；进行中同样被锁定（见 [reorderHomework]） */
    suspend fun moveHomeworkTo(
        homeworkId: Long,
        targetIndex: Int,
        sessionRole: Role,
    ): HomeworkOrderResult

    // ---- 时间排定 ----

    /**
     * 设定开始时间与预估时长：先校验截止时间约束与时间段防冲突（排除自身），
     * 通过后写入并将状态推进到「待完成」（已记录 → 待完成）。
     *
     * 截止时间按作业类型分两种语义（见 [com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec]）：
     * - 当天作业：开始时间 + 预估时长不得晚于绝对 deadline；
     * - 阶段作业：开始时刻 + 预估时长不得跨过「该天每日截止时刻」。
     *
     * 权限（执行权，见 HomeworkValidators.canOperate）：家长可排定名下学生全部作业；
     * 学生可排定**本人名下**全部作业（含家长布置的），否则「家长布置 → 学生计时完成」主闭环不可用。
     *
     * 进行中锁定：状态为「进行中」的作业不允许再调整时间（返回
     * [ScheduleUpdateResult.LockedWorkInProgress]），保留计时开始时刻这一历史事实；
     * 已完成作业同样不可再排定（返回 [ScheduleUpdateResult.StatusTransitionDenied]），
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
     *
     * 进行中锁定：状态为「进行中」的作业不允许撤销排定（返回
     * [HomeworkOperationResult.LockedWorkInProgress]）——排定时间段是计时与防冲突校验的依据，
     * 进行中撤销会破坏计时链路口径；如需撤销请先标记完成（或按家长/学生各自权限撤销完成）。
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
     * 家长录入的阶段作业必须设**每日截止时刻**（阶段作业的 deadline 只承载 time-of-day，
     * 由 [com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec] 编解码；
     * 校验不过返回 [HomeworkOperationResult.TemplateInvalid]）；
     * 新截止取值还必须容纳既有排定时间段（阶段按该天截止时刻、当天按绝对 deadline）；
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

    /**
     * 标记完成：**按类型分流**（阶段作业「当天完成 ≠ 整条完成」，本模块对外契约之一）——
     * - 当天作业（TODAY）：完成即整条置 [HomeworkStatus.COMPLETED]（语义不变）；
     * - 阶段作业（STAGE）：只把**今天**记入「作业每天详情」，整条状态按每天进度收敛：
     *   阶段覆盖日全部完成才置 [HomeworkStatus.COMPLETED]；仍有未完成的天则回到
     *   [HomeworkStatus.PENDING]（「整条还没走完」）——该状态在 timer 的可开始集合
     *   （`STARTABLE_STATUSES = {PENDING, IN_PROGRESS}`）内，故**阶段范围内每一天都可直接开始计时，
     *   不需要先「撤销完成」**；阶段已结束但仍有未完成天时同样不置已完成（由清单页归入「已结束」）。
     *   今天不在阶段覆盖区间内（阶段尚未开始或已结束）时返回
     *   [HomeworkStatusResult.IllegalTransition]，且不写入每天详情。
     */
    suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult

    /**
     * 撤销完成：当天作业为「已完成 → 进行中」；阶段作业撤销的是**今天**的完成记录
     * （今天回到「未开始」并清空执行数据，整条回到 [HomeworkStatus.PENDING]），今天可重新开始计时。
     * 阶段作业今天没有完成记录时返回 [HomeworkStatusResult.IllegalTransition]。
     */
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

    /** 新增成功：返回落库后的作业项（阶段作业同样只 1 条） */
    data class Success(val items: List<HomeworkItem>) : AddHomeworkResult()

    /** 模板校验失败（内容为空/超长、缺阶段范围、家长阶段作业缺每日截止时刻等） */
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

    /**
     * 作业已进入「进行中」：按进行中锁定规则拒绝调整优先级（不改动任何数据），
     * 与权限无关，故单独成一个可读原因供 UI 精确提示。
     */
    data object LockedWorkInProgress : HomeworkOrderResult()
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

    /**
     * 作业已进入「进行中」：按进行中锁定规则拒绝重新排定时间（保留原排定），
     * 与「已完成」的 [StatusTransitionDenied] 区分，便于 UI 给出「已开始，无法改时间」的精确提示。
     */
    data object LockedWorkInProgress : ScheduleUpdateResult()
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

    /**
     * 作业已进入「进行中」：按进行中锁定规则拒绝该操作（当前用于撤销时间排定），
     * 与权限无关，单独成因供 UI 精确提示。
     */
    data object LockedWorkInProgress : HomeworkOperationResult()
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