package com.assignmate.app.navigation

import com.assignmate.app.auth.ui.AuthDestination
import com.assignmate.app.stats.ui.StatsDestination
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今日盘点」两处入口接线契约（framework：导航接线层）。
 */
class DaySummaryEntryWiringContractTest {

    private val navHostSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt",
    )

    private val composableBlocks: List<String> = splitComposableBlocks(navHostSource)

    // ---- 1. 正向：家长中心 / 学生首页两个入口均注入 onOpenDaySummary 并复用 toStatsDaySummary ----

    @Test
    fun `家长中心与学生首页的今日盘点入口已分别注入 onOpenDaySummary`() {
        val parentBlock = blockForRoute(AuthDestination.PARENT_HOME)
        assertNotNull("未找到家长主界面路由注册块", parentBlock)
        assertTrue(
            "家长中心 onOpenDaySummary 应携带该卡片 studentId 进 stats 当日盘点页",
            parentBlock!!.contains("onOpenDaySummary = { studentId -> navController.toStatsDaySummary(studentId) }"),
        )

        val studentBlock = blockForRoute(AuthDestination.STUDENT_HOME)
        assertNotNull("未找到学生端首页路由注册块", studentBlock)
        assertTrue(
            "学生首页 onOpenDaySummary 无参回调应传未指定学生哨兵（盘点页按会话收敛为本人，防越权）",
            studentBlock!!.contains(
                "onOpenDaySummary = { navController.toStatsDaySummary(AppDestination.UNSPECIFIED_STUDENT_ID) }",
            ),
        )
    }

    @Test
    fun `家长入口传该卡片学生 id 学生入口传未指定学生哨兵`() {
        val parentBlock = blockForRoute(AuthDestination.PARENT_HOME)!!
        assertTrue(
            "家长入口必须显式携带该学生 id，不得传未指定哨兵",
            parentBlock.contains("toStatsDaySummary(studentId)") &&
                !parentBlock.contains("toStatsDaySummary(AppDestination.UNSPECIFIED_STUDENT_ID)"),
        )

        val studentBlock = blockForRoute(AuthDestination.STUDENT_HOME)!!
        assertTrue(
            "学生入口必须传未指定哨兵，不得把路由参数当成学生 id",
            studentBlock.contains("toStatsDaySummary(AppDestination.UNSPECIFIED_STUDENT_ID)") &&
                !studentBlock.contains("toStatsDaySummary(studentId)"),
        )
    }

    @Test
    fun `两处入口与既有清单入口共用同一个 toStatsDaySummary`() {
        listOf(
            "家长中心入口" to "navController.toStatsDaySummary(studentId)",
            "学生首页入口" to "navController.toStatsDaySummary(AppDestination.UNSPECIFIED_STUDENT_ID)",
        ).forEach { (label, call) ->
            assertEquals("入口调用次数应为 1：$label", 1, Regex(Regex.escape(call)).findAll(navHostSource).count())
        }

        assertTrue(
            "清单入口接线保持不变（三处入口共用同一导航扩展）",
            navHostSource.contains("onOpenStats = { statsStudentId -> navController.toStatsDaySummary(statsStudentId) }"),
        )
    }

    @Test
    fun `两处入口落到同一条盘点路由其日期查询参数缺省为今天哨兵`() {
        val helper = blockForHelper("toStatsDaySummary")
        assertNotNull("未找到 toStatsDaySummary 定义", helper)
        assertTrue(
            "toStatsDaySummary 应进入 StatsDestination.daySummaryRoute 并携带日期",
            helper!!.contains("navigate(StatsDestination.daySummaryRoute(studentId, epochDay))"),
        )
        assertTrue(
            "应只保留一个盘点导航扩展与一处路由拼装（本次只接线不新增路由）",
            navHostSource.contains("fun NavHostController.toStatsDaySummary(") &&
                Regex("fun\\s+NavHostController\\.toStatsDaySummary\\s*\\(").findAll(navHostSource).count() == 1 &&
                Regex("StatsDestination\\.daySummaryRoute\\(").findAll(navHostSource).count() == 1,
        )

        val daySummary = blockForRoute(StatsDestination.DAY_SUMMARY)!!
        assertTrue(
            "日期查询参数应声明今天哨兵默认值（两处入口约定「今天」）",
            daySummary.contains("defaultValue = StatsDestination.ARG_EPOCH_DAY_TODAY.toString()"),
        )
    }

    @Test
    fun `两处入口不传日期也不标记历史来源沿用既有返回栈语义`() {
        listOf(
            "家长中心入口" to "navController.toStatsDaySummary(studentId)",
            "学生首页入口" to "navController.toStatsDaySummary(AppDestination.UNSPECIFIED_STUDENT_ID)",
        ).forEach { (label, call) ->
            val block = blockOf(call)
            assertNotNull("未找到调用所在注册块：$label", block)
            assertFalse("非历史入口不得显式传 fromHistory（应沿用默认 false 的替换语义）：$label", block!!.contains("fromHistory"))
            assertFalse("非历史入口不得自带盘点日期（应沿用今天哨兵）：$label", block.contains("epochDay ="))
        }

        val helper = blockForHelper("toStatsDaySummary")!!
        assertTrue(
            "toStatsDaySummary 的日期缺省今天哨兵、来源缺省非历史",
            helper.contains("epochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY") &&
                helper.contains("fromHistory: Boolean = false"),
        )
        assertTrue(
            "toStatsDaySummary 沿用既有 popUpTo(DAY_SUMMARY, inclusive) 语义（不新造返回栈规则）",
            helper.contains("popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }"),
        )
    }

    // ---- 2. 常量与参数口径：AppDestination 与 StatsDestination 一致 ----

    @Test
    fun `未指定学生哨兵与 stats 常量同源且盘点路由可被 stats 解析函数还原`() {
        assertEquals(
            "宿主未指定学生哨兵应与 stats 学生 id 缺省值同源",
            StatsDestination.ARG_STUDENT_ID_NONE,
            AppDestination.UNSPECIFIED_STUDENT_ID,
        )
        assertEquals("未指定学生哨兵应为 0", 0L, AppDestination.UNSPECIFIED_STUDENT_ID)
        assertEquals("盘点路由模板应与 stats 契约一致", "stats/day/{studentId}?epochDay={epochDay}", StatsDestination.DAY_SUMMARY)
        assertEquals("今天哨兵应为 -1", -1L, StatsDestination.ARG_EPOCH_DAY_TODAY)

        val route = StatsDestination.daySummaryRoute(AppDestination.UNSPECIFIED_STUDENT_ID)
        assertTrue("盘点路由应以 stats/day/0 起头：$route", route.startsWith("stats/day/0"))
        assertTrue(
            "未指定学生哨兵应能被 stats 解析函数还原：$route",
            StatsDestination.studentIdOf(route.substringAfter("stats/day/").substringBefore("?")) ==
                AppDestination.UNSPECIFIED_STUDENT_ID,
        )
        assertEquals(
            "不带日期查询串时应解析为今天哨兵：$route",
            StatsDestination.ARG_EPOCH_DAY_TODAY,
            StatsDestination.epochDayOf(route.substringAfter("epochDay=", "")),
        )
    }

    @Test
    fun `既有单项详情日期透传与历史入口接线不被本次改动破坏`() {
        assertTrue(
            "单项详情路由（宿主补的可选 epochDay 查询参数）应保持注册与透传",
            navHostSource.contains("route = AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY") &&
                navHostSource.contains("navigate(AppDestination.statsItemDetailRoute(studentId, homeworkId, epochDay))"),
        )
        assertTrue("历史入口 fromHistory = true 的接线应保持", navHostSource.contains("fromHistory = true"))
    }

    // ---- 3. 依赖方向：framework 只接线不 import 业务模块 UI 实现 ----

    @Test
    fun `auth 两页只暴露无 stats 依赖的导航意图回调`() {
        listOf(
            "src/main/java/com/assignmate/app/auth/ui/ParentHomeScreen.kt" to
                "onOpenDaySummary: (Long) -> Unit = {}",
            "src/main/java/com/assignmate/app/auth/ui/StudentHomeScreen.kt" to
                "onOpenDaySummary: () -> Unit = {}",
        ).forEach { (path, signature) ->
            val source = readSource(path)
            assertTrue("导航意图回调签名应与 framework 注入点一致：$path", source.contains(signature))
            assertFalse("不应 import 业务模块 UI 实现：$path", source.contains("import com.assignmate.app.stats"))
        }

        // 注意：本断言只针对 auth 两页（业务模块之间不互相 import UI 实现）。
        // framework 宿主层（AssignMateNavHost.kt）是各模块路由的**注册方**，按契约必须 import
        // stats 的 DaySummaryRoute / StatsDestination 才能注册盘点与详情路由，
        // 故不得对宿主层做「不 import 业务模块」断言（原实现对宿主层的同名断言与本契约自相矛盾）。
    }

    // ---- 4. 源码解析工具（与既有 ContractTest 同一约定：Gradle 单测工作目录为 app/，兼容仓库根运行；
    // 本环境无 Android 设备/模拟器，故以源码级等价证据覆盖接线契约） ----

    /** 读取生产源码（Gradle 单测工作目录为 app/，同时兼容从仓库根运行） */
    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative), File("../app/" + relative))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件：$relative", file)
        return file!!.readText()
    }

    /** 取某路由模板对应的 composable 注册块 */
    private fun blockForRoute(template: String): String? =
        composableBlocks.firstOrNull { block ->
            val expression = Regex("route\\s*=\\s*([A-Za-z_][\\w.]*)\\s*[,)]").find(block)
                ?.groupValues
                ?.get(1)
            (routeConstantValue(expression) ?: expression) == template
        }

    /** 取包含某导航调用文本的 composable 注册块（调用文本在全文件中唯一，故可直接定位） */
    private fun blockOf(call: String): String? =
        composableBlocks.firstOrNull { it.contains(call) }

    /** 取某个私有导航扩展函数的完整定义块（按花括号配平切片） */
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

    /** 把 Module.ROUTE 形式的注册 route 表达式解析为实际路由字符串 */
    private fun routeConstantValue(expression: String?): String? = when (expression) {
        "AuthDestination.ROLE_SELECT" -> AuthDestination.ROLE_SELECT
        "AuthDestination.PARENT_LOGIN" -> AuthDestination.PARENT_LOGIN
        "AuthDestination.PARENT_REGISTER" -> AuthDestination.PARENT_REGISTER
        "AuthDestination.STUDENT_ENTER" -> AuthDestination.STUDENT_ENTER
        "AuthDestination.PARENT_HOME" -> AuthDestination.PARENT_HOME
        "AuthDestination.STUDENT_HOME" -> AuthDestination.STUDENT_HOME
        "StatsDestination.DAY_SUMMARY" -> StatsDestination.DAY_SUMMARY
        else -> null
    }

    /** 以括号配平切出每个 composable(...) { ... } 注册块（跳过字符串字面量与注释） */
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
}
