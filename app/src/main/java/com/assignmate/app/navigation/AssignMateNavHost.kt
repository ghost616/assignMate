package com.assignmate.app.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
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
import com.assignmate.app.timer.ui.TimerCompletionRoute
import com.assignmate.app.timer.ui.TimerDestination
import com.assignmate.app.timer.ui.TimerExecutionRoute
import com.assignmate.app.timer.ui.TimerNextItemRoute
import com.assignmate.app.timer.ui.TimerRestRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 应用导航宿主：扁平化页面结构，业务模块路由由 framework 在此统一接线。
 *
 * 已注册：
 * - auth 六条路由：身份选择 / 家长登录 / 家长注册 / 学生进入 / 家长主界面 / 学生端首页；
 * - homework 四条路由：作业清单 / 录入入口（手动·拍照·相册·语音）/ 录入编辑模板 / 时间设定；
 * - timer 四条路由：作业执行（计时）/ 休息倒计时 / 下一项提示 / 完成反馈。
 *
 * 传参与解析约定：路径参数统一以字符串注册，页面侧经
 * [HomeworkDestination.studentIdOf] / [HomeworkDestination.homeworkIdOf] 解析，
 * 避免各页面重复写 `toLongOrNull() ?: 0L` 造成口径漂移。
 * - 家长进入清单：回调携带被选学生 id（`homework/list/{studentId}`）；
 * - 学生进入清单：传 [AppDestination.UNSPECIFIED_STUDENT_ID]（0 = 未指定），
 *   清单页按当前学生会话解析本人 id（忽略路由参数，防越权查看他人清单）。
 *
 * 计时全链路（清单 → 计时 → 休息 → 下一项 / 完成反馈）：
 * - 清单「开始作业」：清单先把作业置「进行中」，回抛 [HomeworkListRoute] 的
 *   `onStartHomework(homeworkId)`，此处以清单当前的 studentId + homeworkId 进 [TimerDestination.EXECUTION]；
 * - 完成作业：执行页回调进入 [TimerDestination.REST]（休息 10 分钟或跳过）→ [TimerDestination.NEXT_ITEM]
 *   （「现在开始」直接回到执行页：此时会话已由下一项页先行创建，执行页恢复现场继续走秒）；
 *   清单全部完成则进 [TimerDestination.COMPLETION]（[TimerDestination.NEXT_ITEM] 的「看看今天的表现」）；
 * - 各页「回到作业清单」与完成反馈页统一 [toHomeworkListOnStack] 回已在栈中的清单页；
 * - 清单状态无需手动刷新：清单页以仓库 `observeHomework` 单数据流驱动，
 *   计时/完成写库后返回清单即为最新状态（进行中 / 已完成）。
 *
 * 到点提醒同步接线（homework 不依赖 timer，回调/事件由 homework 抛意图、framework 接线）：
 * - 删除作业：[HomeworkListRoute] 的 `onHomeworkRemoved(homeworkId)`（删除成功后一次）→
 *   [ReminderSyncDispatcher.cancelHomeworkReminderSync]（取消旧闹钟，避免提醒指向已删除作业）；
 * - 重排时间保存：[HomeworkTimeSetRoute] 的 `onHomeworkScheduleSaved(homeworkId)`（排定成功后一次）→
 *   [ReminderSyncDispatcher.syncHomeworkReminder]（按新时刻重设，旧时刻随之取消）；
 * - 两个动作均为「调用即返回」：在进程级作用域执行、失败只记日志，
 *   不阻塞主线程，也不影响删除/保存主流程；
 * - 计时入口（进入执行页/下一项页）的整份清单纠正逻辑保持不变。
 *
 * 回退策略（避免回退环）：
 * - 认证成功进入主界面：清理身份选择入口（[toMain]）；
 * - 退出登录 / 会话过期：清空整个返回栈回身份选择（[toRoleSelect]）；
 * - 作业二级页（录入 / 编辑模板 / 时间设定）：popBackStack 回上一级清单页，主界面始终为栈底；
 * - 计时链路每一跳都 popUpTo 移除刚离开的页面（执行 → 休息 → 下一项 / 完成反馈），
 *   因此返回键不会退回到已完成的计时或休息状态，也不会在多轮「下一项 → 执行 → 休息」中堆栈累积。
 *
 * stats / settings 等后续模块在此增量注册。
 */
@Composable
fun AssignMateNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = AppDestination.START,
    // framework 侧到点提醒同步接线（Hilt 注入）：删除作业 → 取消提醒、重排时间保存 → 按新时刻同步。
    // 内部走进程级作用域，页面 popBackStack 后仍能跑完；调用即返回、失败静默降级。
    reminderSync: ReminderSyncDispatcher = hiltViewModel<AssignMateNavHostViewModel>().reminderSync,
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
                // 「开始作业」：清单已把作业推进为「进行中」，此处进入计时执行页（当前学生 + 该作业）
                onStartHomework = { homeworkId ->
                    navController.toTimerExecution(
                        studentId = studentId,
                        homeworkId = homeworkId,
                    )
                },
                // 删除成功：取消该作业的到点提醒，避免旧闹钟触发指向已删除作业的提醒
                onHomeworkRemoved = { homeworkId -> reminderSync.cancelHomeworkReminderSync(homeworkId) },
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
                // 保存成功：按新时刻同步该作业的到点提醒（旧时刻取消、新时刻设上）
                onHomeworkScheduleSaved = { homeworkId -> reminderSync.syncHomeworkReminder(homeworkId) },
            )
        }
        // ---- timer：作业执行页（计时走秒；「有事走开」/「我回来啦」/「完成作业」都在本页） ----
        composable(
            route = TimerDestination.EXECUTION,
            arguments = listOf(
                navArgument(TimerDestination.ARG_STUDENT_ID) { type = NavType.StringType },
                navArgument(TimerDestination.ARG_HOMEWORK_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            TimerExecutionRoute(
                studentId = entry.timerStudentIdArg(),
                homeworkId = entry.timerHomeworkIdArg(),
                // 返回：回上一级（清单或下一项提示页）；离开页面不停表，计时事实在库中，回来即恢复现场
                onBack = { navController.popBackStack() },
                // 完成作业：移除本执行页后进休息页，返回键不会退回已完成的计时现场
                onCompleted = { studentId, homeworkId ->
                    navController.toTimerRest(studentId = studentId, homeworkId = homeworkId)
                },
            )
        }
        // ---- timer：休息页（完成一项后的 10 分钟倒计时，可「跳过休息」） ----
        composable(
            route = TimerDestination.REST,
            arguments = listOf(
                navArgument(TimerDestination.ARG_STUDENT_ID) { type = NavType.StringType },
                navArgument(TimerDestination.ARG_HOMEWORK_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            TimerRestRoute(
                studentId = entry.timerStudentIdArg(),
                homeworkId = entry.timerHomeworkIdArg(),
                // 休息结束/跳过：移除休息页后进下一项提示页
                onRestFinished = { studentId -> navController.toTimerNextItem(studentId) },
                onBackToList = { studentId -> navController.toHomeworkListOnStack(studentId) },
            )
        }
        // ---- timer：下一项提示页（按清单顺序给出下一条待完成项） ----
        composable(
            route = TimerDestination.NEXT_ITEM,
            arguments = listOf(
                navArgument(TimerDestination.ARG_STUDENT_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            TimerNextItemRoute(
                studentId = entry.timerStudentIdArg(),
                onBackToList = { studentId -> navController.toHomeworkListOnStack(studentId) },
                // 「现在开始」：下一项页已先行创建会话，直接进执行页走秒（无「刚离开的休息页」需清理）
                onStartHomework = { studentId, homeworkId ->
                    navController.toTimerExecution(studentId = studentId, homeworkId = homeworkId)
                },
                // 清单全部完成：移除下一项页后进完成反馈页
                onAllCompleted = { studentId -> navController.toTimerCompletion(studentId) },
            )
        }
        // ---- timer：完成反馈页（表扬语 + 清单完成概览） ----
        composable(
            route = TimerDestination.COMPLETION,
            arguments = listOf(
                navArgument(TimerDestination.ARG_STUDENT_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            TimerCompletionRoute(
                studentId = entry.timerStudentIdArg(),
                onBackToList = { studentId -> navController.toHomeworkListOnStack(studentId) },
            )
        }
        // 预留注册点：stats / settings 等业务路由
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

/**
 * 回到「已在返回栈中的」作业清单页。
 *
 * 计时链路（清单 → 执行 → 休息 → 下一项 / 完成反馈）逐跳 popUpTo 后，
 * 栈中恰好保留一个清单实例，故此处统一 popBackStack 回到它：
 * 既刷新出最新清单状态（清单页以仓库数据流驱动），又不会重复压入清单造成回退环。
 * 兜底：清单实例不在栈中（如计时页被深链直接打开）时改为压入新的清单路由，
 * 避免 popBackStack 无目标时把整个返回栈弹空、出现空白页。
 */
private fun NavHostController.toHomeworkListOnStack(studentId: Long) {
    if (!popBackStack(HomeworkDestination.LIST, inclusive = false)) {
        navigate(HomeworkDestination.listRoute(studentId))
    }
}

/**
 * 进入计时执行页：携带当前学生 id 与目标作业 id（作业 id 缺失/非法按
 * [TimerDestination.ARG_HOMEWORK_ID_NONE] 处理，执行页据此走「作业不存在」提示）。
 *
 * 两条入口共用（清单「开始作业」、下一项提示页「现在开始」），
 * 执行页会按该作业「最近一次未结束会话」恢复现场，故重复进入不会重复起表。
 */
private fun NavHostController.toTimerExecution(studentId: Long, homeworkId: Long) {
    navigate(TimerDestination.executionRoute(studentId, homeworkId))
}

/** 完成作业后进休息页：移除刚离开的执行页，返回键不会退回已完成的计时现场。 */
private fun NavHostController.toTimerRest(studentId: Long, homeworkId: Long) {
    navigate(TimerDestination.restRoute(studentId, homeworkId)) {
        popUpTo(TimerDestination.EXECUTION) { inclusive = true }
    }
}

/** 休息结束/跳过后进下一项提示页：移除休息页，避免返回键退回已结束的休息倒计时。 */
private fun NavHostController.toTimerNextItem(studentId: Long) {
    navigate(TimerDestination.nextItemRoute(studentId)) {
        popUpTo(TimerDestination.REST) { inclusive = true }
    }
}

/**
 * 清单全部完成时进完成反馈页：移除下一项提示页。
 * 完成反馈页由「回到作业清单」统一回清单，故返回键不会退回「已无下一项」的提示页。
 */
private fun NavHostController.toTimerCompletion(studentId: Long) {
    navigate(TimerDestination.completionRoute(studentId)) {
        popUpTo(TimerDestination.NEXT_ITEM) { inclusive = true }
    }
}

/** 读取并解析当前路由的 studentId（缺失/非法 → 0，页面据此走「请先选择学生」提示）。 */
private fun androidx.navigation.NavBackStackEntry.studentIdArg(): Long =
    HomeworkDestination.studentIdOf(arguments?.getString(HomeworkDestination.ARG_STUDENT_ID))

/** 读取并解析当前路由的 homeworkId（缺失/非法 → 新建语义 [HomeworkDestination.ARG_HOMEWORK_ID_NONE]）。 */
private fun androidx.navigation.NavBackStackEntry.homeworkIdArg(): Long =
    HomeworkDestination.homeworkIdOf(arguments?.getString(HomeworkDestination.ARG_HOMEWORK_ID))

/** 读取并解析 timer 路由的 studentId（缺失/非法 → 0，页面据此按会话解析本人或提示未选定学生）。 */
private fun androidx.navigation.NavBackStackEntry.timerStudentIdArg(): Long =
    TimerDestination.studentIdOf(arguments?.getString(TimerDestination.ARG_STUDENT_ID))

/** 读取并解析 timer 路由的 homeworkId（缺失/非法 → [TimerDestination.ARG_HOMEWORK_ID_NONE]）。 */
private fun androidx.navigation.NavBackStackEntry.timerHomeworkIdArg(): Long =
    TimerDestination.homeworkIdOf(arguments?.getString(TimerDestination.ARG_HOMEWORK_ID))

/**
 * 导航宿主的 Hilt 注入载体：只为把 framework 的 [ReminderSyncDispatcher] 带进
 * 导航宿主（@Composable 不能直接注入依赖）。
 *
 * 生命周期：导航宿主挂在 Activity 的 ViewModelStore 上，随 Activity 存活——
 * 但提醒同步本身跑在 dispatcher 的进程级作用域，故删除/保存后立即 popBackStack
 * 也不会取消未完成的闹钟同步。
 */
@HiltViewModel
internal class AssignMateNavHostViewModel @Inject constructor(
    val reminderSync: ReminderSyncDispatcher,
) : ViewModel()