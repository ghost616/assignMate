package com.assignmate.app.navigation

import com.assignmate.app.stats.ui.StatsDestination
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stats 单项详情路由「补可选 epochDay 并透传」的接线契约核查（framework）。
 *
 * 缺口背景：stats 侧 [StatsDestination.ITEM_DETAIL] 只声明纯路径 `stats/item/{studentId}/{homeworkId}`，
 * 而详情页的查看日期由 `ItemDetailRoute` 的 `epochDay` 入参承载，故「历史某日盘点 → 点开某项」
 * 原先一定落到「今天」（缺省哨兵）。本计划由 framework 在注册模板上补**可选**查询参数并透传，
 * stats 生产代码不动（日期参数的接线本就属 framework 计划范围，见 StatsDestination / ItemDetailRoute 注释）。
 *
 * 覆盖维度：
 * 1. 模板与拼装同源：宿主注册模板 = stats 既有路径模板 + 可选 `epochDay`（参数名沿用 stats 常量）；
 * 2. 带 / 不带 epochDay 两种形态：拼装 → 解析可往返，缺省（既有深链接不带查询串）回落「今天」哨兵；
 * 3. 注册块：占位符与 navArgument 一一对应（仅 `epochDay` 带哨兵默认值 = 可选参数）；
 * 4. 透传：盘点页把自身展示的日期交给 `toStatsItemDetail`，详情页再经 stats 解析函数取值；
 * 5. 回退语义：详情页仍为二级页（popBackStack、无 popUpTo），不产生回退环。
 *
 * 环境说明：本环境无 Android 模拟器/真机，导航运行时读取 route 参数无法用设备验证，
 * 故沿用工程既有约定以「源码级等价证据 + 纯函数往返」覆盖（与 [AssignMateNavHostContractTest] 同一口径）。
 */
class StatsItemDetailEpochDayRouteTest {

    private val navHostSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt",
    )

    private val composableBlocks: List<String> = splitComposableBlocks(navHostSource)

    /** 宿主为单项详情页注册的路由表达式（源码文本，用于定位注册块） */
    private val itemDetailRouteExpression = "AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY"

    // ---- 1. 注册模板：stats 既有路径 + 宿主补的可选 epochDay ----

    @Test
    fun `详情路由注册模板在 stats 既有路径上补可选 epochDay`() {
        assertEquals("stats/item/{studentId}/{homeworkId}", StatsDestination.ITEM_DETAIL)
        assertEquals(
            "宿主注册模板应为 stats 既有路径模板 + 可选 epochDay 查询参数",
            "stats/item/{studentId}/{homeworkId}?epochDay={epochDay}",
            AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY,
        )
        assertEquals(
            "注册模板的路径部分必须与 stats 既有模板完全一致（不脱离 stats 契约）",
            StatsDestination.ITEM_DETAIL,
            AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY.substringBefore('?'),
        )
        assertEquals(
            "查询参数名沿用 stats 既有常量（framework 不另造参数协议）",
            "epochDay",
            StatsDestination.ARG_EPOCH_DAY,
        )
        assertTrue(
            "NavHost 应以该表达式注册详情路由",
            composableBlocks.any {
                Regex("route\\s*=\\s*" + Regex.escape(itemDetailRouteExpression) + "\\s*[,)]").containsMatchIn(it)
            },
        )
    }

    @Test
    fun `注册块的占位符与 navArgument 一一对应且仅日期为可选参数`() {
        val block = itemDetailBlock()
        val template = AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY
        val placeholders = Regex("\\{(\\w+)}").findAll(template).map { it.groupValues[1] }.toList()
        val declared = Regex("navArgument\\(([^)]*)\\)")
            .findAll(block)
            .map { it.groupValues[1].trim() }
            .toList()

        assertEquals(
            "路径占位符 + 可选日期占位符共 3 个",
            listOf("studentId", "homeworkId", "epochDay"),
            placeholders,
        )
        assertEquals("navArgument 数量应与占位符数量一致（声明=$declared）", placeholders.size, declared.size)
        placeholders.forEach { name ->
            val expected = "StatsDestination.ARG_" + name.toConstantCase()
            assertTrue("占位符 {$name} 应以 $expected 声明，实际=$declared", declared.contains(expected))
        }
        assertEquals(
            "只有 epochDay 声明默认值（路径参数仍为必填），且缺省值为「今天」哨兵 = 可选参数",
            1,
            Regex("defaultValue = StatsDestination\\.ARG_EPOCH_DAY_TODAY\\.toString\\(\\)").findAll(block).count(),
        )
        assertTrue(
            "注册块应显式声明 epochDay 的 navArgument",
            block.contains("navArgument(StatsDestination.ARG_EPOCH_DAY)"),
        )
    }

    // ---- 2. 带 / 不带 epochDay：拼装与解析同源可往返 ----

    @Test
    fun `给定 epochDay 时拼装与解析往返一致`() {
        val route = AppDestination.statsItemDetailRoute(studentId = 2L, homeworkId = 7L, epochDay = 19_674L)
        assertEquals("stats/item/2/7?epochDay=19674", route)

        val raw = route.substringAfter(StatsDestination.ARG_EPOCH_DAY + "=")
        assertEquals("给定日期应被解析回同一值（透传不丢）", 19_674L, StatsDestination.epochDayOf(raw))
        assertEquals("stats/item/2/7", StatsDestination.itemDetailRoute(2L, 7L))
    }

    @Test
    fun `缺省 epochDay 与既有深链接不带查询串的语义一致（回落今天哨兵）`() {
        assertEquals(
            "缺省拼装应显式带上「今天」哨兵，与注册模板的 defaultValue 同值",
            "stats/item/2/7?epochDay=-1",
            AppDestination.statsItemDetailRoute(studentId = 2L, homeworkId = 7L),
        )
        assertEquals(
            "哨兵值解析仍为「今天」语义",
            StatsDestination.ARG_EPOCH_DAY_TODAY,
            StatsDestination.epochDayOf(StatsDestination.ARG_EPOCH_DAY_TODAY.toString()),
        )
        assertEquals(
            "既有深链接（不带查询串 → 参数缺失）解析为「今天」哨兵，行为与改动前一致",
            StatsDestination.ARG_EPOCH_DAY_TODAY,
            StatsDestination.epochDayOf(null),
        )
        assertEquals(
            "非法日期形态同样回落「今天」哨兵，不抛异常",
            StatsDestination.ARG_EPOCH_DAY_TODAY,
            StatsDestination.epochDayOf("not-a-day"),
        )
        assertTrue(
            "既有深链接形态仍可被新模板命中（路径部分不变，查询参数可省）",
            AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY.startsWith("stats/item/{studentId}/{homeworkId}"),
        )
    }

    // ---- 3. 透传链路：盘点页 → 详情页 ----

    @Test
    fun `盘点页把自身展示的日期透传给详情页导航`() {
        val dayBlock = blockOfRouteExpression("StatsDestination.DAY_SUMMARY")
        assertNotNull("未找到当日盘点路由注册块", dayBlock)

        assertTrue(
            "盘点页自身按 stats 既有解析函数取日期（透传源与页面入参同源）",
            dayBlock!!.contains("epochDay = entry.statsEpochDayArg(StatsDestination.ARG_EPOCH_DAY)"),
        )
        assertTrue(
            "「查看这一项详情」应携带学生、作业与盘点页正在展示的日期",
            dayBlock.contains("navController.toStatsItemDetail("),
        )

        val helper = helperBlock("toStatsItemDetail")
        assertTrue(
            "toStatsItemDetail 的日期缺省应为「今天」哨兵（清单/当日盘点入口不传日期即看今天）",
            helper.contains("epochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY"),
        )
        assertTrue(
            "toStatsItemDetail 应走宿主拼装（stats 既有路径 + 可选 epochDay），而非丢掉日期",
            helper.contains("navigate(AppDestination.statsItemDetailRoute(studentId, homeworkId, epochDay))"),
        )
    }

    @Test
    fun `详情页经 stats 既有解析函数取日期并保持二级页回退语义`() {
        val block = itemDetailBlock()

        assertTrue(
            "详情页应把解析出的日期传给页面（解析口径 = statsEpochDayArg → StatsDestination.epochDayOf）",
            block.contains("epochDay = entry.statsEpochDayArg(StatsDestination.ARG_EPOCH_DAY)"),
        )
        assertTrue(
            "详情页学生/作业仍走 stats 既有解析函数",
            block.contains("statsStudentIdArg()") && block.contains("statsHomeworkIdArg()"),
        )
        assertTrue("返回键应 popBackStack 回盘点页", block.contains("onBack = { navController.popBackStack() }"))
        assertFalse("二级页不应携带 popUpTo（不产生回退环）", block.contains("popUpTo"))
        assertTrue(
            "解析助手统一走 StatsDestination.epochDayOf（framework 不另造日期解析）",
            navHostSource.contains("StatsDestination.epochDayOf(arguments?.getString(name))"),
        )
    }

    // ---- 源码解析工具（与既有契约测试同一约定：Gradle 单测工作目录为 app/，兼容仓库根运行） ----

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative), File("../app/" + relative))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative", file)
        return file!!.readText()
    }

    /** 单项详情页注册块（按宿主注册表达式定位） */
    private fun itemDetailBlock(): String {
        val block = blockOfRouteExpression(itemDetailRouteExpression)
        assertNotNull("未找到单项详情路由注册块（$itemDetailRouteExpression）", block)
        return block!!
    }

    /** 按 `route = <表达式>` 定位注册块 */
    private fun blockOfRouteExpression(expression: String): String? {
        val pattern = Regex("route\\s*=\\s*" + Regex.escape(expression) + "\\s*[,)]")
        return composableBlocks.firstOrNull { pattern.containsMatchIn(it) }
    }

    /** 取 `NavHostController.<name>` 扩展函数的完整定义块（含签名与体，按花括号配平） */
    private fun helperBlock(name: String): String {
        val match = Regex("fun\\s+NavHostController\\." + name + "\\s*\\(").find(navHostSource)
        assertNotNull("未找到私有导航扩展函数 $name 定义", match)
        val from = match!!.range.first
        val open = navHostSource.indexOf('{', from)
        assertTrue("导航扩展函数 $name 应有花括号体", open > 0)
        val end = balancedEnd(navHostSource, open, '{', '}')
        assertNotNull("导航扩展函数 $name 的函数体未配平", end)
        return navHostSource.substring(from, end!! + 1)
    }

    /** 切出每个 `composable(...) { ... }` 注册块（括号/花括号配平，跳过字符串字面量与注释） */
    private fun splitComposableBlocks(source: String): List<String> {
        val blocks = mutableListOf<String>()
        var index = source.indexOf("composable(")
        while (index >= 0) {
            val headerEnd = balancedEnd(source, index + "composable".length - 1, '(', ')') ?: break
            val bodyStart = source.indexOf('{', headerEnd)
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

    private fun String.toConstantCase(): String =
        replace(Regex("([a-z])([A-Z])")) { it.groupValues[1] + "_" + it.groupValues[2] }.uppercase()
}
