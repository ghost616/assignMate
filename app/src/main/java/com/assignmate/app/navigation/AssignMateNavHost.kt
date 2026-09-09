package com.assignmate.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.assignmate.app.auth.ui.AuthDestination
import com.assignmate.app.auth.ui.ParentHomeRoute
import com.assignmate.app.auth.ui.ParentLoginRoute
import com.assignmate.app.auth.ui.ParentRegisterRoute
import com.assignmate.app.auth.ui.RoleSelectRoute
import com.assignmate.app.auth.ui.StudentEnterRoute
import com.assignmate.app.auth.ui.StudentHomeRoute

/**
 * 应用导航宿主：扁平化页面结构，业务模块路由由 framework 在此统一接线。
 *
 * auth 模块六条路由（身份选择 / 家长登录 / 家长注册 / 学生进入 / 家长主界面 / 学生端首页）
 * 已全量注册，应用启动即进入身份选择入口；
 * homework / timer / stats / settings 等后续模块在此增量注册。
 *
 * 回退策略：认证成功进入主界面时清空身份选择入口（[toMain]），
 * 退出登录/会话过期时清空整个返回栈回到身份选择（[toRoleSelect]），避免回退环。
 */
@Composable
fun AssignMateNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = AppDestination.START,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        // ---- auth：身份选择（首页入口："我是家长 / 我是学生"） ----
        composable(route = AuthDestination.ROLE_SELECT) {
            RoleSelectRoute(
                onGoParentLogin = { navController.navigate(AuthDestination.PARENT_LOGIN) },
                onGoStudentEnter = { navController.navigate(AuthDestination.STUDENT_ENTER) },
                // 启动时存在已持久化会话：直达对应主界面并移除身份选择页
                onResumeParentHome = { navController.toMain(AuthDestination.PARENT_HOME) },
                onResumeStudentHome = { navController.toMain(AuthDestination.STUDENT_HOME) },
            )
        }
        // ---- auth：家长登录 ----
        composable(route = AuthDestination.PARENT_LOGIN) {
            ParentLoginRoute(
                onNavigateToRegister = { navController.navigate(AuthDestination.PARENT_REGISTER) },
                onLoggedIn = { navController.toMain(AuthDestination.PARENT_HOME) },
            )
        }
        // ---- auth：家长注册 ----
        composable(route = AuthDestination.PARENT_REGISTER) {
            ParentRegisterRoute(
                onRegistered = { navController.toMain(AuthDestination.PARENT_HOME) },
                onNavigateToLogin = {
                    // 清理身份选择之上的中间栈，保证返回栈中只保留唯一登录实例
                    navController.navigate(AuthDestination.PARENT_LOGIN) {
                        popUpTo(AppDestination.START) { inclusive = false }
                    }
                },
            )
        }
        // ---- auth：学生进入（家长账号 + 验证码） ----
        composable(route = AuthDestination.STUDENT_ENTER) {
            StudentEnterRoute(
                onEntered = { navController.toMain(AuthDestination.STUDENT_HOME) },
            )
        }
        // ---- auth：家长主界面（登录/注册成功或会话恢复后到达） ----
        composable(route = AuthDestination.PARENT_HOME) {
            ParentHomeRoute(
                onSessionExpired = { navController.toRoleSelect() },
                onLoggedOut = { navController.toRoleSelect() },
            )
        }
        // ---- auth：学生端首页（学生进入成功或会话恢复后到达） ----
        composable(route = AuthDestination.STUDENT_HOME) {
            StudentHomeRoute(
                onLoggedOut = { navController.toRoleSelect() },
            )
        }
        // 预留注册点：homework（作业清单）、timer、stats、settings 等业务路由
    }
}

/** 认证成功进入主界面：移除身份选择入口（以它为首段回退目标），避免返回时回到选择页。 */
private fun NavHostController.toMain(route: String) {
    navigate(route) {
        popUpTo(AppDestination.START) { inclusive = true }
    }
}

/** 退出登录/会话过期：清空整个返回栈，回到身份选择入口作为新根。 */
private fun NavHostController.toRoleSelect() {
    navigate(AppDestination.START) {
        popUpTo(graph.id) { inclusive = true }
    }
}