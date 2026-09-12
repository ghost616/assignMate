package com.assignmate.app.stats.ui

import com.assignmate.app.stats.data.StatsFailure

/**
 * stats 模块结果 -> 用户可读文案的集中映射（UI 层唯一出口，仓库层只给机器可读原因）。
 *
 * 文案风格：面向学生与家长，失败提示说明「下一步怎么做」而非只报错；
 * 无执行记录/无数据属于正常状态（不是错误），统一走 [NO_DATA_TODAY]/[NO_DATA]/[NOT_STARTED]
 * 的正常态文案。
 *
 * 日期口径：当日盘点页可按 epochDay 展示任意历史日，故日期相关文案（如空态）按「今天 / 历史日」
 * 拆分为两条，调用方据 [com.assignmate.app.stats.ui.DaySummaryUiState.isToday] 选择
 * （见 [noDataText]），避免历史日视图出现「今天」措辞。
 */
object StatsErrorMessages {

    /** 会话失效统一提示 */
    const val NO_ACTIVE_SESSION = "登录状态已失效，请重新进入"

    /** 越权统一提示 */
    const val ACCESS_DENIED = "看不到这位同学的统计，请确认选择的学生"

    /** 家长未选定学生 */
    const val NO_STUDENT_SELECTED = "请先选择学生"

    /** 作业不存在 */
    const val HOMEWORK_NOT_FOUND = "这项作业不存在，可能已被删除"

    /** 暂无统计数据（空态）：盘点「今天」时使用 */
    const val NO_DATA_TODAY = "今天还没有作业记录哦"

    /** 暂无统计数据（空态）：盘点历史日时使用 */
    const val NO_DATA = "这一天还没有作业记录哦"

    /** 空态文案：按盘点日期口径选择（今天 / 历史日），保证历史日视图语义合适 */
    fun noDataText(isToday: Boolean): String = if (isToday) NO_DATA_TODAY else NO_DATA

    /** 历史查询无记录（空态） */
    const val NO_HISTORY = "这段时间还没有作业记录哦"

    /** 尚未开始计时（单项详情无执行记录） */
    const val NOT_STARTED = "还没有开始计时，暂时没有耗时数据"

    /** 未设定预估时长 */
    const val ESTIMATED_UNSET = "未设定"

    /** 查询入参非法 */
    const val INVALID_RANGE = "日期范围不正确，请重新选择"

    /** 读取失败（可重试） */
    const val READ_FAILED = "统计数据读取失败，请稍后重试"

    /** 查询失败原因 -> 可读文案 */
    fun messageOf(failure: StatsFailure): String = when (failure) {
        StatsFailure.HOMEWORK_NOT_FOUND -> HOMEWORK_NOT_FOUND
        StatsFailure.NO_ACTIVE_SESSION -> NO_ACTIVE_SESSION
        StatsFailure.ACCESS_DENIED -> ACCESS_DENIED
        StatsFailure.INVALID_QUERY -> INVALID_RANGE
        StatsFailure.READ_FAILED -> READ_FAILED
    }
}