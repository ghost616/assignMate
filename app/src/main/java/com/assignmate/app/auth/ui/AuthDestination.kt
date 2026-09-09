package com.assignmate.app.auth.ui

/**
 * auth 模块自有路由常量（framework 的 AssignMateNavHost 负责注册接线，本模块不直接改 NavHost）。
 *
 * 接线约定（供 framework 计划参考）：
 * - ROLE_SELECT 作为首页宿主（替代原 home 占位）承载“我是家长/我是学生”入口；
 * - 登录/注册/学生进入成功后进入 PARENT_HOME / STUDENT_HOME；
 * - 各 Route 组件的回调参数即导航意图，建议跳转时对上一级做 popUpTo 清理，避免回退环。
 */
object AuthDestination {

    /** 身份选择（首页）：我是家长 / 我是学生 */
    const val ROLE_SELECT = "auth/role_select"

    /** 家长登录页 */
    const val PARENT_LOGIN = "auth/parent_login"

    /** 家长注册页 */
    const val PARENT_REGISTER = "auth/parent_register"

    /** 学生进入页（家长账号 + 验证码） */
    const val STUDENT_ENTER = "auth/student_enter"

    /** 家长主界面（学生档案管理） */
    const val PARENT_HOME = "auth/parent_home"

    /** 学生端首页占位（当前学生会话信息，作业清单由 homework 模块填充） */
    const val STUDENT_HOME = "auth/student_home"
}