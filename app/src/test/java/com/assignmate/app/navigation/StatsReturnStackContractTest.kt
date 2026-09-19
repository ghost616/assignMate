package com.assignmate.app.navigation

import com.assignmate.app.homework.ui.HomeworkDestination
import com.assignmate.app.stats.ui.StatsDestination
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stats 盘点页返回栈策略修复的独立复测（离朱）。
 *
 * 变更点：清单入口与历史入口共用 [AssignMateNavHost] 的 `toStatsDaySummary`，原先统一
 * `popUpTo(DAY_SUMMARY, inclusive = true)` 会把历史查询页一并弹掉，导致历史入口按返回键回到
 * 清单/首页而非历史页（丢失所选日期范围上下文）。
 *
 * 本类与既有 [AssignMateNavHostContractTest] / [StatsNavigationContractExtraTest] 互补，
 * 补齐两个既有测试的覆盖缺口：
 * 1. 历史入口不得携带任何 `popUpTo`（既有测试只断言了 cleaner 内部无 popUpTo，
 *    未断言历史分支的 navigate 配置本身不带 popUpTo）；
 * 2. 清栈去重语义：`removeDaySummaryInstances` 必须按 `destination.id` 弹出「当前栈顶」实例，
 *    而不是按 route 字符串（按 route 弹会命中历史页之下的旧盘点，越层清理）；
 * 3. 判定谓词的匹配基准：必须以注册 route 模板串（含查询参数占位符）作精确比对，
 *    并与 `*Route(...)` 拼装出的实际实例路由区分开——两者字符串不同，
 *    若误用实际实例路由去匹配会永远匹配不上（清栈失效、旧盘点堆积）。
 *
 * 环境说明：本环境无 Android 模拟器/真机（`adb devices` 为空、`emulator -list-avds` 无 AVD），
 * 故导航运行时行为仍以源码级契约断言等价覆盖（既有工程约定）；nav 库侧 `hasRoute`/
 * `popBackStack(route, inclusive)` 的语义已按 navigation 2.8.5 字节码核实。
 */
class StatsReturnStackContractTest {

    private val navHostSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt",
    )

    // ---- 1. 清单入口：栈 [清单, 盘点]，按 DAY_SUMMARY 模板 inclusive 替换（不得走清栈函数） ----

    @Test
    fun `清单入口按盘点路由模板 inclusive 替换且不触发历史入口清栈`() {
        val helper = blockForHelper("toStatsDaySummary")
        assertNotNull("未找到 toStatsDaySummary 定义", helper)

        val body = helper!!
        assertTrue("应声明 fromHistory 开关且缺省 false（缺省即清单入口）", body.contains("fromHistory: Boolean = false"))
        assertTrue(
            "仅清单入口（!fromHistory）才执行 popUpTo(DAY_SUMMARY, inclusive = true)",
            body.contains("if (!fromHistory)") &&
                body.contains("popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }"),
        )
        assertTrue(
            "清栈调用应置于 fromHistory 为真的分支内（清单入口不清理）",
            body.contains("if (fromHistory)") && body.contains("removeDaySummaryInstances()"),
        )
        assertTrue(
            "清栈必须先于 navigate 压入新实例（先清后压，否则新实例会被一起清掉）",
            body.indexOf("removeDaySummaryInstances()") < body.indexOf("navigate(StatsDestination."),
        )
    }

    @Test
    fun `历史入口的 navigate 配置不携带 popUpTo 以免连带弹掉历史页`() {
        val body = blockForHelper("toStatsDaySummary")!!
        val navigateOptions = navigateOptionsOf(body)

        assertTrue(
            "navigate 目标应为按所选日期拼装的盘点路由",
            navigateOptions.startsWith("navigate(StatsDestination.daySummaryRoute(studentId, epochDay))"),
        )
        // navigate 的 popUpTo 只允许出现在 !fromHistory 守卫内：历史入口（fromHistory = true）该守卫不成立，
        // 故弹栈配置不会执行 —— 历史页因此保留在栈中，返回键可回到历史页。
        assertTrue(
            "popUpTo 必须由 !fromHistory 守卫包裹（历史入口不执行任何 popUpTo）",
            navigateOptions.contains("if (!fromHistory)") &&
                navigateOptions.contains("popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }"),
        )
        assertTrue(
            "守卫内 popUpTo 之前的唯一语句应为被守卫的 if 判断本身",
            navigateOptions.indexOf("if (!fromHistory)") < navigateOptions.indexOf("popUpTo(StatsDestination.DAY_SUMMARY)"),
        )
        assertFalse(
            "整份 toStatsDaySummary 也不得出现按 HISTORY 模板的 popUpTo（同样会移除历史页）",
            body.contains("popUpTo(StatsDestination.HISTORY)"),
        )
    }

    // ---- 2. 历史入口清栈：逐层弹「当前栈顶」，遇历史页/非盘点页即停 ----

    @Test
    fun `清栈按当前栈顶 destination id 弹出且遇停条件齐备`() {
        val cleaner = blockForHelper("removeDaySummaryInstances")
        assertNotNull("未找到 removeDaySummaryInstances 定义", cleaner)

        val body = cleaner!!
        assertTrue(
            "应取当前栈顶实例（currentBackStackEntry 为空即兜底退出）",
            body.contains("val top = currentBackStackEntry ?: return"),
        )
        assertTrue("栈顶为历史查询页即停（保留历史页）", body.contains("if (isStatsHistory(top)) return"))
        assertTrue("栈顶非盘点页即停（不越过历史页/清单页弹栈）", body.contains("if (!isStatsDaySummary(top)) return"))
        assertTrue(
            "应按当前栈顶 destination.id 逐个弹出（按 route 字符串弹会命中历史页之下的旧盘点，越层清理）",
            body.contains("popBackStack(top.destination.id, inclusive = true)"),
        )
        assertTrue(
            "popBackStack 返回 false（无匹配/已到栈底）时应退出循环，避免死循环",
            body.contains("if (!popBackStack(top.destination.id, inclusive = true)) return"),
        )
        assertFalse("清栈不得使用 popUpTo（模板 inclusive 语义会连带移除历史页）", body.contains("popUpTo"))
        assertFalse(
            "清栈不得按路由模板 popBackStack（同样会越层命中历史页之下的旧盘点）",
            body.contains("popBackStack(StatsDestination."),
        )
    }

    @Test
    fun `清栈循环为自栈顶向下的循环结构且仅弹盘点实例`() {
        val body = blockForHelper("removeDaySummaryInstances")!!
        val loopStart = body.indexOf("while (true)")
        val loopEnd = body.lastIndexOf('}')

        assertTrue("应为 while (true) 循环（自栈顶逐个向下）", loopStart >= 0 && loopEnd > loopStart)
        val loop = body.substring(loopStart, loopEnd)
        assertEquals(
            "循环体内应恰好一次弹栈（每次只弹当前栈顶一个实例）",
            1,
            Regex("popBackStack\\(").findAll(loop).count(),
        )
        assertEquals(
            "循环体内应恰好一次盘点实例判定（判定后立即决定继续或停止）",
            1,
            Regex("isStatsDaySummary\\(").findAll(loop).count(),
        )
    }

    // ---- 3. 判定基准：注册 route 模板串 vs 拼装后的实际实例路由 ----

    @Test
    fun `判定谓词按注册 route 模板精确比对并与实际实例路由区分`() {
        val history = definitionOf("isStatsHistory")
        val daySummary = definitionOf("isStatsDaySummary")

        assertTrue(
            "历史页判定应按注册模板串精确比对：entry.destination.route == StatsDestination.HISTORY",
            history.contains("entry.destination.route == StatsDestination.HISTORY"),
        )
        assertTrue(
            "盘点页判定应按注册模板串精确比对：entry.destination.route == StatsDestination.DAY_SUMMARY",
            daySummary.contains("entry.destination.route == StatsDestination.DAY_SUMMARY"),
        )
        assertFalse(
            "历史页判定不得用 startsWith/contains 等前缀匹配（会误判）",
            history.contains("startsWith") || history.contains("contains("),
        )
        assertFalse(
            "盘点页判定不得用 startsWith/contains 等前缀匹配（会误判）",
            daySummary.contains("startsWith") || daySummary.contains("contains("),
        )

        // 模板串 = 注册时 route 常量的原样值（带 {占位符}）；实际实例路由已填具体参数，两者必然不同。
        // 若误用实际实例路由去匹配 destination.route，将永远匹配不上（清栈失效、旧盘点堆积）。
        assertEquals(
            "盘点页模板串应为带占位符的注册模板",
            "stats/day/{studentId}?epochDay={epochDay}",
            StatsDestination.DAY_SUMMARY,
        )
        assertEquals(
            "历史页模板串应为带占位符的注册模板",
            "stats/history/{studentId}?fromEpochDay={fromEpochDay}&toEpochDay={toEpochDay}",
            StatsDestination.HISTORY,
        )
        assertFalse(
            "盘点页模板串与拼装出的实际实例路由不同（证明不能混用）",
            StatsDestination.DAY_SUMMARY == StatsDestination.daySummaryRoute(2L, 19_675L),
        )
        assertFalse(
            "历史页模板串与拼装出的实际实例路由不同（证明不能混用）",
            StatsDestination.HISTORY == StatsDestination.historyRoute(2L, 19_674L, 19_675L),
        )
    }

    @Test
    fun `历史入口保留历史页而清单入口栈中恒只有一个盘点实例`() {
        // 历史入口：fromHistory = true -> 不清历史页 + 普通压栈（无 popUpTo）-> [清单, 历史, 新盘点]
        val helper = blockForHelper("toStatsDaySummary")!!
        assertTrue("历史入口应走清栈分支", helper.contains("removeDaySummaryInstances()"))
        // 历史入口的弹栈配置整体被 !fromHistory 守卫包裹：fromHistory = true 时不会执行 popUpTo，
        // 故历史页仍在栈中，返回键回到历史页（保留所选日期范围上下文）。
        assertTrue(
            "历史入口的 navigate 弹栈配置须整体处于 !fromHistory 守卫内",
            helper.contains("if (!fromHistory)") &&
                helper.contains("popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }"),
        )

        // 清单入口：popUpTo(DAY_SUMMARY, inclusive) 替换栈中旧盘点 -> [清单, 盘点]
        assertTrue(
            "清单入口按 DAY_SUMMARY 模板 inclusive 替换旧盘点实例（旧实例 arguments 入栈即固定，复用只会显示旧日期）",
            helper.contains("popUpTo(StatsDestination.DAY_SUMMARY) { inclusive = true }"),
        )

        // 历史页内部换范围仍为「先移除旧历史实例、再压入新实例」，与盘点清栈互不耦合
        val rangeHelper = blockForHelper("toStatsHistoryRange")!!
        assertTrue(
            "换范围按 HISTORY 模板 popUpTo inclusive（栈中恒只有一个历史页）",
            rangeHelper.contains("popUpTo(StatsDestination.HISTORY) { inclusive = true }"),
        )
        assertFalse("换范围不涉及盘点实例清理", rangeHelper.contains("removeDaySummaryInstances"))
    }

    // ---- 4. 入口接线：历史页 onOpenDaySummary 完整透传所选日期并标记 fromHistory ----

    @Test
    fun `历史页入口携带所选日期并显式标记 fromHistory`() {
        val historyBlock = blockForRoute(StatsDestination.HISTORY)
        assertNotNull("未找到历史查询路由注册块", historyBlock)

        val block = historyBlock!!
        assertTrue("回调应完整接收 studentId 与 epochDay", block.contains("onOpenDaySummary = { studentId, epochDay ->"))
        assertTrue("应透传所选日期进盘点页", block.contains("epochDay = epochDay"))
        assertTrue("应显式标记 fromHistory = true", block.contains("fromHistory = true"))
        assertTrue("应调用 toStatsDaySummary（与清单入口同一路由）", block.contains("navController.toStatsDaySummary("))

        val listBlock = blockForRoute(HomeworkDestination.LIST)
        assertNotNull("未找到作业清单路由注册块", listBlock)
        assertTrue(
            "清单入口应以缺省日期（今天）进入盘点页，不带 fromHistory",
            listBlock!!.contains("onOpenStats = { statsStudentId -> navController.toStatsDaySummary(statsStudentId) }"),
        )
    }

    // ---- 源码解析工具（与既有契约测试同一约定：Gradle 单测工作目录为 app/，兼容仓库根运行） ----

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative), File("../app/" + relative))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative，尝试路径：${candidates.map { it.absolutePath }}", file)
        return file!!.readText()
    }

    /**
     * 切出 `navigate(...)` 调用表达式（含尾随的 options lambda），用于断言「弹栈配置落在哪个守卫内」。
     *
     * 按括号/花括号混合配平扫描：从 `navigate(` 起，圆括号深度归零时若紧跟 `{` 则继续吃进该 lambda，
     * 花括号归零即表达式结束。注释与字符串字面量会被跳过（避免注释里的括号干扰配平）。
     */
    private fun navigateOptionsOf(helperBody: String): String {
        val start = helperBody.indexOf("navigate(")
        assertTrue("未在目标函数体内找到 navigate 调用", start >= 0)
        var depthParen = 0
        var depthBrace = 0
        var started = false
        var i = start
        var inString = false
        var inLineComment = false
        var inBlockComment = false
        while (i < helperBody.length) {
            val ch = helperBody[i]
            val next = helperBody.getOrNull(i + 1)
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
                ch == '(' -> {
                    depthParen++
                    started = true
                }
                ch == ')' -> {
                    depthParen--
                    // 圆括号归零：若后续是 options lambda 则继续，否则表达式结束
                    if (started && depthParen == 0) {
                        val rest = helperBody.substring(i + 1)
                        if (!rest.trimStart().startsWith("{")) {
                            return helperBody.substring(start, i + 1)
                        }
                    }
                }
                ch == '{' -> depthBrace++
                ch == '}' -> {
                    depthBrace--
                    if (started && depthParen == 0 && depthBrace == 0) {
                        return helperBody.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return helperBody.substring(start)
    }
    /** 取某个私有导航扩展函数的完整定义块（按 `{` 配平切片）。 */
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

    /** 取表达式体私有函数的定义文本（自 `private fun <name>(` 到下一个 `private fun`）。 */
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

    /** 按 `composable(route = <表达式>...)` 切出注册块（按括号/花括号配平）。 */
    private fun blockForRoute(template: String): String? =
        splitComposableBlocks(navHostSource).firstOrNull { block ->
            val match = Regex("route\\s*=\\s*([A-Za-z_][\\w.]*)\\s*[,)]").find(block) ?: return@firstOrNull false
            (routeConstantValue(match.groupValues[1]) ?: match.groupValues[1]) == template
        }

    /** 把 `XxxDestination.ROUTE` 形式的注册 route 表达式解析为其常量值。 */
    private fun routeConstantValue(expression: String): String? = when (expression) {
        "StatsDestination.DAY_SUMMARY" -> StatsDestination.DAY_SUMMARY
        "StatsDestination.ITEM_DETAIL" -> StatsDestination.ITEM_DETAIL
        "AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY" -> AppDestination.STATS_ITEM_DETAIL_WITH_EPOCH_DAY
        "StatsDestination.HISTORY" -> StatsDestination.HISTORY
        "HomeworkDestination.LIST" -> HomeworkDestination.LIST
        else -> null
    }

    /** 以括号配平切出每个 `composable(...) { ... }` 注册块（跳过字符串字面量与注释）。 */
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