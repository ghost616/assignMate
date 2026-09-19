package com.assignmate.app.navigation

import com.assignmate.app.auth.ui.AuthDestination
import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.ui.HomeworkDestination
import com.assignmate.app.settings.ui.SettingsDestination
import com.assignmate.app.stats.ui.StatsDestination
import com.assignmate.app.timer.data.FakeAlarmScheduler
import com.assignmate.app.timer.data.FakeHomeworkRepository
import com.assignmate.app.timer.data.HomeworkReminderCoordinator
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import com.assignmate.app.timer.domain.TimerConstants
import com.assignmate.app.timer.ui.TimerDestination
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * framework 交付面整洁性 + 提醒同步静默分支可观测性 + KDoc 计数对齐的契约核查。
 *
 * 三项均非功能缺陷，而是「工程/可观测性债」：
 * A. `.lizhu_env/`（测试隔离用的整仓副本，含独立 build 产物）此前不在 `.gitignore` 中，
 *    会污染 git status 与交付面；本类断言忽略条目存在、路径口径可命中、仓库根 `.gitignore` 可被定位；
 * B. [ReminderSyncDispatcher.syncHomeworkSavedReminder] 的「作业 id 与学生 id 均非正数」分支
 *    原为静默 no-op，提醒同步缺口会静默复现；本类断言该分支**确实留痕**且内容只含 id 类信息，
 *    并逐条锁定三条分支互斥（不会误走别的分支、不会产生任何调度）；
 * C. 接线层 KDoc 所述入口计数与实现对齐（实际四条入口；spec 曾写五条时效性入口，
 *    其中录入页与模板页共用同一入口），且四个入口与四个公开方法逐条相符。
 *
 * 另含回归核查（不得回归的既有契约）：20+ 条路由注册无重复、settings 三路由以
 * [SettingsDestination] 常量注册、[AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY] 保留可选 epochDay、
 * 计时与统计链路各跳仍带 popUpTo（无回退环）、提醒同步三条页面接线与计时入口语义不变。
 *
 * 环境边界：本仓库未开启 `testOptions.unitTests.isReturnDefaultValues`，纯 JVM 下 android.util.Log 未桩实现，
 * 故「debug 留痕」以**可注入日志出口**做行为级断言（替身出口记录调用），
 * 生产出口用一行 `Log.d` 并以源码锚点核验（真机范围）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrameworkDeliverySurfaceContractTest {

    private val navHostSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt",
    )

    private val dispatcherSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/ReminderSyncDispatcher.kt",
    )

    // ---- A. `.lizhu_env/` 交付面清理 ----

    @Test
    fun `交付面忽略规则已覆盖测试隔离工作区`() {
        val gitignore = rootFile(".gitignore")
        assertTrue("未找到仓库根 .gitignore（解析根：${gitignore.absolutePath}）", gitignore.isFile)
        val rules = gitignore.readLines()
        val entry = rules.map { it.trim() }.firstOrNull { it == LIZHU_ENV_ENTRY }

        assertEquals(
            "`.lizhu_env/` 必须作为独立忽略规则出现（不得把整仓副本纳入版本控制）",
            LIZHU_ENV_ENTRY,
            entry,
        )
        assertTrue(
            "新增忽略规则应位于 .gitignore 末尾的 DSH 元数据段落之后（规则顺序：先元数据后隔离工作区）",
            rules.indexOfFirst { it.trim() == LIZHU_ENV_ENTRY } >
                rules.indexOfFirst { it.trim() == ".module_agent/" },
        )
        assertTrue(
            "忽略条目应附中文注释说明用途（否则后人无法判断该目录可否清理）",
            commentAbove(gitignore.readText())?.contains("测试隔离") == true,
        )
    }

    @Test
    fun `忽略规则以目录口径命中隔离工作区及其构建产物`() {
        val rules = rootFile(".gitignore").readLines()

        assertTrue(
            "`.lizhu_env/` 目录本身必须命中忽略规则",
            isIgnored(LIZHU_ENV_ENTRY.removeSuffix("/"), rules),
        )
        assertTrue(
            "隔离工作区内的 build 产物（整仓副本自带 build 目录）必须命中忽略规则",
            isIgnored(".lizhu_env/v5_probe/build", rules) &&
                isIgnored(".lizhu_env/assignmate_verify/app/build", rules),
        )
        assertFalse(
            "忽略口径不得误伤正常源码目录（如 app/src/main）",
            isIgnored("app/src/main", rules),
        )
    }

    // ---- B. 静默分支可观测（debug 留痕） ----

    @Test
    fun `id 同时不可用时静默分支输出 debug 日志且不做任何同步`() = runTest {
        val recorder = RecordingLogSink()
        val fixture = fixture(recorder)
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = 0L, savedHomeworkId = 0L)

        assertEquals("静默分支必须留痕（此前无线索、缺口静默复现）", 1, recorder.entries.size)
        val logged = recorder.entries.single()
        assertEquals("日志 TAG 应与工程约定一致（类名）", "ReminderSyncDispatcher", logged.tag)
        assertNull("留痕是无异常的正常降级，不应带异常", logged.cause)
        assertTrue(
            "留痕应可被开发侧识别为「跳过同步」，实际内容：${logged.message}",
            logged.message.contains("跳过"),
        )
        assertTrue(
            "留痕只含 id 类信息（无敏感内容），实际内容：${logged.message}",
            logged.message.contains("savedHomeworkId=0") && logged.message.contains("studentId=0"),
        )

        advanceUntilIdle()
        assertTrue("该分支不得设置任何闹钟", fixture.scheduler.scheduled.isEmpty())
        assertTrue("该分支不得取消任何闹钟（避免误伤他人提醒）", fixture.scheduler.cancelled.isEmpty())
        assertTrue("该分支不得产生逐日闹钟", fixture.scheduler.scheduledDaily.isEmpty())
    }

    @Test
    fun `静默分支对全部非正数 id 组合均留痕且不误走其它分支`() = runTest {
        val combinations = listOf(
            0L to 0L,
            0L to -1L,
            -7L to 0L,
            Long.MIN_VALUE to Long.MIN_VALUE,
        )

        combinations.forEach { (studentId, savedHomeworkId) ->
            val recorder = RecordingLogSink()
            val fixture = fixture(recorder)
            fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))

            fixture.dispatcher.syncHomeworkSavedReminder(studentId = studentId, savedHomeworkId = savedHomeworkId)
            advanceUntilIdle()

            assertEquals(
                "非正数组合（studentId=$studentId, savedHomeworkId=$savedHomeworkId）必须留痕一次",
                1,
                recorder.entries.size,
            )
            assertTrue(
                "留痕应带上该组合的两个 id（便于定位缺口来源）",
                recorder.entries.single().message.contains("savedHomeworkId=$savedHomeworkId") &&
                    recorder.entries.single().message.contains("studentId=$studentId"),
            )
            assertTrue(
                "拿不到 id 又定位不到学生时不得按某个学生整份纠正（不猜测、不误伤）",
                fixture.scheduler.scheduled.isEmpty() && fixture.scheduler.scheduledDaily.isEmpty(),
            )
        }
    }

    @Test
    fun `可执行分支不应留痕以免日志噪声掩盖真实降级`() = runTest {
        val recorder = RecordingLogSink()
        val fixture = fixture(recorder)
        fixture.put(timerTestHomework(id = 7L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + MINUTE))
        fixture.put(timerTestHomework(id = 8L, status = HomeworkStatus.PENDING, startTimeMillis = BASE + 2 * MINUTE))

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 7L)
        advanceUntilIdle()
        assertEquals(
            "按 id 精确同步分支成功时不产生任何日志（留痕只属静默 no-op 分支）",
            0,
            recorder.entries.size,
        )

        fixture.dispatcher.syncHomeworkSavedReminder(studentId = STUDENT_ID, savedHomeworkId = 0L)
        advanceUntilIdle()
        assertEquals(
            "家长会话兜底（整份清单纠正）分支成功时同样不产生日志",
            0,
            recorder.entries.size,
        )
    }

    @Test
    fun `生产日志出口为 debug 级且 TAG 与类名一致`() {
        val sinkSource = readSource("src/main/java/com/assignmate/app/navigation/ReminderSyncLogSink.kt")

        assertTrue(
            "生产出口应是可注入的 ReminderSyncLogSink 实现（便于测试断言留痕）",
            sinkSource.contains("fun interface ReminderSyncLogSink") &&
                sinkSource.contains("object AndroidReminderSyncLogSink : ReminderSyncLogSink"),
        )
        assertTrue("debug 留痕应走 android.util.Log.d", sinkSource.contains("Log.d(tag, message"))
        assertTrue(
            "日志出口必须有生产绑定（否则「静默分支不再无痕」的契约会被 Hilt 装配绕过）",
            readSource("src/main/java/com/assignmate/app/di/ApplicationScopeModule.kt")
                .contains("fun provideReminderSyncLogSink(): ReminderSyncLogSink = AndroidReminderSyncLogSink"),
        )
        assertTrue(
            "接线层 TAG 常量应为类名口径",
            dispatcherSource.contains("const val TAG = \"ReminderSyncDispatcher\""),
        )
        assertTrue(
            "接线层唯一生产日志出口应经 logSink（不再直接调 android.util.Log）",
            dispatcherSource.contains("logSink.debug(") &&
                !dispatcherSource.contains("Log.d("),
        )
        assertTrue(
            "静默分支应显式留痕（结构级锚点，防止分支体被改回空实现）",
            Regex(
                "runCatching\\s*\\{\\s*logSink\\.debug\\(\\s*TAG,[\\s\\S]*?null,",
            ).containsMatchIn(dispatcherSource),
        )
        assertFalse(
            "fun interface 的抽象方法不可带默认值，故无异常场景须显式传 null（保证留痕不返回 Unit 空实现）",
            Regex("fun\\s+debug\\([^)]*=\\s*null\\s*\\)").containsMatchIn(sinkSource),
        )
    }

    // ---- C. KDoc 计数与入口口径对齐 ----

    @Test
    fun `接线层 KDoc 所述入口计数与实现逐条相符`() {
        val entries = Regex("^\\s*\\* (\\d)\\. ", RegexOption.MULTILINE)
            .findAll(dispatcherSource)
            .map { it.groupValues[1].toInt() }
            .toList()

        assertEquals(
            "KDoc 应显式列出四个提醒同步入口（此前与 spec 的五入口口径计数不一致）",
            listOf(1, 2, 3, 4),
            entries,
        )
        assertTrue(
            "KDoc 应以「四个」声明入口数（以代码为准，不再出现五个/四个口径漂移）",
            dispatcherSource.contains("覆盖四个提醒同步入口"),
        )
        assertTrue(
            "spec 曾把录入页与模板页记为两条入口；KDoc 须显式说明二者共用同一入口",
            dispatcherSource.contains("共用同一入口"),
        )

        val publicMethods = Regex("fun\\s+(\\w+)\\s*\\(")
            .findAll(dispatcherSource)
            .map { it.groupValues[1] }
            .filter { it != "debug" && it != "launchSafely" && it != "logFailure" }
            .toList()
        assertEquals(
            "四个入口应逐条对应接线层的四个对外方法（多出的对外方法意味着未在 KDoc 登记的入口）",
            listOf(
                "cancelHomeworkReminderSync",
                "syncHomeworkReminder",
                "syncStudentReminders",
                "syncHomeworkSavedReminder",
            ),
            publicMethods,
        )
        /* 私有辅助方法（launchSafely / logFailure）不构成入口：核验它们确实带 private 修饰 */
        assertTrue(
            "辅助方法必须保持 private（否则会变成未登记的第五个入口）",
            Regex("private\\s+fun\\s+launchSafely\\s*\\(").containsMatchIn(dispatcherSource) &&
                Regex("private\\s+fun\\s+logFailure\\s*\\(").containsMatchIn(dispatcherSource),
        )
        listOf(
            "cancelHomeworkReminderSync" to "coordinator.cancelHomeworkReminder(",
            "syncHomeworkReminder" to "coordinator.syncHomeworkReminder(",
            "syncStudentReminders" to "coordinator.syncStudentReminders(",
            "syncHomeworkSavedReminder" to "syncHomeworkReminder(savedHomeworkId)",
        ).forEach { (method, landingCall) ->
            val body = methodBody(dispatcherSource, method)
            assertNotNull("KDoc 列出的入口 $method 未在接线层实现", body)
            assertTrue(
                "入口 $method 应经既有协调器入口 $landingCall 落地（不新造机制）",
                body!!.contains(landingCall),
            )
        }
    }

    @Test
    fun `计时入口仍由 timer 侧直接纠正而接线层只作兜底复用`() {
        val timerBlocks = splitComposableBlocks(navHostSource).filter { block ->
            listOf(
                TimerDestination.EXECUTION,
                TimerDestination.REST,
                TimerDestination.NEXT_ITEM,
                TimerDestination.COMPLETION,
            ).any { template ->
                resolveRouteConstant(routeExpressionOf(block) ?: "") == template
            }
        }

        assertEquals("timer 四条路由注册块应可完整定位", 4, timerBlocks.size)
        assertFalse(
            "宿主不得为计时入口额外挂提醒同步（既有纠正由 timer 页面自身发起，接线层只复用同一协调器入口兜底）",
            navHostSource.contains("syncStudentReminders"),
        )
        assertEquals(
            "接线层应收敛为第四个入口「计时入口 = 复用协调器既有纠正」这一条兜底方法",
            1,
            Regex("fun\\s+syncStudentReminders\\s*\\(").findAll(dispatcherSource).count(),
        )
        assertTrue(
            "兜底入口应走同一协调器方法（不新造机制）",
            methodBody(dispatcherSource, "syncStudentReminders")!!
                .contains("coordinator.syncStudentReminders(studentId)"),
        )
        assertTrue(
            "计时入口的既有纠正由 timer 页面直接调用协调器（生产侧锚点）",
            readSource("src/main/java/com/assignmate/app/timer/ui/TimerNextItemViewModel.kt")
                .contains("reminderCoordinator.syncStudentReminders(studentId)"),
        )
    }

    // ---- D. 回归：路由注册与提醒同步接线 ----

    @Test
    fun `20条以上路由注册无重复且均以常量表达式注册`() {
        val blocks = splitComposableBlocks(navHostSource)
        val templates = blocks.mapNotNull { block ->
            resolveRouteConstant(routeExpressionOf(block) ?: "")
        }

        assertEquals(
            "存在未解析的 route 表达式（新增路由需同步本白名单与常量解析表）",
            blocks.size,
            templates.size,
        )
        assertTrue("路由总数应保持在 20 条以上（覆盖 auth/homework/timer/stats/settings）", templates.size >= 20)
        assertEquals(
            "注册的 route 不应出现重复（重复注册会产生不可达页面）",
            templates.size,
            templates.distinct().size,
        )
        assertEquals(
            "不应以字符串字面量注册 route",
            0,
            Regex("route\\s*=\\s*\"").findAll(navHostSource).count(),
        )
        templates.forEach { template ->
            assertEquals(
                "路由 $template 应能唯一定位到注册块",
                1,
                blocks.count { resolveRouteConstant(routeExpressionOf(it) ?: "") == template },
            )
        }
    }

    @Test
    fun `settings 三路由与统计详情路由的注册契约保持不变`() {
        val expectations = listOf(
            Triple("SettingsDestination.HOME", SettingsDestination.HOME, SettingsDestination.HOME),
            Triple(
                "SettingsDestination.OCR_CONFIG",
                SettingsDestination.OCR_CONFIG,
                SettingsDestination.OCR_CONFIG,
            ),
            Triple("SettingsDestination.THEME", SettingsDestination.THEME, SettingsDestination.THEME),
        )

        expectations.forEach { (expression, constantValue, template) ->
            val block = blockForRoute(template)
            assertNotNull("未找到 settings 路由 $template 的注册块", block)
            assertEquals(
                "settings 路由 $template 的常量值应等于其注册模板",
                constantValue,
                template,
            )
            assertEquals(
                "settings 路由应以 $expression 常量注册（而非字面量）",
                template,
                resolveRouteConstant(expression),
            )
            assertTrue(
                "settings 三个二级页均为普通压栈、不带 popUpTo（返回即回来源页，不产生回退环）",
                !block!!.contains("popUpTo"),
            )
        }

        val detailBlock = blockForRoute(AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY)
        assertNotNull("未找到单项详情路由注册块", detailBlock)
        assertTrue(
            "单项详情注册模板应保留 stats 既有路径为前缀",
            AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY.startsWith(StatsDestination.ITEM_DETAIL),
        )
        assertTrue(
            "单项详情应保留可选 epochDay 查询参数（历史日详情可透传日期）",
            detailBlock!!.contains("navArgument(StatsDestination.ARG_EPOCH_DAY)") &&
                detailBlock.contains("defaultValue = StatsDestination.ARG_EPOCH_DAY_TODAY.toString()"),
        )
    }

    @Test
    fun `计时与统计链路各跳仍带 popUpTo 不产生回退环`() {
        val guards = listOf(
            "toTimerRest" to "popUpTo(TimerDestination.EXECUTION) { inclusive = true }",
            "toTimerNextItem" to "popUpTo(TimerDestination.REST) { inclusive = true }",
            "toTimerCompletion" to "popUpTo(TimerDestination.NEXT_ITEM) { inclusive = true }",
            "toStatsHistoryRange" to "popUpTo(StatsDestination.HISTORY) { inclusive = true }",
            "toStatsDaySummary" to "popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }",
        )

        guards.forEach { (helper, popUpTo) ->
            val body = helperBlock(navHostSource, helper)
            assertNotNull("未找到导航扩展函数 $helper", body)
            assertTrue("$helper 应清理刚离开的页面（$popUpTo）以避免回退环", body!!.contains(popUpTo))
        }
        assertTrue(
            "历史入口进盘点页应先清理历史页之上的旧盘点实例",
            helperBlock(navHostSource, "toStatsDaySummary")!!.contains("removeDaySummaryInstances()"),
        )
    }

    @Test
    fun `提醒同步三条页面接线与互斥分支保持不变`() {
        val listBlock = blockForRoute(HomeworkDestination.LIST)
        val entryBlock = blockForRoute(HomeworkDestination.ENTRY)
        val templateBlock = blockForRoute(HomeworkDestination.TEMPLATE)
        val timeSetBlock = blockForRoute(HomeworkDestination.TIME_SET)
        assertNotNull("未找到作业清单注册块", listBlock)
        assertNotNull("未找到录入页注册块", entryBlock)
        assertNotNull("未找到模板页注册块", templateBlock)
        assertNotNull("未找到时间设定页注册块", timeSetBlock)

        assertTrue(
            "删除成功应取消该作业提醒",
            listBlock!!.contains("onHomeworkRemoved = { homeworkId -> reminderSync.cancelHomeworkReminderSync(homeworkId) }"),
        )
        assertTrue(
            "重排时间保存成功后应按新时刻同步提醒",
            timeSetBlock!!.contains("onHomeworkScheduleSaved = { homeworkId -> reminderSync.syncHomeworkReminder(homeworkId) }"),
        )
        assertTrue(
            "录入页保存应按事件回抛的作业 id 精确同步",
            entryBlock!!.contains("reminderSync.syncHomeworkSavedReminder(") &&
                entryBlock.contains("savedHomeworkId = savedHomeworkId"),
        )
        assertTrue(
            "模板页保存应优先用事件回抛 id、路由 homeworkId 仅作非正数兜底",
            templateBlock!!
                .contains("savedHomeworkId = savedHomeworkId.takeIf { it > 0L } ?: entry.homeworkIdArg()"),
        )

        val savedBody = methodBody(dispatcherSource, "syncHomeworkSavedReminder")
        assertNotNull("未找到 syncHomeworkSavedReminder 方法体", savedBody)
        assertEquals(
            "三条分支互斥（按 id / 按学生兜底 / 留痕 no-op），方法内只应有一次 id 判正",
            1,
            Regex("savedHomeworkId\\s*>\\s*0L").findAll(savedBody!!).count(),
        )
        assertTrue(
            "兜底分支条件应是学生 id 为正数（与按 id 分支各自提前 return）",
            savedBody.contains("if (studentId > 0L) {") && savedBody.contains("return"),
        )
        assertEquals(
            "按 id 同步入口在方法内只出现一次（不存在重复调度）",
            1,
            Regex("syncHomeworkReminder\\(").findAll(savedBody).count(),
        )
        assertEquals(
            "学生级纠正入口在方法内只出现一次（不存在重复调度）",
            1,
            Regex("syncStudentReminders\\(").findAll(savedBody).count(),
        )
    }

    // ---- 夹具与源码解析工具 ----

    private class RecordingLogSink : ReminderSyncLogSink {

        val entries = mutableListOf<LogEntry>()

        override fun debug(tag: String, message: String, cause: Throwable?) {
            entries += LogEntry(tag, message, cause)
        }
    }

    private data class LogEntry(val tag: String, val message: String, val cause: Throwable?)

    private class Fixture(
        val repository: FakeHomeworkRepository,
        val scheduler: FakeAlarmScheduler,
        val dispatcher: ReminderSyncDispatcher,
    ) {
        fun put(item: HomeworkItem) {
            repository.put(item)
        }
    }

    /** 真实协调器 + 替身闹钟 + 内存仓库 + 可注入作用域 + 可断言日志出口（链路与生产一致） */
    private fun TestScope.fixture(logSink: ReminderSyncLogSink = RecordingLogSink()): Fixture {
        val repository = FakeHomeworkRepository()
        val scheduler = FakeAlarmScheduler()
        val coordinator = HomeworkReminderCoordinator(
            homeworkRepository = repository,
            alarmScheduler = scheduler,
            clock = MutableClock(BASE),
            zoneId = TimerTestEnv.ZONE,
            scheduleStore = null,
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            repository = repository,
            scheduler = scheduler,
            dispatcher = ReminderSyncDispatcher(coordinator, scope, logSink),
        )
    }

    /** 定位仓库根文件：Gradle 单测工作目录为 app/，同时兼容从仓库根运行 */
    private fun rootFile(relative: String): File {
        val candidates = listOf(File("../$relative"), File(relative), File("app/../$relative"))
        return candidates.firstOrNull { it.isFile } ?: candidates.first()
    }

    /** 取某条目上方紧邻的注释行（用于核验忽略规则的中文说明） */
    private fun commentAbove(text: String): String? {
        val lines = text.lines()
        val index = lines.indexOfFirst { it.trim() == LIZHU_ENV_ENTRY }
        if (index <= 0) return null
        return lines.subList(0, index).lastOrNull { it.isNotBlank() }
    }

    /**
     * 简易 gitignore 匹配口径：忽略 [path] 本身或其任意父目录（父目录被忽略则内部条目一并被忽略）。
     * 仅覆盖非否定（`!`）的普通规则，与本仓库 .gitignore 的实际写法一致。
     */
    private fun isIgnored(path: String, rules: List<String>): Boolean {
        val segments = path.trim('/').split('/')
        return (1..segments.size).any { depth ->
            val prefix = segments.take(depth).joinToString("/")
            rules.any { rule ->
                val trimmed = rule.trim()
                trimmed.isNotEmpty() &&
                    !trimmed.startsWith("#") &&
                    !trimmed.startsWith("!") &&
                    trimmed.trimEnd('/') == prefix
            }
        }
    }

    /** 读取 NavHost（Gradle 单测工作目录为 app/，同时兼容从仓库根运行） */
    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative，尝试路径：${candidates.map { it.absolutePath }}", file)
        return file!!.readText()
    }

    /** 取某公开方法的完整方法体（按花括号配平切片） */
    private fun methodBody(source: String, name: String): String? {
        val match = Regex("fun\\s+" + name + "\\s*\\(").find(source) ?: return null
        val open = source.indexOf("{", match.range.first)
        if (open < 0) return null
        val end = balancedEnd(source, open, '{', '}') ?: return null
        return source.substring(open, end + 1)
    }

    /** 取某导航扩展函数的定义块（签名 + 花括号体） */
    private fun helperBlock(source: String, name: String): String? {
        val match = Regex("fun\\s+NavHostController\\." + name + "\\s*\\(").find(source) ?: return null
        val open = source.indexOf("{", match.range.first)
        if (open < 0) return null
        val end = balancedEnd(source, open, '{', '}') ?: return null
        return source.substring(match.range.first, end + 1)
    }

    /** 切出每个 `composable(...) { ... }` 注册块（括号/花括号配平，跳过字符串字面量与注释） */
    private fun splitComposableBlocks(source: String): List<String> {
        val blocks = mutableListOf<String>()
        var index = source.indexOf("composable(")
        while (index >= 0) {
            val headerEnd = balancedEnd(source, index + "composable".length - 1, '(', ')') ?: break
            val bodyStart = source.indexOf("{", headerEnd)
            if (bodyStart < 0) break
            val bodyEnd = balancedEnd(source, bodyStart, '{', '}') ?: break
            blocks += source.substring(index, bodyEnd + 1)
            index = source.indexOf("composable(", bodyEnd)
        }
        return blocks
    }

    /** 自 [start] 处的 [open] 起配平，返回配对 [close] 的下标（跳过字符串字面量与注释） */
    private fun balancedEnd(source: String, start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = start
        var inString = false
        var inLineComment = false
        var inBlockComment = false
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
                ch == open -> depth++
                ch == close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun routeExpressionOf(block: String): String? =
        Regex("route\\s*=\\s*([A-Za-z_][\\w.]*)\\s*[,)]").find(block)?.groupValues?.get(1)

    private fun blockForRoute(template: String): String? =
        splitComposableBlocks(navHostSource).firstOrNull { block ->
            resolveRouteConstant(routeExpressionOf(block) ?: "") == template
        }

    /** 把 `Module.ROUTE` 形式的表达式解析为实际路由字符串（新增路由需同步本表） */
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
        "AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY" -> AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY
        "StatsDestination.HISTORY" -> StatsDestination.HISTORY
        "SettingsDestination.HOME" -> SettingsDestination.HOME
        "SettingsDestination.OCR_CONFIG" -> SettingsDestination.OCR_CONFIG
        "SettingsDestination.THEME" -> SettingsDestination.THEME
        else -> null
    }

    private companion object {

        const val BASE = TimerTestEnv.FIXED_MILLIS

        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE

        const val STUDENT_ID = TimerTestEnv.STUDENT_ID

        /** 忽略条目口径（目录形式，与 .gitignore 中写法一致） */
        const val LIZHU_ENV_ENTRY = ".lizhu_env/"
    }
}