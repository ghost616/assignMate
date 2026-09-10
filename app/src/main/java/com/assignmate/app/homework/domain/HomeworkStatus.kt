package com.assignmate.app.homework.domain

/**
 * 作业状态机：已记录 → 待完成 → 进行中 → 已完成。
 *
 * 合法流转（见 [allowedTransitions] / [canTransitionTo]，纯函数 [HomeworkValidators] 集中校验）：
 * - 初始状态固定为 [RECORDED]（作业刚录入、尚未排定时间）；
 * - [RECORDED] → [PENDING]（排定开始时间与预估时长后确认）；
 * - [PENDING] → [RECORDED]（撤销排定，回到仅记录状态）；
 * - [PENDING] ↔ [IN_PROGRESS]、[IN_PROGRESS] ↔ [COMPLETED]；
 * - [PENDING] → [COMPLETED]（允许不经过计时直接标记完成）；
 * - 终态 [COMPLETED] 仅可回退到 [IN_PROGRESS]（纠正误标记）。
 *
 * 持久化约定：homework_item.status 列以本枚举 name 字符串存储。
 */
enum class HomeworkStatus {

    /** 已记录：刚录入，尚未排定开始时间 */
    RECORDED,

    /** 待完成：已排定开始时间与预估时长，等待开始 */
    PENDING,

    /** 进行中：已开始（timer 模块后续经仓库入口写入） */
    IN_PROGRESS,

    /** 已完成：终态（仅允许回退为 [IN_PROGRESS] 以纠正误操作） */
    COMPLETED,
    ;

    /** 用户可读的中文标签 */
    val label: String
        get() = when (this) {
            RECORDED -> "已记录"
            PENDING -> "待完成"
            IN_PROGRESS -> "进行中"
            COMPLETED -> "已完成"
        }

    /** 是否终态（已完成视为终态） */
    val isTerminal: Boolean get() = this == COMPLETED

    /** 本状态可直接流转到的状态集合（不含自身） */
    val allowedTransitions: Set<HomeworkStatus> get() = TRANSITIONS.getValue(this)

    /** 目标状态是否为合法流转（含自身，便于幂等更新） */
    fun canTransitionTo(target: HomeworkStatus): Boolean =
        target == this || target in allowedTransitions

    companion object {

        /** 录入新作业时的初始状态 */
        val INITIAL: HomeworkStatus = RECORDED

        private val TRANSITIONS: Map<HomeworkStatus, Set<HomeworkStatus>> = mapOf(
            RECORDED to setOf(PENDING),
            PENDING to setOf(RECORDED, IN_PROGRESS, COMPLETED),
            IN_PROGRESS to setOf(PENDING, COMPLETED),
            COMPLETED to setOf(IN_PROGRESS),
        )

        /** 字符串安全解析（null/未知取值均返回 null） */
        fun fromName(name: String?): HomeworkStatus? =
            name?.let { raw -> entries.firstOrNull { it.name == raw } }
    }
}