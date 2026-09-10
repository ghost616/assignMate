package com.assignmate.app.timer.data

/**
 * 计时走秒前台服务的启停契约（UI 层经此控制服务，不直接触碰 Service/Intent，便于测试替身）。
 *
 * 语义：
 * - [startTicker]：开始/继续走秒（会话进入 RUNNING），服务转为前台并展示常驻通知；
 * - [markPaused]：冻结走秒（「有事走开」），通知展示冻结后的已用时；
 * - [markRunning]：解除冻结（「我回来啦」），按新的暂停累计继续走秒；
 * - [stopTicker]：停止服务并移除通知（作业完成或退出计时页时调用）。
 *
 * 每次调用都携带完整的 [TimerTickerInfo]（而非增量状态），因此即使进程/服务被系统回收后
 * 再次收到暂停/恢复指令，也能用最近一次上报的基准重建通知内容。
 */
interface TimerTickerController {

    /** 开始走秒（会话 RUNNING） */
    fun startTicker(info: TimerTickerInfo)

    /** 冻结走秒（会话 PAUSED） */
    fun markPaused(info: TimerTickerInfo)

    /** 解除冻结（会话重新 RUNNING） */
    fun markRunning(info: TimerTickerInfo)

    /** 停止服务并移除常驻通知 */
    fun stopTicker()
}

/**
 * 走秒通知所需的会话基准信息。
 *
 * 已用时长口径与 [com.assignmate.app.timer.domain.TimerCalculations.elapsedMillis] 一致：
 * `参考时刻 - 开始时刻 - 暂停累计`；服务在跑秒时用自身时钟取「现在」，
 * 暂停时改用 [frozenAtMillis] 冻结（避免暂停期间通知还在涨秒）。
 *
 * @param pausedTotalMillis 截至参考时刻的累计暂停毫秒（不含 [frozenAtMillis] 之后的部分）
 * @param frozenAtMillis 冻结时刻（暂停开始时刻）；为 null 表示走秒中
 */
data class TimerTickerInfo(
    val sessionId: Long,
    val homeworkContent: String,
    val startedAtMillis: Long,
    val pausedTotalMillis: Long,
    val frozenAtMillis: Long? = null,
) {

    /** 是否处于暂停冻结状态 */
    val isPaused: Boolean get() = frozenAtMillis != null
}
