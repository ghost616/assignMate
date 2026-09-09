package com.assignmate.app.navigation

import com.assignmate.app.auth.ui.AuthDestination

/**
 * 应用宿主级路由常量（framework 统一维护导航接线层）。
 *
 * 约定：
 * - 宿主/一级路由与导航宿主配置由 framework 定义；
 * - 各业务模块的自有页面路由在各自模块内定义（如 auth 模块的 [AuthDestination]），
 *   并在 [AssignMateNavHost] 中增量注册，模块间不互相 import UI 实现细节。
 *
 * 首页语义：原占位首页（ui/home/HomeScreen）已删除，由 auth 模块
 * "我是家长 / 我是学生"身份选择入口（[AuthDestination.ROLE_SELECT]）接管；
 * framework 仅保留起始路由衔接常量 [START]，实际路由字符串以 auth 模块为准。
 */
object AppDestination {

    /** 应用起始路由：auth 身份选择入口（我是家长 / 我是学生） */
    const val START: String = AuthDestination.ROLE_SELECT

    // 预留：后续跨模块共享的一级宿主路由（如主框架底部导航）在此补充常量
}