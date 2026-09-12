package com.assignmate.app.navigation

import com.assignmate.app.stats.ui.StatsDestination
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stats 导航接线补充核查（离朱独立复测新增，与既有 AssignMateNavHostContractTest 互补）。
 *
 * 既有契约测试覆盖了「路由被注册 / 解析助手被调用 / 默认值条数」等正向断言，本类补齐其缺口：
 * 1. stats 三条路由的 navArgument 与占位符「双向一一对应」（含数量一致性）；
 * 2. 参数解析的超 Long 范围 / 极值 / 非法形态（不抛异常、回落哨兵）；
 * 3. 换日期 / 换范围一律「移除旧实例 + 压入新实例」（toStatsDaySummary / toStatsHistoryRange 均按路由模板 popUpTo），
 *    且两个盘点入口的返回栈语义区分：清单入口替换栈顶盘点实例（[清单, 盘点]）、
 *    历史入口只清理历史页之上的旧盘点实例并保留历史页（[清单, 历史, 新盘点]，返回键回到历史页）；
 * 4. 选择器纪元日与毫秒换算口径（向下取整而非截断）；
 * 5. 导航宿主未引入白名单外的依赖（对应「无新增第三方依赖」验证点）。
 */
class StatsNavigationContractExtraTest {

    private val navHostSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt",
    )

    private val composableBlocks: List<String> = splitComposableBlocks(navHostSource)

    /** stats 路由模板 -> 源码中注册时使用的常量表达式 */
    private val statsRoutes: Map<String, String> = linkedMapOf(
        StatsDestination.DAY_SUMMARY to "StatsDestination.DAY_SUMMARY",
        StatsDestination.ITEM_DETAIL to "StatsDestination.ITEM_DETAIL",
        StatsDestination.HISTORY to "StatsDestination.HISTORY",
    )

    // ---- 1. 路由模板 + navArgument 双向一一对应 ----

    @Test
    fun `stats 三条路由模板与接线说明一致`() {
        assertEquals("stats/day/{studentId}?epochDay={epochDay}", StatsDestination.DAY_SUMMARY)
        assertEquals("stats/item/{studentId}/{homeworkId}", StatsDestination.ITEM_DETAIL)
        assertEquals(
            "stats/history/{studentId}?fromEpochDay={fromEpochDay}&toEpochDay={toEpochDay}",
            StatsDestination.HISTORY,
        )
        assertEquals("studentId", StatsDestination.ARG_STUDENT_ID)
        assertEquals("homeworkId", StatsDestination.ARG_HOMEWORK_ID)
        assertEquals("epochDay", StatsDestination.ARG_EPOCH_DAY)
        assertEquals("fromEpochDay", StatsDestination.ARG_FROM_EPOCH_DAY)
        assertEquals("toEpochDay", StatsDestination.ARG_TO_EPOCH_DAY)
    }

    @Test
    fun `stats 各路由的 navArgument 与占位符双向一一对应`() {
        statsRoutes.forEach { (template, expression) ->
            val block = blockForStatsRoute(template, expression)
            val placeholders = Regex("\\{(\\w+)}").findAll(template).map { it.groupValues[1] }.toList()
            val declared = Regex("navArgument\\(([^)]*)\\)")
                .findAll(block)
                .map { it.groupValues[1].trim() }
                .toList()

            assertEquals(
                "路由 $template 的 navArgument 数量应与占位符数量一致（声明=$declared）",
                placeholders.size,
                declared.size,
            )

            placeholders.forEach { name ->
                val expected = "StatsDestination.ARG_" + name.toConstantCase()
                assertTrue(
                    "路由 $template 的占位符 {$name} 应以 $expected 声明，实际声明=$declared",
                    declared.contains(expected),
                )
            }

            declared.forEach { raw ->
                val token = raw.substringAfterLast('.')
                val name = if (token.startsWith("ARG_")) constantToParamName(token) else raw.trim('"')
                assertTrue(
                    "navArgument($raw) 在路由 $template 中无对应占位符",
                    placeholders.contains(name),
                )
            }
        }
    }

    @Test
    fun `历史路由起止日期查询参数为可选且默认值同为今天哨兵`() {
        val block = blockForStatsRoute(StatsDestination.HISTORY, "StatsDestination.HISTORY")
        val defaultCount = Regex(
            "defaultValue = StatsDestination\\.ARG_EPOCH_DAY_TODAY\\.toString\\(\\)",
        ).findAll(block).count()

        assertEquals("起止日期两个查询参数都应声明「今天」哨兵默认值", 2, defaultCount)
        assertEquals(-1L, StatsDestination.ARG_EPOCH_DAY_TODAY)
    }

    @Test
    fun `盘点路由的日期参数为可选且清单入口缺省为今天哨兵`() {
        val block = blockForStatsRoute(StatsDestination.DAY_SUMMARY, "StatsDestination.DAY_SUMMARY")
        val defaultCount = Regex(
            "defaultValue = StatsDestination\\.ARG_EPOCH_DAY_TODAY\\.toString\\(\\)",
        ).findAll(block).count()

        assertEquals("epochDay 应声明「今天」哨兵默认值（可选查询参数）", 1, defaultCount)
        assertTrue(
            "盘点页应把解析出的日期传给页面",
            block.contains("epochDay = entry.statsEpochDayArg(StatsDestination.ARG_EPOCH_DAY)"),
        )
    }

    // ---- 2. 导航扩展：换日期/换范围一律替换实例而非复用 ----

    @Test
    fun `换日期范围按历史路由模板移除旧实例`() {
        val helper = blockForHelper("toStatsHistoryRange")
        assertNotNull("未找到 toStatsHistoryRange 定义", helper)

        assertTrue(
            "应按历史路由模板 popUpTo(inclusive) 移除旧查询实例（避免换范围累积回退栈）",
            helper!!.contains("popUpTo(StatsDestination.HISTORY) { inclusive = true }"),
        )
        assertTrue(
            "应压入携带新范围的历史路由",
            helper.contains("navigate(StatsDestination.historyRoute(studentId, fromEpochDay, toEpochDay))"),
        )
    }

    @Test
    fun `盘点页按所选日期替换旧实例而非复用`() {
        val helper = blockForHelper("toStatsDaySummary")
        assertNotNull("未找到 toStatsDaySummary 定义", helper)
        assertTrue(
            "应声明 epochDay 缺省为「今天」哨兵（清单入口不传日期即盘点今天）",
            helper!!.contains("epochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY"),
        )
        assertTrue(
            "应按所选日期压入盘点路由",
            helper.contains("navigate(StatsDestination.daySummaryRoute(studentId, epochDay))"),
        )
        assertTrue(
            "清单入口应 popUpTo(DAY_SUMMARY, inclusive) 移除旧盘点实例（旧实例 arguments 固定，复用只会展示旧日期）",
            helper.contains("popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }"),
        )
        assertTrue(
            "不应再以 popBackStack 复用旧盘点实例",
            !helper.contains("popBackStack(StatsDestination.DAY_SUMMARY"),
        )
    }

    // ---- 2b. 两个盘点入口的返回栈语义（清单 [清单, 盘点] / 历史 [清单, 历史, 新盘点]） ----

    @Test
    fun `清单入口查看盘点栈顶实例被替换不产生实例堆积`() {
        val helper = blockForHelper("toStatsDaySummary")!!

        assertTrue(
            "应声明 fromHistory 入口开关（缺省 false = 清单入口）",
            helper.contains("fromHistory: Boolean = false"),
        )
        assertTrue(
            "仅清单入口（!fromHistory）才按 DAY_SUMMARY 模板 inclusive 弹栈替换",
            helper.contains("if (!fromHistory)") &&
                helper.contains("popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }"),
        )
        assertTrue(
            "历史入口不应在 navigate 的 popUpTo 中按 DAY_SUMMARY inclusive 弹栈",
            !helper.contains("popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = fromHistory }"),
        )
    }

    @Test
    fun `历史入口保留历史页只清理其上的旧盘点实例`() {
        val historyBlock = blockForStatsRoute(StatsDestination.HISTORY, "StatsDestination.HISTORY")
        assertTrue(
            "历史页应显式以 fromHistory = true 进入盘点页（区别于清单入口）",
            historyBlock.contains("fromHistory = true"),
        )

        val helper = blockForHelper("toStatsDaySummary")!!
        assertTrue(
            "历史入口应调用 removeDaySummaryInstances() 只清理历史页之上的旧盘点实例",
            helper.contains("if (fromHistory)") && helper.contains("removeDaySummaryInstances()"),
        )
        assertTrue(
            "清理调用应先于 navigate 压入新实例（先清后压）",
            helper.indexOf("removeDaySummaryInstances()") < helper.indexOf("navigate(StatsDestination."),
        )

        val cleaner = blockForHelper("removeDaySummaryInstances")
        assertNotNull("未找到 removeDaySummaryInstances 定义", cleaner)
        assertTrue(
            "清理自栈顶逐个弹出并遇历史页即停（保留历史页及其所选日期范围上下文）",
            cleaner!!.contains("if (isStatsHistory(top)) return") &&
                cleaner.contains("if (!isStatsDaySummary(top)) return") &&
                cleaner.contains("currentBackStackEntry") &&
                cleaner.contains("popBackStack(top.destination.id, inclusive = true)"),
        )
        assertTrue(
            "清理不得按 DAY_SUMMARY/HISTORY 模板 popUpTo（模板 inclusive 会连带移除历史页）",
            !cleaner.contains("popUpTo(") && !cleaner.contains("popBackStack(StatsDestination."),
        )
        assertTrue(
            "历史页判定按注册路由模板精确匹配",
            definitionOf("isStatsHistory").contains("entry.destination.route == StatsDestination.HISTORY"),
        )
        assertTrue(
            "盘点实例判定按注册路由模板精确匹配",
            definitionOf("isStatsDaySummary").contains("entry.destination.route == StatsDestination.DAY_SUMMARY"),
        )
    }

    @Test
    fun `历史页内部换范围仍保持先移除旧实例再压入新实例`() {
        val helper = blockForHelper("toStatsHistoryRange")
        assertNotNull("未找到 toStatsHistoryRange 定义", helper)
        assertTrue(
            "换范围应按历史路由模板 popUpTo inclusive 移除旧查询实例（历史页恒只有一个实例）",
            helper!!.contains("popUpTo(StatsDestination.HISTORY) { inclusive = true }"),
        )
        assertTrue(
            "换范围后应压入携带新范围的历史路由",
            helper.contains("navigate(StatsDestination.historyRoute(studentId, fromEpochDay, toEpochDay))"),
        )
        assertTrue(
            "换范围不涉及盘点实例清理（盘点堆叠清理只属于历史 → 盘点入口）",
            !helper.contains("removeDaySummaryInstances"),
        )
    }

    // ---- 3. 参数解析边界（含超 Long 范围） ----

    @Test
    fun `stats 参数解析对超 Long 范围输入不抛异常并回落哨兵`() {
        assertEquals(StatsDestination.ARG_STUDENT_ID_NONE, StatsDestination.studentIdOf("99999999999999999999"))
        assertEquals(StatsDestination.ARG_HOMEWORK_ID_NONE, StatsDestination.homeworkIdOf("99999999999999999999"))
        assertEquals(StatsDestination.ARG_EPOCH_DAY_TODAY, StatsDestination.epochDayOf("99999999999999999999"))
        assertEquals(StatsDestination.ARG_STUDENT_ID_NONE, StatsDestination.studentIdOf("-99999999999999999999"))
        assertEquals(StatsDestination.ARG_EPOCH_DAY_TODAY, StatsDestination.epochDayOf("-99999999999999999999"))
    }

    @Test
    fun `stats 参数解析对极值原样返回`() {
        assertEquals(Long.MAX_VALUE, StatsDestination.studentIdOf(Long.MAX_VALUE.toString()))
        assertEquals(Long.MIN_VALUE, StatsDestination.studentIdOf(Long.MIN_VALUE.toString()))
        assertEquals(Long.MAX_VALUE, StatsDestination.homeworkIdOf(Long.MAX_VALUE.toString()))
        assertEquals(Long.MIN_VALUE, StatsDestination.homeworkIdOf(Long.MIN_VALUE.toString()))
        assertEquals(Long.MAX_VALUE, StatsDestination.epochDayOf(Long.MAX_VALUE.toString()))
        assertEquals(Long.MIN_VALUE, StatsDestination.epochDayOf(Long.MIN_VALUE.toString()))
    }

    @Test
    fun `stats 参数解析对非法形态一律回落哨兵`() {
        listOf(null, "", " ", "abc", "0x", "1e3", "1.5", " 2", "2 ", "--1").forEach { raw ->
            assertEquals(
                "studentId 非法输入应回落 0：[$raw]",
                StatsDestination.ARG_STUDENT_ID_NONE,
                StatsDestination.studentIdOf(raw),
            )
            assertEquals(
                "homeworkId 非法输入应回落 -1：[$raw]",
                StatsDestination.ARG_HOMEWORK_ID_NONE,
                StatsDestination.homeworkIdOf(raw),
            )
            assertEquals(
                "epochDay 非法输入应回落 -1：[$raw]",
                StatsDestination.ARG_EPOCH_DAY_TODAY,
                StatsDestination.epochDayOf(raw),
            )
        }
        assertEquals(2L, StatsDestination.studentIdOf("+2"))
        assertEquals(0L, StatsDestination.studentIdOf("0"))
        assertEquals(-3L, StatsDestination.studentIdOf("-3"))
    }

    @Test
    fun `历史路由拼装与解析同源可往返`() {
        val route = StatsDestination.historyRoute(2L, 19_674L, 19_675L)
        assertEquals("stats/history/2?fromEpochDay=19674&toEpochDay=19675", route)

        val query = route.substringAfter('?')
        val rawFrom = query.substringAfter(StatsDestination.ARG_FROM_EPOCH_DAY + "=").substringBefore('&')
        val rawTo = query.substringAfter(StatsDestination.ARG_TO_EPOCH_DAY + "=").substringBefore('&')
        assertEquals(19_674L, StatsDestination.epochDayOf(rawFrom))
        assertEquals(19_675L, StatsDestination.epochDayOf(rawTo))

        assertEquals(
            "stats/history/2?fromEpochDay=19675&toEpochDay=19675",
            StatsDestination.historyRoute(2L, 19_675L),
        )
    }

    // ---- 4. 日期选择器换算口径 ----

    @Test
    fun `纪元日与 UTC 毫秒换算口径与实现一致`() {
        assertTrue(
            "MILLIS_PER_DAY 应为一天的毫秒数",
            navHostSource.contains("private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L"),
        )
        assertTrue(
            "纪元日 -> 毫秒应为左乘 MILLIS_PER_DAY",
            navHostSource.contains("private fun Long.toUtcMillisOfDay(): Long = this * MILLIS_PER_DAY"),
        )
        assertTrue(
            "毫秒 -> 纪元日应向下取整（floorDiv，兼容 1970 年之前的负毫秒）",
            navHostSource.contains("private fun Long.toEpochDayOf(): Long = Math.floorDiv(this, MILLIS_PER_DAY)"),
        )

        val millisPerDay = 24L * 60L * 60L * 1000L
        assertEquals(86_400_000L, millisPerDay)
        assertEquals(19_675L, Math.floorDiv(19_675L * millisPerDay, millisPerDay))
        assertEquals(-1L, Math.floorDiv(-1L, millisPerDay))
        assertEquals(-1L, Math.floorDiv(-millisPerDay + 1L, millisPerDay))
        assertEquals(0L, Math.floorDiv(millisPerDay - 1L, millisPerDay))
    }

    @Test
    fun `哨兵日期不作为选择器初值`() {
        assertTrue(
            "哨兵 -1 应被 takeIf 过滤为 null（交由用户重新选择）",
            navHostSource.contains("initialFromEpochDay = request.fromEpochDay.takeIf { it >= 0L }") &&
                navHostSource.contains("initialToEpochDay = request.toEpochDay.takeIf { it >= 0L }"),
        )
    }

    // ---- 5. 依赖白名单（无新增第三方依赖） ----

    @Test
    fun `导航宿主只引入 AndroidX 与项目内依赖`() {
        val imports = Regex("^import\\s+([\\w.]+)", RegexOption.MULTILINE)
            .findAll(navHostSource)
            .map { it.groupValues[1] }
            .toList()
        val allowedPrefixes = listOf(
            "androidx.compose.",
            "androidx.hilt.",
            "androidx.lifecycle.",
            "androidx.navigation.",
            "com.assignmate.",
            "dagger.",
            "javax.inject.",
            "java.",
            "kotlin.",
        )

        imports.forEach { imported ->
            assertTrue(
                "导航宿主引入了白名单外的依赖：$imported",
                allowedPrefixes.any { imported.startsWith(it) },
            )
        }
        assertTrue(
            "日期范围选择器应来自 Compose Material3（随 BOM 引入）",
            imports.contains("androidx.compose.material3.DateRangePicker") &&
                imports.contains("androidx.compose.material3.DatePickerDialog"),
        )
    }

    // ---- 源码解析工具 ----

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative), File("../app/" + relative))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative", file)
        return file!!.readText()
    }

    private fun blockForStatsRoute(template: String, expression: String): String {
        val pattern = Regex("route\\s*=\\s*" + Regex.escape(expression) + "\\s*[,)]")
        val block = composableBlocks.firstOrNull { pattern.containsMatchIn(it) }
        assertNotNull("未找到路由 $template（$expression）的注册块", block)
        return block!!
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

    private fun String.toConstantCase(): String =
        replace(Regex("([a-z])([A-Z])")) { it.groupValues[1] + "_" + it.groupValues[2] }.uppercase()

    private fun constantToParamName(constant: String): String {
        val tail = constant.removePrefix("ARG_")
        return tail.split('_').joinToString("") { part ->
            part.lowercase().replaceFirstChar { it.uppercase() }
        }.replaceFirstChar { it.lowercase() }
    }
}