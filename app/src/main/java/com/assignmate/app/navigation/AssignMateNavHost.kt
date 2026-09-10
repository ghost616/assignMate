package com.assignmate.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.assignmate.app.auth.ui.AuthDestination
import com.assignmate.app.auth.ui.ParentHomeRoute
import com.assignmate.app.auth.ui.ParentLoginRoute
import com.assignmate.app.auth.ui.ParentRegisterRoute
import com.assignmate.app.auth.ui.RoleSelectRoute
import com.assignmate.app.auth.ui.StudentEnterRoute
import com.assignmate.app.auth.ui.StudentHomeRoute
import com.assignmate.app.homework.ui.HomeworkDestination
import com.assignmate.app.homework.ui.HomeworkEntryRoute
import com.assignmate.app.homework.ui.HomeworkListRoute
import com.assignmate.app.homework.ui.HomeworkTemplateRoute
import com.assignmate.app.homework.ui.HomeworkTimeSetRoute

/**
 * 应用导航宿主：扁平化页面结构，业务模块路由由 framework 在此统一接线。
 *
 * 已注册：
 * - auth 六条路由：身份选择 / 家长登录 / 家长注册 / 学生进入 / 家长主界面 / 学生端首页；
 * - homework 四条路由：作业清单 / 录入入口（手动·拍照·相册·语音）/ 录入编辑模板 / 时间设定。
 *
 * 传参与解析约定：路径参数统一以字符串注册，页面侧经
 * [HomeworkDestination.studentIdOf] / [HomeworkDestination.homeworkIdOf] 解析，
 * 避免各页面重复写 `toLongOrNull() ?: 0L` 造成口径漂移。
 * - 家长进入清单：回调携带被选学生 id（`homework/list/{studentId}`）；
 * - 学生进入清单：传 [AppDestination.UNSPECIFIED_STUDENT_ID]（0 = 未指定），
 *   清单页按当前学生会话解析本人 id（忽略路由参数，防越权查看他人清单）。
 *
 * 回退策略（避免回退环）：
 * - 认证成功进入主界面：清理身份选择入口（[toMain]）；
 * - 退出登录 / 会话过期：清空整个返回栈回身份选择（[toRoleSelect]）；
 * - 作业二级页（录入 / 编辑模板 / 时间设定）：popBackStack 回上一级清单页，主界面始终为栈底。
 *
 * timer / stats / settings 等后续模块在此增量注册。
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
                // 家长选择某学生后进入其作业清单（携带该 studentId）
                onEnterHomework = { studentId -> navController.toHomeworkList(studentId) },
            )
        }
        // ---- auth：学生端首页（学生进入成功或会话恢复后到达） ----
        composable(route = AuthDestination.STUDENT_HOME) {
            StudentHomeRoute(
                onLoggedOut = { navController.toRoleSelect() },
                // 学生进入自己的作业清单：交由清单页按当前学生会话解析（防越权）
                onEnterHomework = { navController.toHomeworkList(AppDestination.UNSPECIFIED_STUDENT_ID) },
            )
        }
        // ---- homework：作业清单（家长：路由参数指定学生；学生：参数被忽略，按会话解析本人） ----
        composable(
            route = HomeworkDestination.LIST,
            arguments = listOf(
                navArgument(HomeworkDestination.ARG_STUDENT_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val studentId = entry.studentIdArg()
            HomeworkListRoute(
                studentId = studentId,
                onBack = { navController.popBackStack() },
                onAddHomework = { navController.navigate(HomeworkDestination.entryRoute(studentId)) },
                onEditTime = { homeworkId ->
                    navController.navigate(HomeworkDestination.timeSetRoute(studentId, homeworkId))
                },
                onEditTemplate = { homeworkId ->
                    navController.navigate(HomeworkDestination.templateRoute(studentId, homeworkId))
                },
            )
        }
        // ---- homework：录入入口（手动录入 + 拍照/相册/语音识别），保存后回清单 ----
        composable(
            route = HomeworkDestination.ENTRY,
            arguments = listOf(
                navArgument(HomeworkDestination.ARG_STUDENT_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            HomeworkEntryRoute(
                studentId = entry.studentIdArg(),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        // ---- homework：录入/编辑模板（homeworkId 缺省 = 新建） ----
        composable(
            route = HomeworkDestination.TEMPLATE,
            arguments = listOf(
                navArgument(HomeworkDestination.ARG_STUDENT_ID) { type = NavType.StringType },
                navArgument(HomeworkDestination.ARG_HOMEWORK_ID) {
                    type = NavType.StringType
                    defaultValue = HomeworkDestination.ARG_HOMEWORK_ID_NONE.toString()
                },
            ),
        ) { entry ->
            HomeworkTemplateRoute(
                studentId = entry.studentIdArg(),
                homeworkId = entry.homeworkIdArg(),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        // ---- homework：时间设定 / 编辑 ----
        composable(
            route = HomeworkDestination.TIME_SET,
            arguments = listOf(
                navArgument(HomeworkDestination.ARG_STUDENT_ID) { type = NavType.StringType },
                navArgument(HomeworkDestination.ARG_HOMEWORK_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            HomeworkTimeSetRoute(
                studentId = entry.studentIdArg(),
                homeworkId = entry.homeworkIdArg(),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
        // 预留注册点：timer / stats / settings 等业务路由
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

/**
 * 进入作业清单：家长传被选学生 id；学生传 [AppDestination.UNSPECIFIED_STUDENT_ID]，
 * 由清单页按当前学生会话解析本人 id。清单之上不再回退到选择页，无回退环。
 */
private fun NavHostController.toHomeworkList(studentId: Long) {
    navigate(HomeworkDestination.listRoute(studentId))
}

/** 读取并解析当前路由的 studentId（缺失/非法 → 0，页面据此走「请先选择学生」提示）。 */
private fun androidx.navigation.NavBackStackEntry.studentIdArg(): Long =
    HomeworkDestination.studentIdOf(arguments?.getString(HomeworkDestination.ARG_STUDENT_ID))

/** 读取并解析当前路由的 homeworkId（缺失/非法 → 新建语义 [HomeworkDestination.ARG_HOMEWORK_ID_NONE]）。 */
private fun androidx.navigation.NavBackStackEntry.homeworkIdArg(): Long =
    HomeworkDestination.homeworkIdOf(arguments?.getString(HomeworkDestination.ARG_HOMEWORK_ID))