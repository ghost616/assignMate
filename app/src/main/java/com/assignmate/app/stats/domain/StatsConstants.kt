package com.assignmate.app.stats.domain

/**
 * stats 模块业务规则常量集中收敛，禁止在实现层散落魔法值。
 *
 * 规则同源：[StatsCalculations] / [DifficultyAssessor] / 仓库实现 / UI 文案共用本组常量，
 * 调整阈值只改这一处，避免「统计口径」与「提示口径」漂移。
 */
object StatsConstants {

    // ---- 时间换算 ----
    /** 一分钟的毫秒数（时长一律按毫秒落库，展示时换算为分钟） */
    const val MILLIS_PER_MINUTE = 60_000L

    /** 一小时的分钟数 */
    const val MINUTES_PER_HOUR = 60L

    /** 不足一分钟的时长展示阈值：小于一分钟的进度视为「不到 1 分钟」，避免展示为 0 分钟 */
    const val DISPLAY_MINUTE_MIN_MILLIS = MILLIS_PER_MINUTE

    // ---- 完成率 ----
    /** 百分比展示精度（保留 1 位小数，如 87.5%） */
    const val PERCENT_SCALE = 1

    // ---- 困难度评估阈值（实际耗时 / 预估耗时） ----
    /**
     * 实际耗时低于预估该比例即视为「很顺利」（比预估快不少）。
     * 0.7 表示 30 分钟预估在 21 分钟内完成。
     */
    const val FAST_RATIO = 0.7

    /**
     * 实际耗时超过预估该比例即视为「偏慢」（比预估慢不少）。
     * 1.3 表示 30 分钟预估实际超过 39 分钟。
     */
    const val SLOW_RATIO = 1.3

    /**
     * 实际耗时超过预估该比例即视为「明显吃力」（比预估慢很多）。
     * 2.0 表示实际耗时达到预估的两倍。
     */
    const val STRUGGLE_RATIO = 2.0

    /** 暂停次数达到该值即视为「多次暂停」（需结合耗时一并评估） */
    const val FREQUENT_PAUSE_COUNT = 3

    /** 暂停次数达到该值即视为「频繁暂停」（单次评估即可判定吃力） */
    const val VERY_FREQUENT_PAUSE_COUNT = 5

    /** 预估时长缺失时，实际耗时达到该分钟数也算「偏慢」（无比值可比时的兜底口径） */
    const val LONG_RUNNING_MINUTES = 60L

    // ---- 历史查询 ----
    /** 历史查询允许的最大天数跨度（含首尾），防止一次拉取过大范围 */
    const val MAX_HISTORY_DAYS = 92L
}