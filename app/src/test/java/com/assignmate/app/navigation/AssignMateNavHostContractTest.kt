package com.assignmate.app.navigation

import com.assignmate.app.auth.ui.AuthDestination
import com.assignmate.app.homework.ui.HomeworkDestination
import com.assignmate.app.settings.ui.SettingsDestination
import com.assignmate.app.stats.ui.StatsDestination
import com.assignmate.app.timer.ui.TimerDestination
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航接线契约静态一致性核查（framework：导航接线与系统组件声明）。
 *
 * 本环境无 Android 模拟器/真机，[AssignMateNavHost] 的「运行时读取路由参数」无法用设备验证，
 * 故以「源码级等价证据」覆盖该契约：直接解析 NavHost 源码中注册的 `route = ...` 与
 * `navArgument(...)` 声明，与各模块 Destination 常量逐条比对。
 *
 * 覆盖维度：
 * 1. 正向：四条 timer 路由模板与 NavHost 注册的 route 完全一致，navArgument 与占位符一一对应；
 * 2. 反向：不存在重复注册的同名路由、无未解析的占位符、无未被调用的私有导航扩展函数；
 * 3. 边界：路由参数解析的非法/缺失值回落口径（0 / -1）与 NavHost 页内取值口径一致。
 */
class AssignMateNavHostContractTest {

    private val navHostSource: String = readNavHostSource()

    /** 以括号配平切出每个 `composable(...) { ... }` 注册块，避免正则跨块误匹配。 */
    private val composableBlocks: List<String> = splitComposableBlocks(navHostSource)

    // ---- 1. 正向覆盖：路由模板与注册 route 一致 ----

    @Test
    fun `timer 四条路由模板均在 NavHost 中被注册`() {
        val registered = registeredRoutes()

        assertTrue("未注册 EXECUTION：$registered", registered.contains(TimerDestination.EXECUTION))
        assertTrue("未注册 REST：$registered", registered.contains(TimerDestination.REST))
        assertTrue("未注册 NEXT_ITEM：$registered", registered.contains(TimerDestination.NEXT_ITEM))
        assertTrue("未注册 COMPLETION：$registered", registered.contains(TimerDestination.COMPLETION))
    }

    @Test
    fun `homework 四条路由模板均在 NavHost 中被注册`() {
        val registered = registeredRoutes()

        assertTrue("未注册 LIST：$registered", registered.contains(HomeworkDestination.LIST))
        assertTrue("未注册 ENTRY：$registered", registered.contains(HomeworkDestination.ENTRY))
        assertTrue("未注册 TEMPLATE：$registered", registered.contains(HomeworkDestination.TEMPLATE))
        assertTrue("未注册 TIME_SET：$registered", registered.contains(HomeworkDestination.TIME_SET))
    }

    @Test
    fun `auth 六条路由模板均在 NavHost 中被注册`() {
        val registered = registeredRoutes()
        val authRoutes = listOf(
            AuthDestination.ROLE_SELECT,
            AuthDestination.PARENT_LOGIN,
            AuthDestination.PARENT_REGISTER,
            AuthDestination.STUDENT_ENTER,
            AuthDestination.PARENT_HOME,
            AuthDestination.STUDENT_HOME,
        )

        authRoutes.forEach { route ->
            assertTrue("未注册 auth 路由 $route：$registered", registered.contains(route))
        }
    }

    @Test
    fun `stats 三条路由模板均在 NavHost 中被注册`() {
        val registered = registeredRoutes()

        assertTrue("未注册 DAY_SUMMARY：$registered", registered.contains(StatsDestination.DAY_SUMMARY))
        assertTrue("未注册 ITEM_DETAIL：$registered", registered.contains(StatsDestination.ITEM_DETAIL))
        assertTrue("未注册 HISTORY：$registered", registered.contains(StatsDestination.HISTORY))
    }

    @Test
    fun `注册的 route 全部来自各模块 Destination 常量而非硬编码字面量`() {
        val literals = Regex("route\\s*=\\s*\"").findAll(navHostSource).count()

        assertEquals("NavHost 中不应以字符串字面量注册 route", 0, literals)
    }

    // ---- 2. 反向覆盖：无重复注册 / 无未解析占位符 ----

    @Test
    fun `不存在重复注册的同名路由`() {
        val registered = registeredRoutes()
        val duplicates = registered.groupingBy { it }.eachCount().filterValues { it > 1 }

        assertTrue("存在重复注册的同名路由：$duplicates", duplicates.isEmpty())
    }

    @Test
    fun `每条路由模板的占位符均有对应 navArgument 声明`() {
        composableBlocks.forEach { block ->
            val route = routeExpressionOf(block) ?: return@forEach
            val template = resolveRouteConstant(route) ?: route
            val placeholders = Regex("\\{(\\w+)}").findAll(template).map { it.groupValues[1] }.toList()

            placeholders.forEach { name ->
                assertTrue(
                    "路由 $template 的占位符 {$name} 缺少 navArgument 声明",
                    block.contains("ARG_" + name.toConstantCase()) || block.contains("navArgument(\"$name\")"),
                )
            }
        }
    }

    @Test
    fun `每条 navArgument 声明都能在路由模板中找到对应占位符`() {
        composableBlocks.forEach { block ->
            val route = routeExpressionOf(block) ?: return@forEach
            val template = resolveRouteConstant(route) ?: route
            val placeholders = Regex("\\{(\\w+)}").findAll(template).map { it.groupValues[1] }.toSet()

            val declared = Regex("navArgument\\(([^)]*)\\)")
                .findAll(block)
                .map { it.groupValues[1] }
                .toList()

            declared.forEach { raw ->
                val trimmed = raw.trim()
                // 形如 HomeworkDestination.ARG_STUDENT_ID 的常量取末段；字面量取引号内名称
                val token = trimmed.substringAfterLast('.')
                val name = if (token.startsWith("ARG_")) constantToParamName(token) else trimmed.trim('"')
                assertTrue("navArgument($raw) 在路由 $template 中无对应占位符", placeholders.contains(name))
            }
        }
    }

    @Test
    fun `timer 路由的 navArgument 名称使用 TimerDestination 常量且与占位符一一对应`() {
        val timerExpectations = mapOf(
            TimerDestination.EXECUTION to listOf("studentId", "homeworkId"),
            TimerDestination.REST to listOf("studentId", "homeworkId"),
            TimerDestination.NEXT_ITEM to listOf("studentId"),
            TimerDestination.COMPLETION to listOf("studentId"),
        )

        timerExpectations.forEach { (template, params) ->
            val block = blockForRoute(template)
            assertNotNull("未找到 timer 路由 $template 的注册块", block)

            val placeholders = Regex("\\{(\\w+)}").findAll(template).map { it.groupValues[1] }.toList()
            assertEquals("路由 $template 占位符与契约不一致", params, placeholders)

            params.forEach { name ->
                val expectedConstant = "TimerDestination.ARG_" + name.toConstantCase()
                assertTrue(
                    "路由 $template 的 $name 应使用 $expectedConstant 声明",
                    block!!.contains(expectedConstant),
                )
            }
        }
    }

    @Test
    fun `timer 各页入参经 TimerDestination 解析函数解析`() {
        listOf(TimerDestination.EXECUTION, TimerDestination.REST).forEach { template ->
            val block = blockForRoute(template)!!
            assertTrue("$template 的 studentId 应经 timerStudentIdArg() 解析", block.contains("timerStudentIdArg()"))
            assertTrue("$template 的 homeworkId 应经 timerHomeworkIdArg() 解析", block.contains("timerHomeworkIdArg()"))
        }
        listOf(TimerDestination.NEXT_ITEM, TimerDestination.COMPLETION).forEach { template ->
            val block = blockForRoute(template)!!
            assertTrue("$template 的 studentId 应经 timerStudentIdArg() 解析", block.contains("timerStudentIdArg()"))
            assertFalse("$template 无 homeworkId 占位符，不应解析 homeworkId", block.contains("timerHomeworkIdArg()"))
        }
        assertTrue(navHostSource.contains("TimerDestination.studentIdOf"))
        assertTrue(navHostSource.contains("TimerDestination.homeworkIdOf"))
        assertTrue(navHostSource.contains("TimerDestination.ARG_STUDENT_ID"))
        assertTrue(navHostSource.contains("TimerDestination.ARG_HOMEWORK_ID"))
    }

    // ---- 3. 导航扩展函数定义与调用（无未使用私有函数） ----

    @Test
    fun `全部导航扩展函数均有定义且被调用`() {
        val helpers = listOf(
            "toMain",
            "toRoleSelect",
            "toHomeworkList",
            "toHomeworkListOnStack",
            "toTimerExecution",
            "toTimerRest",
            "toTimerNextItem",
            "toTimerCompletion",
            "toStatsDaySummary",
            "toStatsItemDetail",
            "toStatsHistory",
            "toStatsHistoryRange",
            "toSettingsHome",
            "toSettingsOcrConfig",
            "toSettingsTheme",
        )

        helpers.forEach { helper ->
            val definition = Regex("fun\\s+NavHostController\\." + helper + "\\s*\\(").find(navHostSource)
            assertNotNull("导航扩展函数 $helper 未定义", definition)

            val callSites = Regex("\\." + helper + "\\s*\\(").findAll(navHostSource).count()
            assertTrue("导航扩展函数 $helper 未被调用（未使用私有函数会产生编译告警）", callSites >= 1)
        }
    }

    @Test
    fun `计时链路各跳均清理刚离开的页面避免回退环`() {
        val rest = blockForHelper("toTimerRest")!!
        assertTrue("toTimerRest 应 popUpTo 执行页", rest.contains("popUpTo(TimerDestination.EXECUTION) { inclusive = true }"))

        val next = blockForHelper("toTimerNextItem")!!
        assertTrue("toTimerNextItem 应 popUpTo 休息页", next.contains("popUpTo(TimerDestination.REST) { inclusive = true }"))

        val completion = blockForHelper("toTimerCompletion")!!
        assertTrue("toTimerCompletion 应 popUpTo 下一项页", completion.contains("popUpTo(TimerDestination.NEXT_ITEM) { inclusive = true }"))

        val onStack = blockForHelper("toHomeworkListOnStack")!!
        assertTrue("toHomeworkListOnStack 应优先 popBackStack 回栈中清单", onStack.contains("popBackStack(HomeworkDestination.LIST, inclusive = false)"))
        assertTrue("toHomeworkListOnStack 需有压入新清单的兜底", onStack.contains("navigate(HomeworkDestination.listRoute(studentId))"))
    }

    // ---- 4. 到点提醒同步接线（删除 / 重排时间两条入口） ----

    @Test
    fun `删除与重排时间的提醒同步已在 NavHost 接线到协调器`() {
        // 1) 清单路由：删除成功回调 → 取消提醒
        val listBlock = blockForRoute(HomeworkDestination.LIST)
        assertNotNull("未找到作业清单路由注册块", listBlock)
        assertTrue(
            "作业清单页应声明 onHomeworkRemoved 收尾回调",
            listBlock!!.contains("onHomeworkRemoved"),
        )
        assertTrue(
            "清单页 onHomeworkRemoved 应接到 reminderSync.cancelHomeworkReminderSync(homeworkId)",
            listBlock.contains("onHomeworkRemoved = { homeworkId -> reminderSync.cancelHomeworkReminderSync(homeworkId) }"),
        )

        // 2) 时间设定路由：保存成功回调 → 按新时刻同步提醒
        val timeSetBlock = blockForRoute(HomeworkDestination.TIME_SET)
        assertNotNull("未找到时间设定路由注册块", timeSetBlock)
        assertTrue(
            "时间设定页应声明 onHomeworkScheduleSaved 收尾回调",
            timeSetBlock!!.contains("onHomeworkScheduleSaved"),
        )
        assertTrue(
            "时间设定页 onHomeworkScheduleSaved 应接到 reminderSync.syncHomeworkReminder(homeworkId)",
            timeSetBlock.contains("onHomeworkScheduleSaved = { homeworkId -> reminderSync.syncHomeworkReminder(homeworkId) }"),
        )

        // 3) 协调器经 Hilt 注入导航宿主（@Composable 不能直接注入依赖，故走 ViewModel 载体）
        assertTrue(
            "NavHost 应经 Hilt 注入提醒同步接线层",
            navHostSource.contains("hiltViewModel<AssignMateNavHostViewModel>()") &&
                navHostSource.contains("@HiltViewModel") &&
                navHostSource.contains("ReminderSyncDispatcher"),
        )

        // 4) 接线层确实落到 timer 协调器的两个方法（删除 → cancel、重排 → sync）
        val dispatcherSource = readSource(
            "src/main/java/com/assignmate/app/navigation/ReminderSyncDispatcher.kt",
        )
        assertTrue(
            "ReminderSyncDispatcher 应调用协调器 cancelHomeworkReminder（删除入口）",
            dispatcherSource.contains("coordinator.cancelHomeworkReminder("),
        )
        assertTrue(
            "ReminderSyncDispatcher 应调用协调器 syncHomeworkReminder（重排时间入口）",
            dispatcherSource.contains("coordinator.syncHomeworkReminder("),
        )
        assertTrue(
            "提醒同步应在注入的作用域内启动（不阻塞主线程）",
            dispatcherSource.contains("scope.launch"),
        )
        assertTrue(
            "提醒同步失败应静默降级（catch 后仅记录）",
            dispatcherSource.contains("catch (e: Exception)"),
        )
    }

    @Test
    fun `homework 侧经事件外抛删除与保存成功且不引入 timer 依赖`() {
        // 删除成功事件（仅成功时发出）由清单页转成回调
        val listSource = readSource("src/main/java/com/assignmate/app/homework/ui/HomeworkListViewModel.kt")
        assertTrue("清单 ViewModel 应声明删除成功事件 Deleted", listSource.contains("data class Deleted("))
        assertTrue(
            "删除事件应仅在仓库返回 Success 时发出",
            listSource.contains("if (result is HomeworkOperationResult.Success)") &&
                listSource.contains("_events.send(HomeworkListEvent.Deleted(homeworkId = target.id))"),
        )
        val listScreenSource = readSource("src/main/java/com/assignmate/app/homework/ui/HomeworkListScreen.kt")
        assertTrue(
            "清单页应把删除成功事件转成 onHomeworkRemoved 回调",
            listScreenSource.contains("is HomeworkListEvent.Deleted -> onHomeworkRemoved(event.homeworkId)"),
        )

        // 排定成功事件携带 homeworkId，由时间设定页转成回调
        val timeSetSource = readSource("src/main/java/com/assignmate/app/homework/ui/HomeworkTimeSetViewModel.kt")
        assertTrue(
            "排定成功事件应携带 homeworkId",
            timeSetSource.contains("data class Saved(val homeworkId: Long, val message: String)"),
        )
        val timeSetScreenSource = readSource("src/main/java/com/assignmate/app/homework/ui/HomeworkTimeSetScreen.kt")
        assertTrue(
            "时间设定页应把保存成功事件转成 onHomeworkScheduleSaved 回调",
            timeSetScreenSource.contains("onHomeworkScheduleSaved(event.homeworkId)"),
        )

        // 依赖方向：homework 生产代码不得 import timer（回调/事件均为纯 Kotlin 类型）
        listOf(
            "src/main/java/com/assignmate/app/homework/ui/HomeworkListViewModel.kt",
            "src/main/java/com/assignmate/app/homework/ui/HomeworkListScreen.kt",
            "src/main/java/com/assignmate/app/homework/ui/HomeworkTimeSetViewModel.kt",
            "src/main/java/com/assignmate/app/homework/ui/HomeworkTimeSetScreen.kt",
        ).forEach { path ->
            assertFalse("$path 不应依赖 timer 模块", readSource(path).contains("import com.assignmate.app.timer"))
        }
    }

    // ---- 5. 边界值：路由参数解析口径 ----

    @Test
    fun `timer 路由参数解析边界与 NavHost 页内取值口径一致`() {
        assertEquals(0L, TimerDestination.studentIdOf("0"))
        assertEquals(5L, TimerDestination.homeworkIdOf("5"))
        assertEquals(TimerDestination.ARG_STUDENT_ID_NONE, TimerDestination.studentIdOf(null))
        assertEquals(TimerDestination.ARG_STUDENT_ID_NONE, TimerDestination.studentIdOf("abc"))
        assertEquals(TimerDestination.ARG_HOMEWORK_ID_NONE, TimerDestination.homeworkIdOf(null))
        assertEquals(TimerDestination.ARG_HOMEWORK_ID_NONE, TimerDestination.homeworkIdOf("abc"))
        assertEquals(TimerDestination.ARG_STUDENT_ID_NONE, TimerDestination.studentIdOf(""))
        assertEquals(TimerDestination.ARG_STUDENT_ID_NONE, TimerDestination.studentIdOf("0x"))
        assertEquals(Long.MAX_VALUE, TimerDestination.studentIdOf(Long.MAX_VALUE.toString()))
        assertEquals(Long.MIN_VALUE, TimerDestination.homeworkIdOf(Long.MIN_VALUE.toString()))
        assertEquals(0L, TimerDestination.ARG_STUDENT_ID_NONE)
        assertEquals(5L, TimerDestination.homeworkIdOf("5"))
        assertEquals(TimerDestination.ARG_HOMEWORK_ID_NONE, TimerDestination.homeworkIdOf("99999999999999999999"))
    }

    // ---- 6. stats 统计链路接线（清单 → 盘点 → 单项详情 / 历史查询） ----

    @Test
    fun `清单页「查看盘点」已接线到 stats 当日盘点页`() {
        val listBlock = blockForRoute(HomeworkDestination.LIST)
        assertNotNull("未找到作业清单路由注册块", listBlock)
        assertTrue(
            "清单页应把 onOpenStats 接到 stats 当日盘点页",
            listBlock!!.contains("onOpenStats = { statsStudentId -> navController.toStatsDaySummary(statsStudentId) }"),
        )

        val daySummary = blockForHelper("toStatsDaySummary")!!
        assertTrue(
            "toStatsDaySummary 应进入 StatsDestination.daySummaryRoute 并携带日期",
            daySummary.contains("navigate(StatsDestination.daySummaryRoute(studentId, epochDay))"),
        )
        assertTrue(
            "toStatsDaySummary 应声明 epochDay 缺省为「今天」哨兵（清单入口即盘点今天）",
            daySummary.contains("epochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY"),
        )
        assertTrue(
            "toStatsDaySummary 应声明 fromHistory 入口开关（清单/历史两个入口返回栈语义不同）",
            daySummary.contains("fromHistory: Boolean = false"),
        )
        assertTrue(
            "toStatsDaySummary 清单入口应 popUpTo(DAY_SUMMARY, inclusive) 替换旧盘点实例（旧实例参数固定，复用会显示旧日期）",
            daySummary.contains("popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }"),
        )
        assertTrue(
            "清单入口（fromHistory = false）才按 DAY_SUMMARY inclusive 弹栈",
            daySummary.contains("if (!fromHistory)"),
        )
    }

    @Test
    fun `历史入口查看某日盘点应保留历史页并可返回历史页`() {
        val historyBlock = blockForRoute(StatsDestination.HISTORY)
        assertNotNull("未找到历史查询路由注册块", historyBlock)
        // 断言拆成「回调形参完整接收 epochDay」+「按所选日期进入盘点页」两段，避免依赖源码缩进
        assertTrue(
            "历史页「查看这一天的盘点」回调应接收 studentId 与 epochDay 两个入参（不再丢弃日期）",
            historyBlock!!.contains("onOpenDaySummary = { studentId, epochDay ->"),
        )
        assertTrue(
            "历史页应把所选 epochDay 透传给盘点页导航",
            historyBlock.contains("navController.toStatsDaySummary("),
        )
        assertTrue(
            "历史入口应显式传 fromHistory = true（区别于清单入口）",
            historyBlock.contains("fromHistory = true"),
        )

        val daySummary = blockForHelper("toStatsDaySummary")!!
        assertTrue(
            "历史入口应只清理历史页之上的旧盘点实例（不连同历史页一起弹出，返回键才能回到历史页）",
            daySummary.contains("removeDaySummaryInstances()"),
        )
        assertTrue(
            "盘点实例判定应按注册路由模板精确匹配 DAY_SUMMARY",
            navHostSource.contains("entry.destination.route == StatsDestination.DAY_SUMMARY"),
        )
    }

    @Test
    fun `从历史页进入的盘点实例清理不越过历史页`() {
        val cleaner = blockForHelper("removeDaySummaryInstances")!!
        val history = definitionOf("isStatsHistory")
        val daySummary = definitionOf("isStatsDaySummary")

        assertTrue(
            "清理应自栈顶逐个弹出（inclusive = true），遇非盘点页即停",
            cleaner.contains("currentBackStackEntry") &&
                cleaner.contains("popBackStack(top.destination.id, inclusive = true)"),
        )
        assertTrue(
            "遇历史查询页应停止（不弹出历史页，保留其日期范围上下文）",
            cleaner.contains("if (isStatsHistory(top)) return"),
        )
        assertTrue(
            "只清理盘点实例（非盘点页即停，兜底防弹穿返回栈）",
            cleaner.contains("if (!isStatsDaySummary(top)) return"),
        )
        assertTrue(
            "历史页判定应按注册路由模板精确匹配 HISTORY",
            history.contains("entry.destination.route == StatsDestination.HISTORY"),
        )
        assertTrue(
            "盘点页判定应按注册路由模板精确匹配 DAY_SUMMARY",
            daySummary.contains("entry.destination.route == StatsDestination.DAY_SUMMARY"),
        )
        assertFalse(
            "历史入口不应使用按 DAY_SUMMARY inclusive 的 popUpTo（会连带移除历史页）",
            cleaner.contains("popUpTo"),
        )
    }

    @Test
    fun `盘点路由的 epochDay 为可选参数且历史页完整透传所选日期`() {
        val dayBlock = blockForRoute(StatsDestination.DAY_SUMMARY)
        assertNotNull("未找到当日盘点路由注册块", dayBlock)
        assertTrue(
            "DAY_SUMMARY 应声明 epochDay 查询参数的 navArgument",
            dayBlock!!.contains("navArgument(StatsDestination.ARG_EPOCH_DAY)"),
        )
        assertTrue(
            "epochDay 的默认值应为「今天」哨兵（可选参数，缺省导航不抛缺少必填参数异常）",
            dayBlock.contains("defaultValue = StatsDestination.ARG_EPOCH_DAY_TODAY.toString()"),
        )
        assertTrue(
            "盘点页应经 statsEpochDayArg(ARG_EPOCH_DAY) 解析后传给页面",
            dayBlock.contains("epochDay = entry.statsEpochDayArg(StatsDestination.ARG_EPOCH_DAY)"),
        )

        val historyBlock = blockForRoute(StatsDestination.HISTORY)
        assertNotNull("未找到历史查询路由注册块", historyBlock)
        // 断言拆成「回调形参完整接收 epochDay」+「按所选日期进入盘点页」两段，避免依赖源码缩进
        assertTrue(
            "历史页「查看这一天的盘点」回调应接收 studentId 与 epochDay 两个入参（不再丢弃日期）",
            historyBlock!!.contains("onOpenDaySummary = { studentId, epochDay ->"),
        )
        assertTrue(
            "历史页应把所选 epochDay 透传给盘点页导航（同时标记 fromHistory 以保留本历史页）",
            historyBlock.contains("navController.toStatsDaySummary(") &&
                historyBlock.contains("fromHistory = true"),
        )
    }

    @Test
    fun `盘点页可进入单项详情与历史查询`() {
        val dayBlock = blockForRoute(StatsDestination.DAY_SUMMARY)
        assertNotNull("未找到当日盘点路由注册块", dayBlock)
        assertTrue(
            "盘点页 onOpenItemDetail 应接到单项详情页",
            dayBlock!!.contains("navController.toStatsItemDetail(studentId, homeworkId)"),
        )
        assertTrue(
            "盘点页 onOpenHistory 应接到历史查询页",
            dayBlock.contains("onOpenHistory = { studentId -> navController.toStatsHistory(studentId) }"),
        )

        val itemDetail = blockForHelper("toStatsItemDetail")!!
        assertTrue(
            "toStatsItemDetail 应进入 StatsDestination.itemDetailRoute（携带学生与作业）",
            itemDetail.contains("navigate(StatsDestination.itemDetailRoute(studentId, homeworkId))"),
        )
        val history = blockForHelper("toStatsHistory")!!
        assertTrue(
            "toStatsHistory 查询范围缺省应传「今天」哨兵（由页面按时区解析）",
            history.contains("StatsDestination.historyRoute(studentId, StatsDestination.ARG_EPOCH_DAY_TODAY)"),
        )
    }

    @Test
    fun `历史查询换日期范围替换当前实例且选择器归接线层`() {
        val historyBlock = blockForRoute(StatsDestination.HISTORY)
        assertNotNull("未找到历史查询路由注册块", historyBlock)
        assertTrue(
            "历史页 onPickRange 应弹出日期范围选择器（接线层提供实现）",
            historyBlock!!.contains("StatsHistoryRangePickerDialog("),
        )
        assertTrue(
            "范围确认后应经 toStatsHistoryRange 重进历史查询页",
            historyBlock.contains("navController.toStatsHistoryRange("),
        )

        val rangeHelper = blockForHelper("toStatsHistoryRange")!!
        assertTrue(
            "toStatsHistoryRange 应按历史路由模板 popUpTo inclusive 移除旧查询实例",
            rangeHelper.contains("popUpTo(StatsDestination.HISTORY) { inclusive = true }"),
        )
        assertTrue(
            "toStatsHistoryRange 应压入携带新范围的统计历史路由",
            rangeHelper.contains("navigate(StatsDestination.historyRoute(studentId, fromEpochDay, toEpochDay))"),
        )

        // 选择器实现只用 Material3 自带组件（随 Compose BOM 引入，无新增第三方依赖）
        assertTrue(
            "日期范围选择器应使用 Material3 自带实现",
            navHostSource.contains("import androidx.compose.material3.DateRangePicker") &&
                navHostSource.contains("DateRangePicker(state = state)"),
        )
    }

    @Test
    fun `stats 路由参数经 StatsDestination 解析函数解析`() {
        assertTrue(
            "当日盘点/单项详情的学生 id 应经 statsStudentIdArg() 解析",
            blockForRoute(StatsDestination.DAY_SUMMARY)!!.contains("statsStudentIdArg()") &&
                blockForRoute(StatsDestination.ITEM_DETAIL)!!.contains("statsStudentIdArg()"),
        )
        assertTrue(
            "单项详情应经 statsHomeworkIdArg() 解析 homeworkId",
            blockForRoute(StatsDestination.ITEM_DETAIL)!!.contains("statsHomeworkIdArg()"),
        )
        assertTrue(
            "历史查询应经 statsEpochDayArg() 解析起止日期",
            blockForRoute(StatsDestination.HISTORY)!!.let {
                it.contains("statsEpochDayArg(StatsDestination.ARG_FROM_EPOCH_DAY)") &&
                    it.contains("statsEpochDayArg(StatsDestination.ARG_TO_EPOCH_DAY)")
            },
        )
        assertTrue(navHostSource.contains("StatsDestination.studentIdOf"))
        assertTrue(navHostSource.contains("StatsDestination.homeworkIdOf"))
        assertTrue(navHostSource.contains("StatsDestination.epochDayOf"))
    }

    @Test
    fun `历史查询的日期参数声明默认值使其成为可选参数`() {
        val historyBlock = blockForRoute(StatsDestination.HISTORY)!!
        val optionalCount = Regex(
            "defaultValue = StatsDestination\\.ARG_EPOCH_DAY_TODAY\\.toString\\(\\)",
        ).findAll(historyBlock).count()

        assertEquals("起止日期两个查询参数都应声明「今天」哨兵默认值", 2, optionalCount)
    }

    @Test
    fun `stats 路由参数解析边界与哨兵值口径一致`() {
        assertEquals(StatsDestination.ARG_STUDENT_ID_NONE, StatsDestination.studentIdOf(null))
        assertEquals(StatsDestination.ARG_STUDENT_ID_NONE, StatsDestination.studentIdOf("abc"))
        assertEquals(StatsDestination.ARG_HOMEWORK_ID_NONE, StatsDestination.homeworkIdOf(null))
        assertEquals(StatsDestination.ARG_EPOCH_DAY_TODAY, StatsDestination.epochDayOf(null))
        assertEquals(StatsDestination.ARG_EPOCH_DAY_TODAY, StatsDestination.epochDayOf("x"))
        assertEquals(2L, StatsDestination.studentIdOf("2"))
        assertEquals(7L, StatsDestination.homeworkIdOf("7"))
        assertEquals(19_675L, StatsDestination.epochDayOf("19675"))
        // 路由拼装与解析同源：拼出的路由包含解析函数认识的两个查询参数
        val route = StatsDestination.historyRoute(2L, 19_674L, 19_675L)
        assertTrue("历史路由应含起始日查询参数：$route", route.contains("fromEpochDay=19674"))
        assertTrue("历史路由应含结束日查询参数：$route", route.contains("toEpochDay=19675"))
    }

    // ---- 源码解析工具 ----

    /** 读取 NavHost 源码；Gradle 单测工作目录为 app/，同时兼容从仓库根运行。 */
    private fun readNavHostSource(): String =
        readSource("src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt")

    /**
     * 按相对 `app/` 的路径读取生产源码（Gradle 单测工作目录为 app/，同时兼容从仓库根运行）；
     * 供「接线契约」类断言核对被改动的多个文件，避免只看 NavHost 而漏掉两侧实现。
     */
    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative), File("../app/" + relative))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative，尝试路径：${candidates.map { it.absolutePath }}", file)
        return file!!.readText()
    }

    /** 以括号配平提取 `composable(...) { ... }` 块（跳过字符串字面量与注释）。 */
    private fun splitComposableBlocks(source: String): List<String> {
        val blocks = mutableListOf<String>()
        var index = source.indexOf("composable(")
        while (index >= 0) {
            var depth = 0
            var end = -1
            var cursor = index + "composable".length
            var inString = false
            var inLineComment = false
            var inBlockComment = false
            while (cursor < source.length) {
                val ch = source[cursor]
                val next = source.getOrNull(cursor + 1)
                when {
                    inLineComment -> if (ch == '\n') inLineComment = false
                    inBlockComment -> if (ch == '*' && next == '/') {
                        inBlockComment = false
                        cursor++
                    }
                    inString -> when {
                        ch == '\\' -> cursor++
                        ch == '"' -> inString = false
                    }
                    ch == '/' && next == '/' -> inLineComment = true
                    ch == '/' && next == '*' -> inBlockComment = true
                    ch == '"' -> inString = true
                    ch == '(' -> depth++
                    ch == ')' -> {
                        depth--
                        if (depth == 0) end = cursor
                    }
                }
                if (end >= 0) break
                cursor++
            }
            if (end < 0) break
            val bodyStart = source.indexOf('{', end)
            if (bodyStart < 0) break
            var bodyDepth = 0
            var bodyEnd = -1
            var i = bodyStart
            inString = false
            inLineComment = false
            inBlockComment = false
            while (i < source.length) {
                val ch = source[i]
                val next = source.getOrNull(i + 1)
                when {
                    inLineComment -> if (ch == '\n') inLineComment = false
                    inBlockComment -> if (ch == '*' && next == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> when {
                        ch == '\\' -> i++
                        ch == '"' -> inString = false
                    }
                    ch == '/' && next == '/' -> inLineComment = true
                    ch == '/' && next == '*' -> inBlockComment = true
                    ch == '"' -> inString = true
                    ch == '{' -> bodyDepth++
                    ch == '}' -> {
                        bodyDepth--
                        if (bodyDepth == 0) bodyEnd = i
                    }
                }
                if (bodyEnd >= 0) break
                i++
            }
            if (bodyEnd < 0) break
            blocks += source.substring(index, bodyEnd + 1)
            index = source.indexOf("composable(", bodyEnd)
        }
        return blocks
    }

    /**
     * 提取 `route = X` 中的表达式文本。
     *
     * 兼容两种注册写法：单行 `composable(route = X) { ... }`（以 `)` 结束）
     * 与多行 `composable(\n route = X,\n arguments = ...)`（以 `,` 结束）。
     */
    private fun routeExpressionOf(block: String): String? =
        Regex("route\\s*=\\s*([A-Za-z_][\\w.]*)\\s*[,)]").find(block)?.groupValues?.get(1)

    /** 取某路由模板对应的注册块。 */
    private fun blockForRoute(template: String): String? =
        composableBlocks.firstOrNull { block ->
            val route = routeExpressionOf(block) ?: return@firstOrNull false
            (resolveRouteConstant(route) ?: route) == template
        }

    /**
     * 取某个私有导航函数的「定义文本」（签名 + 体），同时适用于花括号体与表达式体函数。
     *
     * [blockForHelper] 只适用于带花括号体的实现（按 `{` 配平切片，对表达式体函数会错切到后续函数）；
     * 判定类表达式体函数（如 `isStatsHistory`/`isStatsDaySummary`）走本方法：
     * 自 `private fun <name>(` 起，到下一个 `private fun` 声明起点或参数列表所在行末尾为止。
     */
    private fun definitionOf(name: String): String {
        val start = Regex("private\\s+fun\\s+" + name + "\\s*\\(").find(navHostSource)
        assertNotNull("未找到私有导航函数 $name 定义", start)
        val from = start!!.range.first
        val nextFun = Regex("private\\s+fun\\s+").find(navHostSource, from + 1)
        val end = if (nextFun != null) {
            nextFun.range.first
        } else {
            navHostSource.indexOf('\n', navHostSource.indexOf(')', from)).takeIf { it > 0 } ?: navHostSource.length
        }
        return navHostSource.substring(from, end)
    }

    /** 取某个私有导航扩展函数的完整定义块（括号配平）。 */
    private fun blockForHelper(name: String): String? {
        val match = Regex("fun\\s+NavHostController\\." + name + "\\s*\\(").find(navHostSource) ?: return null
        val start = match.range.first
        val open = navHostSource.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < navHostSource.length) {
            when (navHostSource[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return navHostSource.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    /** 把 `Module.ROUTE` 形式的表达式解析为实际路由字符串。 */
    private fun resolveRouteConstant(expression: String): String? = when (expression) {
        "AuthDestination.ROLE_SELECT" -> AuthDestination.ROLE_SELECT
        "AuthDestination.PARENT_LOGIN" -> AuthDestination.PARENT_LOGIN
        "AuthDestination.PARENT_REGISTER" -> AuthDestination.PARENT_REGISTER
        "AuthDestination.STUDENT_ENTER" -> AuthDestination.STUDENT_ENTER
        "AuthDestination.PARENT_HOME" -> AuthDestination.PARENT_HOME
        "AuthDestination.STUDENT_HOME" -> AuthDestination.STUDENT_HOME
        "HomeworkDestination.LIST" -> HomeworkDestination.LIST
        "HomeworkDestination.ENTRY" -> HomeworkDestination.ENTRY
        "HomeworkDestination.TEMPLATE" -> HomeworkDestination.TEMPLATE
        "HomeworkDestination.TIME_SET" -> HomeworkDestination.TIME_SET
        "TimerDestination.EXECUTION" -> TimerDestination.EXECUTION
        "TimerDestination.REST" -> TimerDestination.REST
        "TimerDestination.NEXT_ITEM" -> TimerDestination.NEXT_ITEM
        "TimerDestination.COMPLETION" -> TimerDestination.COMPLETION
        "StatsDestination.DAY_SUMMARY" -> StatsDestination.DAY_SUMMARY
        "StatsDestination.ITEM_DETAIL" -> StatsDestination.ITEM_DETAIL
        "StatsDestination.HISTORY" -> StatsDestination.HISTORY
        "SettingsDestination.HOME" -> SettingsDestination.HOME
        "SettingsDestination.OCR_CONFIG" -> SettingsDestination.OCR_CONFIG
        "SettingsDestination.THEME" -> SettingsDestination.THEME
        else -> null
    }

    private fun registeredRoutes(): List<String> {
        val routes = composableBlocks.mapNotNull { block ->
            val expression = routeExpressionOf(block) ?: return@mapNotNull null
            resolveRouteConstant(expression)
        }
        assertEquals(
            "存在未解析的 route 表达式（新增路由需同步本测试的白名单）",
            composableBlocks.size,
            routes.size,
        )
        return routes
    }

    private fun String.toConstantCase(): String =
        replace(Regex("([a-z])([A-Z])")) { it.groupValues[1] + "_" + it.groupValues[2] }.uppercase()

    private fun constantToParamName(constant: String): String {
        val tail = constant.removePrefix("ARG_")
        return tail.split('_').joinToString("") { part ->
            part.lowercase().replaceFirstChar { it.uppercase() }
        }.replaceFirstChar { it.lowercase() }
    }
}
