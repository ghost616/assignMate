package com.assignmate.app.homework.ui

import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkOrderResult
import com.assignmate.app.homework.data.ScheduleUpdateResult
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkValidationError

/**
 * 仓库失败原因 / 校验原因 → 用户可读文案的单一映射点。
 *
 * 约束：机器逻辑（枚举/密封结果）与文案分离，UI 层统一经本文件转换，禁止在页面散落硬编码；
 * 校验原因的文案同时用于「提交前实时提示」与「仓库兜底拒绝提示」，保证前后端口径一致。
 */
internal fun HomeworkValidationError.toUserMessage(): String = when (this) {
    HomeworkValidationError.BLANK_CONTENT -> "请填写作业内容"
    HomeworkValidationError.CONTENT_TOO_LONG -> "作业内容过长，请精简后再提交"
    HomeworkValidationError.MISSING_STAGE_RANGE -> "阶段作业需要选择阶段范围（一周/两周/三周/一个月）"
    HomeworkValidationError.MISSING_STAGE_DEADLINE -> "家长录入的阶段作业需要设置截止时间"
    HomeworkValidationError.DEADLINE_EXCEEDED -> "时间安排不合适：开始时间加预估时长超过了截止时间"
    HomeworkValidationError.INVALID_ESTIMATED_MINUTES -> "预估时长需要是 1-600 之间的整数（分钟）"
    HomeworkValidationError.TIME_CONFLICT -> "这段时间已有其它作业安排，请换一个时间"
    HomeworkValidationError.PERMISSION_DENIED -> "没有权限操作这条作业"
    HomeworkValidationError.ILLEGAL_STATUS_TRANSITION -> "当前状态不支持该操作"
}

/** 录入结果文案（仅失败分支需要；成功由页面导航提示） */
internal fun AddHomeworkResult.toUserMessage(): String = when (this) {
    is AddHomeworkResult.Success -> "已添加到作业清单"
    is AddHomeworkResult.TemplateInvalid -> error.toUserMessage()
    AddHomeworkResult.NoActiveSession -> "会话已失效，请重新登录"
}

/** 优先级调整结果文案 */
internal fun HomeworkOrderResult.toUserMessage(): String = when (this) {
    HomeworkOrderResult.Success -> "顺序已调整"
    HomeworkOrderResult.NotFound -> "作业不存在，可能已被删除"
    HomeworkOrderResult.PermissionDenied -> PERMISSION_DENIED_HINT
    HomeworkOrderResult.LockedWorkInProgress -> WORK_IN_PROGRESS_LOCKED_HINT
}

/** 时间排定结果文案（成功文案包含排定结果说明） */
internal fun ScheduleUpdateResult.toUserMessage(): String = when (this) {
    is ScheduleUpdateResult.Success -> "时间已设定，作业进入「待完成」"
    ScheduleUpdateResult.NotFound -> "作业不存在，可能已被删除"
    ScheduleUpdateResult.PermissionDenied -> EXECUTE_PERMISSION_DENIED_HINT
    ScheduleUpdateResult.DeadlineExceeded -> "时间安排不合适：开始时间加预估时长超过了截止时间"
    ScheduleUpdateResult.TimeConflict -> "这段时间已有其它作业安排，请换一个时间"
    ScheduleUpdateResult.InvalidEstimatedMinutes -> "预估时长需要是 1-600 之间的整数（分钟）"
    ScheduleUpdateResult.StatusTransitionDenied -> "当前状态不支持重新排定时间"
    ScheduleUpdateResult.LockedWorkInProgress -> WORK_IN_PROGRESS_LOCKED_HINT
}

/** 通用操作结果文案（successMessage 由调用方按动作定制） */
internal fun HomeworkOperationResult.toUserMessage(successMessage: String): String = when (this) {
    is HomeworkOperationResult.Success -> successMessage
    HomeworkOperationResult.NotFound -> "作业不存在，可能已被删除"
    HomeworkOperationResult.PermissionDenied -> PERMISSION_DENIED_HINT
    is HomeworkOperationResult.TemplateInvalid -> error.toUserMessage()
    is HomeworkOperationResult.ContentInvalid -> error.toUserMessage()
    HomeworkOperationResult.LockedWorkInProgress -> WORK_IN_PROGRESS_LOCKED_HINT
}

/** 清单卡片信息：类型 + 阶段范围 + 截止时间的组合展示文案 */
internal fun HomeworkItem.typeLabel(): String = when {
    !isStage -> type.label
    stageRange != null -> "${type.label} · ${stageRange.label}"
    else -> type.label
}

/**
 * 改删/调序权限不足提示（学生尝试改删或调序家长录入的作业）：
 * 对应 [com.assignmate.app.homework.domain.HomeworkValidators.canModify] / [canDelete] / [canReorder]。
 */
internal const val PERMISSION_DENIED_HINT = "学生只能修改或删除自己添加的作业"

/**
 * 执行权不足提示（学生尝试排定时间或推进状态于他人名下的作业）：
 * 对应 [com.assignmate.app.homework.domain.HomeworkValidators.canOperate]——
 * 时间排定与状态流转按「是否是本人名下作业」判定，与录入者角色无关。
 */
internal const val EXECUTE_PERMISSION_DENIED_HINT = "只能操作自己名下的作业"

/**
 * 进行中锁定提示（需求假设 C）：作业进入「进行中」后不允许再调整优先级与时间排定，
 * 对应 [com.assignmate.app.homework.data.HomeworkOrderResult.LockedWorkInProgress] /
 * [com.assignmate.app.homework.data.ScheduleUpdateResult.LockedWorkInProgress] /
 * [com.assignmate.app.homework.data.HomeworkOperationResult.LockedWorkInProgress]。
 */
internal const val WORK_IN_PROGRESS_LOCKED_HINT = "作业已开始，不能再调整顺序或时间"