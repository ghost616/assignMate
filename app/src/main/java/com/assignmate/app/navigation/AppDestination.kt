package com.assignmate.app.navigation

import com.assignmate.app.auth.ui.AuthDestination

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

    // 预留：后续跨模块共享的一级宿主路由（如主框架底部导航）在此补充常量
}