package com.assignmate.app.navigation

import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.assignmate.app.stats.ui.DaySummaryRoute
import com.assignmate.app.stats.ui.HistoryRoute
import com.assignmate.app.stats.ui.ItemDetailRoute
import com.assignmate.app.stats.ui.StatsDestination
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
 * - timer 四条路由：作业执行（计时）/ 休息倒计时 / 下一项提示 / 完成反馈；
 * - stats 三条路由：当日盘点 / 单项详情 / 历史查询。
 *
 * 传参与解析约定：路径与查询参数统一以字符串注册，页面侧经各模块 Destination 的解析函数
 * （homework → [HomeworkDestination.studentIdOf] / [HomeworkDestination.homeworkIdOf]；
 * stats → [StatsDestination.studentIdOf] / [StatsDestination.homeworkIdOf] / [StatsDestination.epochDayOf]）
 * 解析，避免各页面重复写 `toLongOrNull() ?: 0L` 造成口径漂移。
 * stats 的日期查询参数（DAY_SUMMARY 的 `epochDay`、HISTORY 的 `fromEpochDay`/`toEpochDay`）一律声明
 * `defaultValue = [StatsDestination.ARG_EPOCH_DAY_TODAY]`（-1 = 未指定 → 页面按会话时区解析为「今天」）：
 * 可选参数才允许不带查询串导航，缺失时回落「今天」而非抛缺少必填参数异常。
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
 * 统计全链路（清单 → 当日盘点 → 单项详情 / 历史查询，两条入口链路同一条盘点路由）：
 * - 清单「查看盘点」：[HomeworkListRoute] 的 `onOpenStats(studentId)` 携带清单当前展示的学生
 *   （家长 = 被选学生、学生端 = 清单按会话解析出的本人 id）→ [toStatsDaySummary] 进 [StatsDestination.DAY_SUMMARY]，
 *   日期缺省（[StatsDestination.ARG_EPOCH_DAY_TODAY]）= 盘点「今天」；
 *   因清单已把 studentId 解析到位，此处不再传 [AppDestination.UNSPECIFIED_STUDENT_ID]，
 *   盘点页/详情页仍按会话再做一层越权收敛（家长需正数学生、学生固定本人）；
 * - 盘点「查看这一项详情」：[DaySummaryRoute] 的 `onOpenItemDetail(studentId, homeworkId)` → [StatsDestination.ITEM_DETAIL]；
 * - 盘点「查看历史完成情况」：[DaySummaryRoute] 的 `onOpenHistory(studentId)` → [StatsDestination.HISTORY]，
 *   日期范围缺省传 [StatsDestination.ARG_EPOCH_DAY_TODAY] 哨兵，由页面按会话时区解析为「今天」；
 * - 历史「选择日期范围」：[HistoryRoute] 的 `onPickRange(studentId, from, to)` → 弹出日期范围选择器
 *   （[StatsHistoryRangePickerDialog]，Material3 + 无新增依赖）→ 确认后 [toStatsHistoryRange]
 *   先移除当前历史查询实例再压入新范围，栈中恒只有一个历史查询页（换范围不累积回退栈）；
 * - 历史「查看这一天的盘点」：[HistoryRoute] 的 `onOpenDaySummary(studentId, epochDay)` → [toStatsDaySummary]（fromHistory = true）
 *   携带所选日期（`stats/day/{studentId}?epochDay=…`），盘点页据此展示该历史日的盘点，不再固定「今天」；
 *   此入口保留历史查询页在栈中（返回键回到历史页、不丢失用户所选日期范围上下文），只清理历史页之上的旧盘点实例。
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
 *   因此返回键不会退回到已完成的计时或休息状态，也不会在多轮「下一项 → 执行 → 休息」中堆栈累积；
 * - 统计链路：换日期/改范围一律「先移除旧实例、再压入新实例」，但两个盘点入口的返回栈语义不同：
 *   · 清单「查看盘点」→ 栈为 `[清单, 盘点]`，[toStatsDaySummary] 以 popUpTo(DAY_SUMMARY, inclusive = true)
 *     替换栈中旧盘点实例（旧实例 arguments 入栈即固定，复用只会展示旧日期）；
 *   · 历史「查看这一天的盘点」→ 栈为 `[清单, 历史, 新盘点]`，[toStatsDaySummary]（fromHistory = true）
 *     先仅清理历史页之上的旧盘点实例（避免 `[清单, 旧盘点, 历史, 新盘点]` 重复堆积），
 *     再压入新盘点实例，故按返回键回到历史查询页（保留其日期范围上下文）；
 *   · [toStatsHistoryRange] 换范围同样先移除当前历史查询实例，栈中恒只有一个历史查询页；
 *   二级页（单项详情 / 历史查询）与盘点页「返回」统一 popBackStack 回上一级，三条入口都不产生回退环。
 *
 * settings 等后续模块在此增量注册。
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
                // 「查看盘点」：清单已把目标学生解析到位（家长 = 被选学生、学生端 = 本人），
                // 此处进 stats 当日盘点页；学生端与家长端共用同一条盘点路由
                onOpenStats = { statsStudentId -> navController.toStatsDaySummary(statsStudentId) },
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
        // ---- stats：当日盘点页（清单「查看盘点」/ 历史「查看这一天的盘点」入口；家长端与学生端共用，页面按会话再收敛越权） ----
        composable(
            route = StatsDestination.DAY_SUMMARY,
            arguments = listOf(
                navArgument(StatsDestination.ARG_STUDENT_ID) { type = NavType.StringType },
                // 盘点日期为可选查询参数（缺省 = [StatsDestination.ARG_EPOCH_DAY_TODAY]，页面解析为「今天」）：
                // 可选参数才允许不带查询串导航（清单「查看盘点」），缺失时回落「今天」而非抛缺少必填参数异常
                navArgument(StatsDestination.ARG_EPOCH_DAY) {
                    type = NavType.StringType
                    defaultValue = StatsDestination.ARG_EPOCH_DAY_TODAY.toString()
                },
            ),
        ) { entry ->
            DaySummaryRoute(
                studentId = entry.statsStudentIdArg(),
                // 盘点日期：清单入口为「今天」哨兵，历史入口为所选自然日
                epochDay = entry.statsEpochDayArg(StatsDestination.ARG_EPOCH_DAY),
                onBack = { navController.popBackStack() },
                // 「查看这一项详情」：携带盘点页解析出的学生 + 该项作业
                onOpenItemDetail = { studentId, homeworkId ->
                    navController.toStatsItemDetail(studentId, homeworkId)
                },
                // 「查看历史完成情况」：查询范围缺省为「今天」，由历史页按会话时区解析
                onOpenHistory = { studentId -> navController.toStatsHistory(studentId) },
            )
        }
        // ---- stats：单项详情页（预估 / 实际 / 暂停时长 + 困难度提示） ----
        composable(
            route = StatsDestination.ITEM_DETAIL,
            arguments = listOf(
                navArgument(StatsDestination.ARG_STUDENT_ID) { type = NavType.StringType },
                navArgument(StatsDestination.ARG_HOMEWORK_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            ItemDetailRoute(
                studentId = entry.statsStudentIdArg(),
                homeworkId = entry.statsHomeworkIdArg(),
                onBack = { navController.popBackStack() },
            )
        }
        // ---- stats：历史查询页（按日期 / 日期范围查看历史完成情况） ----
        composable(
            route = StatsDestination.HISTORY,
            arguments = listOf(
                navArgument(StatsDestination.ARG_STUDENT_ID) { type = NavType.StringType },
                // 查询参数声明默认值（= [StatsDestination.ARG_EPOCH_DAY_TODAY]，页面解析为「今天」）：
                // 可选参数才允许不带查询串导航，缺失时回落到「今天」而非抛缺少必填参数异常
                navArgument(StatsDestination.ARG_FROM_EPOCH_DAY) {
                    type = NavType.StringType
                    defaultValue = StatsDestination.ARG_EPOCH_DAY_TODAY.toString()
                },
                navArgument(StatsDestination.ARG_TO_EPOCH_DAY) {
                    type = NavType.StringType
                    defaultValue = StatsDestination.ARG_EPOCH_DAY_TODAY.toString()
                },
            ),
        ) { entry ->
            val statsStudentId = entry.statsStudentIdArg()
            // 日期范围选择器归导航接线层（stats 页面只回抛 onPickRange 意图，不引入选择器实现）；
            // 非空即展示，确认后按新范围重进历史查询页
            var rangePickerRequest by remember { mutableStateOf<StatsRangePickerRequest?>(null) }
            HistoryRoute(
                studentId = statsStudentId,
                fromEpochDay = entry.statsEpochDayArg(StatsDestination.ARG_FROM_EPOCH_DAY),
                toEpochDay = entry.statsEpochDayArg(StatsDestination.ARG_TO_EPOCH_DAY),
                onBack = { navController.popBackStack() },
                // 「查看这一天的盘点」：完整透传所选日期（stats 盘点路由的 epochDay 查询参数），
                // 使盘点页展示该历史日而非固定「今天」；fromHistory = true 保留本历史页在返回栈中
                // （返回键回到历史页、不丢失所选日期范围上下文），只清理历史页之上的旧盘点实例
                onOpenDaySummary = { studentId, epochDay ->
                    navController.toStatsDaySummary(
                        studentId = studentId,
                        epochDay = epochDay,
                        fromHistory = true,
                    )
                },
                onPickRange = { studentId, fromEpochDay, toEpochDay ->
                    rangePickerRequest = StatsRangePickerRequest(
                        studentId = studentId,
                        fromEpochDay = fromEpochDay,
                        toEpochDay = toEpochDay,
                    )
                },
            )
            rangePickerRequest?.let { request ->
                StatsHistoryRangePickerDialog(
                    // 哨兵值（-1 = 未指定日期）不作为选择器初值，交由用户重新选择
                    initialFromEpochDay = request.fromEpochDay.takeIf { it >= 0L },
                    initialToEpochDay = request.toEpochDay.takeIf { it >= 0L },
                    onDismiss = { rangePickerRequest = null },
                    onConfirm = { fromEpochDay, toEpochDay ->
                        rangePickerRequest = null
                        navController.toStatsHistoryRange(
                            studentId = request.studentId,
                            fromEpochDay = fromEpochDay,
                            toEpochDay = toEpochDay,
                        )
                    },
                )
            }
        }
        // 预留注册点：settings 等业务路由
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
 * 进入 stats 当日盘点页：
 * - 清单「查看盘点」：epochDay 缺省 [StatsDestination.ARG_EPOCH_DAY_TODAY]（盘点「今天」），
 *   返回栈语义为 `[清单, 盘点]`（替换栈中旧盘点实例，避免实例堆积）；
 * - 历史「查看这一天的盘点」：携带所选自然日，返回栈语义为 `[清单, 历史, 新盘点]`，
 *   返回键回到历史查询页（保留用户所选日期范围上下文）。
 *
 * studentId 由调用方给出（清单/历史页均已把目标学生解析到位），页面侧仍按会话再收敛一次：
 * 学生会话固定取本人 id，家长会话要求正数学生，故此处无需传「未指定」哨兵。
 *
 * 为什么是「移除旧实例 + 压入新实例」而不是复用栈中盘点页：NavBackStackEntry 的 arguments 在入栈时
 * 即固定，返回旧实例只会让页面按旧日期取数（盘点页 start 幂等，重进不会重取），
 * 用户会「在历史里选了昨天却看到今天的盘点」。故一律压入按新日期取数的新实例。
 *
 * 两个入口的差别只在「移除哪些旧实例」，由 [fromHistory] 区分，且两者都不产生回退环：
 * - [fromHistory] = false（清单入口，栈中盘点实例正好栈顶）：按路由模板
 *   popUpTo([StatsDestination.DAY_SUMMARY], inclusive = true) 连同其上页面一起移除后压入新实例；
 *   栈中没有盘点实例（首次从清单进入）时该 popUpTo 为无操作，等价于普通压栈。
 * - [fromHistory] = true（历史入口，盘点实例位于当前历史页之下）：若沿用 inclusive 弹出，
 *   会连带移除历史页（返回键只能回到清单），故改为 [removeDaySummaryInstances] 只清理历史页之上的
 *   旧盘点实例（如 `[清单, 旧盘点, 历史]` → `[清单, 历史]`），再普通压栈 → `[清单, 历史, 新盘点]`；
 *   历史页本身与其所选日期范围完整保留。
 *
 * 路由模板匹配的可靠性（已按 navigation-common 2.8.5 核实）：[androidx.navigation.NavDestination]
 * 把注册时的 route 原样保存在 `destination.route`（`setRoute` 仅另建 deep link 并用其 hashCode 作 id，
 * 不做占位符替换/默认值填充），故带查询参数的路由用模板串 popUpTo/popBackStack 可精确命中。
 *
 * @param studentId 目标学生 id（清单/历史页均已按会话解析到位）
 * @param epochDay 盘点日期（纪元日）；缺省「今天」哨兵由页面按会话时区解析
 * @param fromHistory 是否来自历史查询页入口（true = 保留历史页在返回栈中）
 */
private fun NavHostController.toStatsDaySummary(
    studentId: Long,
    epochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    fromHistory: Boolean = false,
) {
    if (fromHistory) {
        // 历史入口：只清掉历史页「之上」的旧盘点实例，历史页保留，返回键回到历史页
        removeDaySummaryInstances()
    }
    navigate(StatsDestination.daySummaryRoute(studentId, epochDay)) {
        if (!fromHistory) {
            // 清单入口：盘点实例即栈顶，连同其上页面一起替换，栈中恒只有一个盘点实例
            popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }
        }
    }
}

/**
 * 移除返回栈中「位于当前历史查询页之上」的盘点实例（自栈顶向下，遇到历史查询页即停）。
 *
 * 与 [toStatsDaySummary] 的清单入口（popUpTo inclusive）互补：历史页之下可能还压着从清单进入的旧盘点
 * 实例（如 `[清单, 旧盘点, 历史, 新盘点]`），若按 DAY_SUMMARY inclusive 弹栈会连带移除历史页，
 * 若只弹栈顶又会留下重复盘点实例，故按「栈顶 → 历史页」逐段清理：
 * 从栈顶逐个 popBackStack（inclusive = true），遇到历史查询页即停止（不删历史页）。
 *
 * 幂等且安全：栈中没有历史页（如历史页被深链直接打开）时弹到栈底仍无匹配，
 * 由 [popBackStack] 返回 false 兜底退出；栈顶本就是历史页时当前循环不执行，等价于普通压栈。
 */
private fun NavHostController.removeDaySummaryInstances() {
    while (true) {
        val top = currentBackStackEntry ?: return
        if (isStatsHistory(top)) return
        if (!isStatsDaySummary(top)) return
        if (!popBackStack(top.destination.id, inclusive = true)) return
    }
}

/** 是否为 stats 历史查询页（按注册路由模板精确匹配，含全部查询参数）。 */
private fun isStatsHistory(entry: androidx.navigation.NavBackStackEntry): Boolean =
    entry.destination.route == StatsDestination.HISTORY

/** 是否为 stats 当日盘点页（按注册路由模板精确匹配，含日期查询参数）。 */
private fun isStatsDaySummary(entry: androidx.navigation.NavBackStackEntry): Boolean =
    entry.destination.route == StatsDestination.DAY_SUMMARY

/** 进入 stats 单项详情页：携带盘点页解析出的学生 id 与该作业 id，返回键 popBackStack 回盘点页。 */
private fun NavHostController.toStatsItemDetail(studentId: Long, homeworkId: Long) {
    navigate(StatsDestination.itemDetailRoute(studentId, homeworkId))
}

/**
 * 进入 stats 历史查询页（当日盘点页「查看历史完成情况」）。
 *
 * 查询范围缺省传 [StatsDestination.ARG_EPOCH_DAY_TODAY] 哨兵（而非在导航层取「今天」）：
 * 由历史页用可注入时钟 + 业务时区解析，保证「今天」的口径与仓库当日窗口同源。
 */
private fun NavHostController.toStatsHistory(studentId: Long) {
    navigate(StatsDestination.historyRoute(studentId, StatsDestination.ARG_EPOCH_DAY_TODAY))
}

/**
 * 在历史查询页换日期范围：先移除当前历史查询实例，再压入携带新范围的新实例。
 *
 * 若直接 navigate，每换一次范围就压一层，返回键会逐层退回旧范围（回退环）；
 * 故按路由模板 popUpTo([StatsDestination.HISTORY], inclusive = true) 移除当前实例
 * （模板串匹配的可靠性同 [toStatsDaySummary] 注释），栈中恒只有一个历史查询页。
 */
private fun NavHostController.toStatsHistoryRange(studentId: Long, fromEpochDay: Long, toEpochDay: Long) {
    navigate(StatsDestination.historyRoute(studentId, fromEpochDay, toEpochDay)) {
        popUpTo(StatsDestination.HISTORY) { inclusive = true }
    }
}

/** 读取并解析 stats 路由的 studentId（缺失/非法 → 0，页面据此按会话解析本人或提示未选定学生）。 */
private fun androidx.navigation.NavBackStackEntry.statsStudentIdArg(): Long =
    StatsDestination.studentIdOf(arguments?.getString(StatsDestination.ARG_STUDENT_ID))

/** 读取并解析 stats 路由的 homeworkId（缺失/非法 → [StatsDestination.ARG_HOMEWORK_ID_NONE]）。 */
private fun androidx.navigation.NavBackStackEntry.statsHomeworkIdArg(): Long =
    StatsDestination.homeworkIdOf(arguments?.getString(StatsDestination.ARG_HOMEWORK_ID))

/** 读取并解析 stats 历史查询路由的 epochDay（缺失/非法 → [StatsDestination.ARG_EPOCH_DAY_TODAY]）。 */
private fun androidx.navigation.NavBackStackEntry.statsEpochDayArg(name: String): Long =
    StatsDestination.epochDayOf(arguments?.getString(name))

/**
 * 历史查询页「选择日期范围」的待确认请求：由 [HistoryRoute] 的 `onPickRange` 回抛的目标学生
 * （已按会话解析）+ 当前查询范围（作为选择器初值），确认后据此重进历史查询页。
 */
private data class StatsRangePickerRequest(
    val studentId: Long,
    val fromEpochDay: Long,
    val toEpochDay: Long,
)

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

/**
 * stats 历史查询页「选择日期范围」对话框（framework 导航接线层提供）。
 *
 * 为什么在框架层：stats 页面只回抛 `onPickRange(studentId, from, to)` 导航意图、不引入选择器实现，
 * 选中结果最终要变成 `stats/history/{studentId}?fromEpochDay=&toEpochDay=` 的路由参数，
 * 与 [StatsDestination.historyRoute] 的拼装口径必须同源（见 [toStatsHistoryRange]）。
 * 选择器用 Material3 自带实现（随 Compose BOM 引入），不新增第三方依赖。
 *
 * 日期与纪元日换算：Material3 以「该日 UTC 零点毫秒」表示日期，故纪元日 = 毫秒 / 一天的毫秒数，
 * 与 [StatsDestination] 的 epochDay 语义（UTC 纪元日）一致，不掺入本地时区偏移。
 *
 * @param initialFromEpochDay 起始日初值；null 表示无初值（路由哨兵 -1 = 未指定时由调用方转成 null）
 * @param initialToEpochDay 结束日初值；null 时选择器只带起始日初值
 * @param onDismiss 取消选择（点取消按钮或点击外部）
 * @param onConfirm 确认选择：仅选中起始日时按单日范围回调
 *   （与 [StatsDestination.historyRoute] 的 `toEpochDay` 默认等于 `fromEpochDay` 口径一致）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsHistoryRangePickerDialog(
    initialFromEpochDay: Long?,
    initialToEpochDay: Long?,
    onDismiss: () -> Unit,
    onConfirm: (fromEpochDay: Long, toEpochDay: Long) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFromEpochDay?.toUtcMillisOfDay(),
        initialSelectedEndDateMillis = initialToEpochDay?.toUtcMillisOfDay(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                // 未选起始日时无可查范围，禁用确定；只选起始日则按单日查询
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val startMillis = state.selectedStartDateMillis
                    val endMillis = state.selectedEndDateMillis ?: startMillis
                    if (startMillis != null && endMillis != null) {
                        onConfirm(startMillis.toEpochDayOf(), endMillis.toEpochDayOf())
                    }
                },
            ) { Text(text = "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    ) {
        DateRangePicker(state = state)
    }
}

/** 纪元日 → 该日 UTC 零点毫秒（Material3 日期选择器的时间基准）。 */
private fun Long.toUtcMillisOfDay(): Long = this * MILLIS_PER_DAY

/** UTC 零点毫秒 → 纪元日（向下取整，兼容 1970 年之前的负毫秒）。 */
private fun Long.toEpochDayOf(): Long = Math.floorDiv(this, MILLIS_PER_DAY)

/** 一天的毫秒数（86400000，非闰秒口径） */
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L