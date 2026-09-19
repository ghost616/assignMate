package com.assignmate.app.timer.domain

/**
 * timer 模块业务常量：休息时长、走秒刷新间隔、超时判定口径等魔法值集中收敛
 * （按项目规范「避免魔法数字」，业务常量留在本模块，不并入 core）。
 */
object TimerConstants {

    // ---- 时间换算 ----
    const val MILLIS_PER_SECOND = 1_000L
    const val MILLIS_PER_MINUTE = 60_000L
    const val MILLIS_PER_HOUR = 3_600_000L

    // ---- 休息倒计时 ----
    /** 完成一项作业后的休息时长（分钟）：需求固定 10 分钟 */
    const val REST_DURATION_MINUTES = 10

    /** 休息时长（毫秒），与 [REST_DURATION_MINUTES] 同源，避免两处口径漂移 */
    const val REST_DURATION_MILLIS = REST_DURATION_MINUTES * MILLIS_PER_MINUTE

    // ---- 走秒 ----
    /**
     * 计时/休息倒计时的刷新间隔（毫秒）：1 秒一跳。
     * 已用时长并非按跳数累加，而是每跳用「当前时刻 - 开始时刻 - 暂停累计」重算，
     * 因此刷新间隔抖动（前台服务被系统调度延迟）不会造成计时误差。
     */
    const val TICK_INTERVAL_MILLIS = MILLIS_PER_SECOND

    // ---- 超时判定口径 ----
    /**
     * 超时判定的宽限时长（分钟）：默认 0，即「已到开始时间 + 预估时长」即视为超时；
     * 单独抽成常量便于后续按家长设置放宽口径，避免改动判定函数与用例。
     */
    const val OVERDUE_GRACE_MINUTES = 0

    /** 超时宽限时长（毫秒），与 [OVERDUE_GRACE_MINUTES] 同源 */
    const val OVERDUE_GRACE_MILLIS = OVERDUE_GRACE_MINUTES * MILLIS_PER_MINUTE

    // ---- 到点提醒（闹钟） ----
    /** 提醒默认提前量（分钟）：0 = 就在排定开始时间提醒 */
    const val REMINDER_LEAD_MINUTES = 0

    /** 提醒提前量（毫秒），与 [REMINDER_LEAD_MINUTES] 同源 */
    const val REMINDER_LEAD_MILLIS = REMINDER_LEAD_MINUTES * MILLIS_PER_MINUTE

    /**
     * 触发时刻过期宽限（分钟）：触发时刻早于「现在 − 宽限」的作业不再补设闹钟，
     * 避免打开应用就被过期提醒轰炸；落在宽限窗口内（刚过点）的仍设闹钟，由系统立即触发。
     */
    const val REMINDER_STALE_GRACE_MINUTES = 5

    /** 过期宽限（毫秒），与 [REMINDER_STALE_GRACE_MINUTES] 同源 */
    const val REMINDER_STALE_GRACE_MILLIS = REMINDER_STALE_GRACE_MINUTES * MILLIS_PER_MINUTE

    /** 闹钟请求码基数（配合 [ALARM_REQUEST_CODE_MODULUS] 把作业 id 映射为稳定请求码） */
    const val ALARM_REQUEST_CODE_BASE = 20_000

    /** 作业 id 取模基数：既避免请求码越界，又保证同一作业始终映射到同一请求码 */
    const val ALARM_REQUEST_CODE_MODULUS = 100_000

    // ---- 超时鼓励去重 ----
    /** 同一作业两次超时鼓励的最小间隔（分钟）：避免超时后反复骚扰 */
    const val OVERDUE_PROMPT_INTERVAL_MINUTES = 10

    /** 超时鼓励最小间隔（毫秒），与 [OVERDUE_PROMPT_INTERVAL_MINUTES] 同源 */
    const val OVERDUE_PROMPT_INTERVAL_MILLIS = OVERDUE_PROMPT_INTERVAL_MINUTES * MILLIS_PER_MINUTE

    // ---- 走秒唤醒锁（前台服务持有） ----
    /** 走秒期间的 WakeLock 标签（供系统与调试定位） */
    const val WAKE_LOCK_TAG = "assignmate:timer_ticker"

    /**
     * WakeLock 超时兜底（小时）：正常路径在暂停/停止/服务销毁时立即释放，
     * 此超时仅用于极端情况下漏释放的兜底（远大于任何合理作业时长）。
     */
    const val WAKE_LOCK_TIMEOUT_HOURS = 4

    /** WakeLock 超时兜底（毫秒），与 [WAKE_LOCK_TIMEOUT_HOURS] 同源 */
    const val WAKE_LOCK_TIMEOUT_MILLIS = WAKE_LOCK_TIMEOUT_HOURS * MILLIS_PER_HOUR

    // ---- 业务自然日（逐日归属） ----
    /**
     * 未指定业务自然日的哨兵值。
     *
     * 语义：仅用于「v4 旧库经 ALTER TABLE 加列后遗留的 epoch_day 列默认值 0」这一历史数据；
     * 正常写入路径（开始计时）必须显式写入按业务时区折算的真实自然日。
     * 取值 0 对应 1970-01-01，不可能是真实作业日，故可安全用作哨兵。
     */
    const val UNSPECIFIED_EPOCH_DAY = 0L

    // ---- 阶段作业每日到点提醒 ----
    /**
     * 「按天提醒」的闹钟请求码基数。
     *
     * 与单次提醒的 [ALARM_REQUEST_CODE_BASE] 错开一个量级：阶段作业的每一天各有一个闹钟，
     * 若与单次提醒共用同一区间，就可能与**另一条作业**的单次提醒撞码而互相覆盖。
     */
    const val ALARM_REQUEST_CODE_DAY_BASE = 500_000

    /** 「作业 id + 自然日」混合为请求码时的乘数（同一对始终映射同一请求码，重设即覆盖） */
    const val ALARM_REQUEST_CODE_DAY_MIX = 31L

    // ---- 休息页重建 ----
    /**
     * 休息起点复用窗口（毫秒）：进程被回收后重新进入休息页时，只有「起点仍在休息窗口内」
     * 才接着原起点倒计时（幂等重建）；已过窗口则视为新的休息，以当前时刻重新起算。
     */
    const val REST_RESUME_WINDOW_MILLIS = REST_DURATION_MILLIS
}
