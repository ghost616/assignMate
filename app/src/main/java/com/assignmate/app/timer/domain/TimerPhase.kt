package com.assignmate.app.timer.domain

/**
 * 计时阶段：timer 模块状态机与 UI 的统一口径（纯领域枚举，无 Android 依赖）。
 *
 * 与持久化的关系：库表 timer_session.status 只保存 [RUNNING] / [PAUSED] / [FINISHED]
 * 三种「会话状态」（见 [PERSISTED]）；[IDLE]（尚未开始）与 [RESTING]（完成后休息中）
 * 是页面级阶段，不落库——[IDLE] 表现为「该作业无未结束会话」，[RESTING] 由休息页自身的倒计时状态表达。
 *
 * 合法流转（见 [allowedTransitions] / [canTransitionTo]，纯函数集中可单测）：
 * - [IDLE] → [RUNNING]（开始计时，仓库落一条 RUNNING 会话）；
 * - [RUNNING] ↔ [PAUSED]（「有事走开」/「我回来啦」）；
 * - [RUNNING] / [PAUSED] → [FINISHED]（完成作业：会话收尾 + 作业置已完成）；
 * - [FINISHED] → [RESTING]（进入 10 分钟休息）；
 * - [FINISHED] / [RESTING] → [IDLE]（回清单或开始下一项，回到无会话阶段）。
 *
 * 持久化约定：status 列以本枚举 name 字符串存储，新增取值无需迁移表结构。
 */
enum class TimerPhase {

    /** 未开始：该作业尚无未结束的计时会话 */
    IDLE,

    /** 进行中：会话已开始且未暂停，走秒累计中 */
    RUNNING,

    /** 已暂停：「有事走开」，暂停期间不计入已用时长 */
    PAUSED,

    /** 已完成：会话已收尾（写入结束时刻与暂停汇总），作业同步为已完成 */
    FINISHED,

    /** 休息中：完成作业后的 10 分钟休息倒计时（不落库，属休息页状态） */
    RESTING,
    ;

    /** 用户可读的中文标签（计时页与状态说明统一使用） */
    val label: String
        get() = when (this) {
            IDLE -> "未开始"
            RUNNING -> "进行中"
            PAUSED -> "已暂停"
            FINISHED -> "已完成"
            RESTING -> "休息中"
        }

    /** 是否为「未结束的会话阶段」（走秒中或暂停中，二者都属于尚未收尾的会话） */
    val isActive: Boolean get() = this == RUNNING || this == PAUSED

    /** 是否为终态（会话已收尾，不可再暂停/恢复） */
    val isTerminal: Boolean get() = this == FINISHED

    /** 本阶段可直接流转到的阶段集合（不含自身） */
    val allowedTransitions: Set<TimerPhase> get() = TRANSITIONS.getValue(this)

    /** 目标阶段是否为合法流转（含自身，便于幂等更新） */
    fun canTransitionTo(target: TimerPhase): Boolean =
        target == this || target in allowedTransitions

    /**
     * 落库用的状态字符串（库内 `status` 列的写入口径）。
     *
     * 仅 [PERSISTED] 阶段可落库：其它阶段（[IDLE] / [RESTING] 属页面级阶段）调用即抛
     * [IllegalArgumentException]，把「库内 status 取值域」从人工约定变成**可执行约束**——
     * 脏值在写入源头暴露，而不是读取时被 [fromSessionStatus] 静默按 [FINISHED] 兜底。
     */
    val persistedName: String
        get() {
            require(this in PERSISTED) {
                "阶段 $this 不可落库：库内 status 仅支持 $PERSISTED"
            }
            return name
        }

    companion object {

        /** 新作业进入计时页时的初始阶段 */
        val INITIAL: TimerPhase = IDLE

        /** 可落库的会话状态（库内 status 列取值域） */
        val PERSISTED: Set<TimerPhase> = setOf(RUNNING, PAUSED, FINISHED)

        /** 字符串安全解析（null/未知取值均返回 null，避免脏值导致崩溃） */
        fun fromName(name: String?): TimerPhase? =
            name?.let { raw -> entries.firstOrNull { it.name == raw } }

        /**
         * 解析会话的持久化状态：库内理论上只可能是 [PERSISTED] 三值，
         * 脏值统一按 [FINISHED] 兜底（已收尾口径不会再累计走秒，比按「进行中」处理更安全）。
         */
        fun fromSessionStatus(name: String?): TimerPhase = fromName(name) ?: FINISHED

        private val TRANSITIONS: Map<TimerPhase, Set<TimerPhase>> = mapOf(
            IDLE to setOf(RUNNING),
            RUNNING to setOf(PAUSED, FINISHED),
            PAUSED to setOf(RUNNING, FINISHED),
            FINISHED to setOf(RESTING, IDLE),
            RESTING to setOf(IDLE, RUNNING),
        )
    }
}
