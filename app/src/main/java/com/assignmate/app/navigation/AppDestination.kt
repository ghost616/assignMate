package com.assignmate.app.navigation

import com.assignmate.app.auth.ui.AuthDestination
import com.assignmate.app.stats.ui.StatsDestination

/**
 * 应用宿主级路由常量与导航传参约定（framework 统一维护导航接线层）。
 *
 * 约定：
 * - 宿主/一级路由与导航宿主配置由 framework 定义；
 * - 各业务模块的自有页面路由在各自模块内定义（auth → AuthDestination、homework → HomeworkDestination、
 *   timer → TimerDestination、stats → StatsDestination），并在 [AssignMateNavHost] 中增量注册，
 *   路径参数一律由各模块 Destination 的解析函数（`studentIdOf` / `homeworkIdOf` / `epochDayOf`）解析，
 *   模块间不互相 import UI 实现细节。
 *
 * 首页语义：原占位首页（ui/home/HomeScreen）已删除，由 auth 模块
 * "我是家长 / 我是学生"身份选择入口（[AuthDestination.ROLE_SELECT]）接管；
 * framework 仅保留起始路由衔接常量 [START]，实际路由字符串以各业务模块定义为准。
 */
object AppDestination {

    /** 应用起始路由：auth 身份选择入口（我是家长 / 我是学生） */
    const val START: String = AuthDestination.ROLE_SELECT

    /**
     * 作业清单「未指定学生」导航传参（学生端入口使用）。
     *
     * homework 清单页契约：0 表示未指定——学生会话固定取本人 id（忽略路由参数，防越权查看他人清单）；
     * 家长会话则要求路由参数 > 0，未选定学生时页面提示「请先选择学生」。
     */
    const val UNSPECIFIED_STUDENT_ID: Long = 0L

    /**
     * stats 单项详情路由的**宿主注册模板**（framework 在 stats 既有契约上的适配层）。
     *
     * stats 侧 [StatsDestination.ITEM_DETAIL] 只声明了 `stats/item/{studentId}/{homeworkId}` 路径，
     * 故「历史某日盘点 → 点开某项 → 查看该日详情」原先取不到日期；本模板在统计既有路径模板之后
     * 补一个**可选**查询参数 `epochDay`（参数名沿用 stats 既有 [StatsDestination.ARG_EPOCH_DAY]，
     * 注册时以 [StatsDestination.ARG_EPOCH_DAY_TODAY] 作 defaultValue，缺省语义 = 「今天」，
     * 既有深链接 `stats/item/{studentId}/{homeworkId}` 不带查询串仍可命中）。
     *
     * 为什么这一段由 framework 拼、而不改 stats 常量：日期参数的接线（路由模板 + navArgument +
     * 页面透传）本就属 framework 计划范围（见 [StatsDestination] 与 `ItemDetailRoute` 的类注释），
     * stats 生产代码保持不动；参数名与解析口径全部来自 stats 既有约定
     * （[StatsDestination.ARG_EPOCH_DAY] / [StatsDestination.epochDayOf]），framework 不另造参数协议。
     */
    val STATS_ITEM_DETAIL_WITH_EPOCH_DAY: String =
        StatsDestination.ITEM_DETAIL +
            "?" + StatsDestination.ARG_EPOCH_DAY +
            "={" + StatsDestination.ARG_EPOCH_DAY + "}"

    /**
     * 拼装 stats 单项详情页实际路由：stats 既有路径拼装 [StatsDestination.itemDetailRoute]
     * 之后再补可选日期查询串（与 [STATS_ITEM_DETAIL_WITH_EPOCH_DAY] 的注册模板同源）。
     *
     * @param epochDay 详情要查看的自然日；缺省 [StatsDestination.ARG_EPOCH_DAY_TODAY]（「今天」哨兵），
     *   与盘点路由的日期口径一致；解析侧统一走 [StatsDestination.epochDayOf]。
     */
    fun statsItemDetailRoute(
        studentId: Long,
        homeworkId: Long,
        epochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    ): String = StatsDestination.itemDetailRoute(studentId, homeworkId) +
        "?" + StatsDestination.ARG_EPOCH_DAY + "=" + epochDay

    // 预留：后续跨模块共享的一级宿主路由（如主框架底部导航）在此补充常量
}