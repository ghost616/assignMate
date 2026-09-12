package com.assignmate.app.stats.domain

/**
 * 历史查询入参（按日期或日期范围）。
 *
 * 语义与约束：
 * - 日期一律用 UTC 纪元日（`LocalDate.toEpochDay()`）表达，避免「日期 + 时区」两套口径混用；
 * - [endEpochDay] 缺省等于 [startEpochDay]，即「查单日」；两者均为闭区间；
 * - 结束日早于开始日时视为非法入参（[isValid] = false，仓库返回空结果而非抛异常）；
 * - 跨度上限 [StatsConstants.MAX_HISTORY_DAYS]：超出时由 [normalized] 收敛结束日，
 *   避免一次查询拉取过大范围（页面同时给出提示）。
 *
 * 该模型为纯数据：日期解析（如 `yyyy-MM-dd` 文本 → 纪元日）由 UI 层完成后传入。
 */
data class HistoryQuery(
    val startEpochDay: Long,
    val endEpochDay: Long = startEpochDay,
) {

    /** 是否合法：起止日均有效且结束日不早于开始日 */
    val isValid: Boolean get() = endEpochDay >= startEpochDay

    /** 跨度天数（含首尾；非法入参返回 0） */
    val spanDays: Long
        get() = if (isValid) endEpochDay - startEpochDay + 1L else 0L

    /** 是否超出允许的最大跨度 */
    val isTooLong: Boolean get() = spanDays > StatsConstants.MAX_HISTORY_DAYS

    /**
     * 收敛为合法且不超上限的查询：结束日截断到 `start + MAX_HISTORY_DAYS - 1`。
     * 非法入参原样返回（由调用方按 [isValid] 决定是否提示）。
     */
    fun normalized(): HistoryQuery =
        if (!isValid) {
            this
        } else {
            copy(endEpochDay = endEpochDay.coerceAtMost(startEpochDay + StatsConstants.MAX_HISTORY_DAYS - 1L))
        }

    companion object {

        /** 构造单日查询 */
        fun ofDay(epochDay: Long): HistoryQuery = HistoryQuery(startEpochDay = epochDay)
    }
}