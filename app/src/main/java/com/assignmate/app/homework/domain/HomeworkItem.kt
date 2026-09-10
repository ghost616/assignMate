package com.assignmate.app.homework.domain

import java.time.Duration
import java.time.Instant

/**
 * 作业项领域模型（data 层与 core 的 homework_item 表互转）。
 *
 * 时间语义：
 * - [deadline] 截止时间：家长录入的阶段作业必填，当天作业可空；
 * - [startTime] 开始时间与 [estimatedMinutes] 预估时长共同构成「已排定时间段」
 *   [startTime, startTime + estimatedMinutes]，用于时间段防冲突校验；
 * - [estimatedFinishedAt] 为推导值，便于展示与冲突排查，不落库。
 *
 * 权限语义：[createdByRole] 记录录入者角色，配合当前会话角色由
 * [HomeworkValidators.canModify] / [HomeworkValidators.canDelete] 判定可操作性。
 */
data class HomeworkItem(
    val id: Long,
    val parentAccountId: Long,
    val studentId: Long,
    val content: String,
    val type: HomeworkType,
    val stageRange: StageRange?,
    val deadline: Instant?,
    val priority: Int,
    val startTime: Instant?,
    val estimatedMinutes: Int?,
    val status: HomeworkStatus,
    val createdByRole: CreatorRole,
    val createdAt: Instant,
) {

    /** 已排定的预计完成时刻（未排定开始时间时为 null） */
    val estimatedFinishedAt: Instant?
        get() = startTime?.let { start ->
            estimatedMinutes?.let { minutes -> start.plus(Duration.ofMinutes(minutes.toLong())) }
        }

    /** 是否为阶段作业 */
    val isStage: Boolean get() = type == HomeworkType.STAGE

    /** 是否已排定开始时间（清单据此区分「待排定/已排定」） */
    val isScheduled: Boolean get() = startTime != null

    /** 是否处于进行中（timer 后续使用） */
    val isInProgress: Boolean get() = status == HomeworkStatus.IN_PROGRESS
}