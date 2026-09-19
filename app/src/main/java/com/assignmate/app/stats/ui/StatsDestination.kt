package com.assignmate.app.stats.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState

/**
 * stats 模块自有路由常量与路径参数协议（framework 的 AssignMateNavHost 负责注册接线，
 * 本模块不直接改 NavHost，与 homework/timer 的 Destination 约定一致）。
 *
 * 路由清单与接线契约：
 * - DAY_SUMMARY：当日盘点页，`stats/day/{studentId}?epochDay={epochDay}`；完成率（进度 + 百分比）、
 *   暂停次数、暂停总时长、暂停最久作业；`epochDay` 为可选查询参数（缺省 [ARG_EPOCH_DAY_TODAY]
 *   表示「今天」），历史查询页「查看这一天的盘点」据此展示所选日期的盘点；入口可进入单项详情与历史查询；
 * - ITEM_DETAIL：单项详情页，`stats/item/{studentId}/{homeworkId}`；展示**指定某一天**（缺省「今天」，
 *   由页面入参注入）的预估/实际/暂停时长与困难度侧面评估提示（无执行记录时展示「尚未开始/暂无数据」）；
 *   阶段作业据此查看某一天的详情而非阶段总计——日期参数的接线（路由查询参数 + navArgument）属
 *   framework 计划范围，本模块不改 NavHost；
 * - HISTORY：历史查询页，`stats/history/{studentId}?fromEpochDay={from}&toEpochDay={to}`；
 *   按日期或日期范围查看历史完成情况（学生/家长视角均可查看名下学生）。
 *
 * 传参与解析约定：页面入参一律走本对象的 [studentIdOf] / [homeworkIdOf] / [epochDayOf]，
 * 学生端可传 [ARG_STUDENT_ID_NONE]（0 = 未指定），由页面按当前学生会话解析本人 id（防越权）；
 * `epochDay` 缺省/非法一律回落 [ARG_EPOCH_DAY_TODAY]（由页面解析为「今天」，保持既有行为）。
 */
object StatsDestination {

    /**
     * 当日盘点页路由模板。
     *
     * `epochDay` 为**可选查询参数**：缺省（[ARG_EPOCH_DAY_TODAY]）表示「今天」，
     * 历史查询页「查看这一天的盘点」据此带上所选日期，使该页能展示任意历史日的盘点。
     */
    const val DAY_SUMMARY = "stats/day/{studentId}?epochDay={epochDay}"

    /**
     * 单项详情页路由模板。
     *
     * 日期口径（阶段作业按天查看）由 [ItemDetailRoute] 的 `epochDay` 入参承载，缺省「今天」；
     * 若要让「历史日盘点里点开的详情」也落在该历史日，需 framework 在本路由上补一个可选查询参数
     * （`epochDay`）并透传给页面——该接线属 framework 计划，本模块不改 NavHost
     * （路由模板与 navArgument 一一对应的契约测试由 navigation 模块持有）。
     */
    const val ITEM_DETAIL = "stats/item/{studentId}/{homeworkId}"

    /** 历史查询页路由模板（查询参数缺省为「今天」，由页面按会话时区解析） */
    const val HISTORY = "stats/history/{studentId}?fromEpochDay={fromEpochDay}&toEpochDay={toEpochDay}"

    // ---- 路径/查询参数名（framework 注册 navArgument 时使用） ----
    const val ARG_STUDENT_ID = "studentId"
    const val ARG_HOMEWORK_ID = "homeworkId"
    const val ARG_EPOCH_DAY = "epochDay"
    const val ARG_FROM_EPOCH_DAY = "fromEpochDay"
    const val ARG_TO_EPOCH_DAY = "toEpochDay"

    /** studentId 缺省值：0 表示未指定（学生会话取本人，家长会话需显式指定） */
    const val ARG_STUDENT_ID_NONE = 0L

    /** homeworkId 缺省值：表示未指定作业（详情页据此走「作业不存在」提示） */
    const val ARG_HOMEWORK_ID_NONE = -1L

    /**
     * epochDay 缺省值：表示「今天」。
     * 纪元日恒为非负数（1970-01-01 为 0），故 -1 可安全用作「未指定」哨兵值。
     */
    const val ARG_EPOCH_DAY_TODAY = -1L

    /**
     * 拼装当日盘点页实际路由。
     *
     * @param epochDay 要盘点的自然日（UTC 纪元日）；缺省 [ARG_EPOCH_DAY_TODAY] 表示「今天」，
     *   与历史查询页「查看这一天的盘点」共用同一参数协议。
     */
    fun daySummaryRoute(
        studentId: Long,
        epochDay: Long = ARG_EPOCH_DAY_TODAY,
    ): String = "stats/day/$studentId?$ARG_EPOCH_DAY=$epochDay"

    /**
     * 拼装单项详情页实际路由。
     *
     * 说明：详情页的查看日期由 [ItemDetailRoute] 的 `epochDay` 入参承载（缺省「今天」），
     * **不经路由参数**——把日期放进路由需要 framework 在本路由上补占位符与 navArgument，
     * 属 framework 计划的接线范围（本模块不改 NavHost）。
     */
    fun itemDetailRoute(studentId: Long, homeworkId: Long): String = "stats/item/$studentId/$homeworkId"

    /**
     * 拼装历史查询页实际路由。
     *
     * @param fromEpochDay 起始日（UTC 纪元日）
     * @param toEpochDay 结束日（含）；默认与起始日相同（查单日）
     */
    fun historyRoute(
        studentId: Long,
        fromEpochDay: Long,
        toEpochDay: Long = fromEpochDay,
    ): String = "stats/history/$studentId?$ARG_FROM_EPOCH_DAY=$fromEpochDay&$ARG_TO_EPOCH_DAY=$toEpochDay"

    /** 解析路径参数 studentId（缺失/非法返回 0，页面据此按会话解析本人或提示未选定学生） */
    fun studentIdOf(raw: String?): Long = raw?.toLongOrNull() ?: ARG_STUDENT_ID_NONE

    /** 解析路径参数 homeworkId（缺失/非法返回 [ARG_HOMEWORK_ID_NONE]） */
    fun homeworkIdOf(raw: String?): Long = raw?.toLongOrNull() ?: ARG_HOMEWORK_ID_NONE

    /** 解析查询参数 epochDay（缺失/非法返回 [ARG_EPOCH_DAY_TODAY]，由页面解析为「今天」） */
    fun epochDayOf(raw: String?): Long = raw?.toLongOrNull() ?: ARG_EPOCH_DAY_TODAY
}

/**
 * 路由参数 + 当前会话 -> 目标学生 id（stats 各页面共用，口径与 homework/timer 保持一致）：
 * - 学生会话：固定取本人 id（忽略路由参数，防止越权查看他人统计）；
 * - 家长会话：取路由参数指定的学生（需 > 0）；
 * - 无会话：返回 null，页面据此提示「登录状态已失效 / 请先选择学生」。
 */
internal fun resolveStatsStudentId(
    session: SessionState,
    routeStudentId: Long,
): Long? = when {
    session.isStudent -> session.studentId
    session.role == Role.PARENT -> routeStudentId.takeIf { it > 0L }
    else -> null
}