package com.assignmate.app.stats.ui

import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.stats.data.StatsFailure
import com.assignmate.app.stats.domain.StageProgress
import com.assignmate.app.stats.domain.StatsCalculations

/**
 * stats 模块文案集中映射（UI 层唯一出口，仓库层只给机器可读原因）：
 * 失败原因、正常态（空态/尚未开始/未设定）、日期口径文案，以及**当天状态**与**阶段打卡进度**文案。
 *
 * 文案风格：面向学生与家长，失败提示说明「下一步怎么做」而非只报错；
 * 无执行记录/无数据属于正常状态（不是错误），统一走 [NO_DATA_TODAY]/[NO_DATA]/[NOT_STARTED]
 * 的正常态文案。
 *
 * 日期口径：当日盘点页与单项详情页均可展示任意历史日，故日期相关文案（如空态）按「今天 / 历史日」
 * 拆分为两条，调用方据 `isToday` 选择（见 [noDataText]），避免历史日视图出现「今天」措辞。
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

    /**
     * 「查看这一天的盘点」无法导航时的兜底提示（携带目标日期）。
     *
     * 为什么需要：历史页的事件消费点对**未解析出学生**的事件只能丢弃（拿不到 studentId 就构不出
     * `stats/day/{studentId}` 路由），原实现是静默丢弃——用户点了按钮既不跳转也无任何提示，
     * 属用户可感知的死路。日期进文案是为了让用户明确「没反应的是哪一天」，并给出下一步动作。
     *
     * @param epochDay 用户点选的那一天（业务自然日）
     */
    fun daySummaryDroppedText(epochDay: Long): String =
        "${StatsCalculations.dateText(epochDay)} 的盘点暂时打不开：" +
            "还没确定要看哪位小朋友的记录，请返回重新进入后再试"

    /** 查询失败原因 -> 可读文案 */
    fun messageOf(failure: StatsFailure): String = when (failure) {
        StatsFailure.HOMEWORK_NOT_FOUND -> HOMEWORK_NOT_FOUND
        StatsFailure.NO_ACTIVE_SESSION -> NO_ACTIVE_SESSION
        StatsFailure.ACCESS_DENIED -> ACCESS_DENIED
        StatsFailure.INVALID_QUERY -> INVALID_RANGE
        StatsFailure.READ_FAILED -> READ_FAILED
    }

    /** 某一天的作业状态文案（口径：「一条作业项 + 每天详情」里的当天状态，非作业项聚合状态） */
    fun dayStatusLabel(status: HomeworkDayStatus): String = when (status) {
        HomeworkDayStatus.NOT_STARTED -> "还没开始"
        HomeworkDayStatus.IN_PROGRESS -> "进行中"
        HomeworkDayStatus.COMPLETED -> "已完成"
        HomeworkDayStatus.MISSED -> "当天没做完"
    }

    /** 阶段作业打卡进度文案（如「阶段打卡 3/7 天」） */
    fun stageProgressText(progress: StageProgress): String =
        "阶段打卡 ${progress.doneDays}/${progress.totalDays} 天"
}