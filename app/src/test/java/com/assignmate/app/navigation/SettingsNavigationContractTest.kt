package com.assignmate.app.navigation

import com.assignmate.app.auth.ui.AuthDestination
import com.assignmate.app.homework.ui.HomeworkDestination
import com.assignmate.app.settings.ui.SettingsDestination
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * settings 导航接线契约静态一致性核查（framework：导航接线层）。
 *
 * 与既有 [AssignMateNavHostContractTest] / stats 契约测试同一约定：三个 settings 页面均为 Compose 页面，
 * 本环境无 Android 模拟器/真机，故以「源码级等价证据」覆盖接线契约——解析 NavHost 源码中
 * 注册的 `route = ...`、页面回调接线与导航扩展函数实现，逐条核对。
 *
 * 覆盖维度：
 * 1. 正向：三条 settings 路由（设置主页 / OCR 配置 / 护眼设置）均已注册，route 取自
 *    [SettingsDestination] 常量（非字符串字面量），模板串与 settings 模块常量一致；
 * 2. 入口接线：家长中心 onOpenSettings -> 设置主页、学生首页 onOpenThemeSettings -> 护眼设置、
 *    录入页 onGoToOcrSettings -> OCR 配置页（framework 统一注入跳转，是否展示入口由 homework 按角色决定）；
 * 3. 二级页回退语义：三个二级页均 popBackStack 回上一级、导航扩展不做 popUpTo，不产生回退环；
 * 4. 既有路由不被破坏：auth/homework/timer/stats 共 17 条路由常量与 settings 三条一并注册、且无重复。
 */
class SettingsNavigationContractTest {

    private val navHostSource: String = readSource(
        "src/main/java/com/assignmate/app/navigation/AssignMateNavHost.kt",
    )

    private val composableBlocks: List<String> = splitComposableBlocks(navHostSource)

    // ---- 1. 正向：三条 settings 路由注册（常量表达式 + 模板串） ----

    @Test
    fun `settings 三条路由模板与接线常量一致`() {
        assertEquals("settings/home", SettingsDestination.HOME)
        assertEquals("settings/ocr_config", SettingsDestination.OCR_CONFIG)
        assertEquals("settings/theme", SettingsDestination.THEME)
    }

    @Test
    fun `settings 三条路由均在 NavHost 中以常量注册`() {
        val expectations = mapOf(
            SettingsDestination.HOME to "SettingsDestination.HOME",
            SettingsDestination.OCR_CONFIG to "SettingsDestination.OCR_CONFIG",
            SettingsDestination.THEME to "SettingsDestination.THEME",
        )

        expectations.forEach { (template, expression) ->
            val pattern = Regex("route\\s*=\\s*" + Regex.escape(expression) + "\\s*[,)]")
            val matched = composableBlocks.filter { pattern.containsMatchIn(it) }
            assertEquals(
                "路由 $template 应恰好注册一次（表达式 $expression），实际 ${matched.size} 次",
                1,
                matched.size,
            )
        }

        // route 一律取各模块常量：NavHost 中不得出现字符串字面量注册
        assertEquals(
            "NavHost 中不应以字符串字面量注册 route",
            0,
            Regex("route\\s*=\\s*\"").findAll(navHostSource).count(),
        )
    }

    @Test
    fun `settings 三条路由无占位符故无需 navArgument 声明`() {
        listOf(
            SettingsDestination.HOME,
            SettingsDestination.OCR_CONFIG,
            SettingsDestination.THEME,
        ).forEach { template ->
            assertFalse("settings 路由 $template 不应带路径/查询占位符", template.contains("{"))
            assertFalse(
                "无占位符的路由 $template 不应声明 navArgument",
                blockForRoute(template).contains("navArgument("),
            )
        }
    }

    @Test
    fun `settings 各页返回键 popBackStack 回上一级`() {
        val backCallback = Regex("onBack\\s*=\\s*\\{\\s*navController\\.popBackStack\\(\\)\\s*}")

        listOf(
            SettingsDestination.HOME,
            SettingsDestination.OCR_CONFIG,
            SettingsDestination.THEME,
        ).forEach { template ->
            assertTrue(
                "路由 $template 的 onBack 应统一 popBackStack 回上一级",
                backCallback.containsMatchIn(blockForRoute(template)),
            )
        }
    }

    // ---- 2. 入口接线：家长中心 / 学生首页 / 录入页 ----

    @Test
    fun `家长中心设置入口已接线到设置主页`() {
        val parentHomeBlock = blockForRoute(AuthDestination.PARENT_HOME)
        assertTrue(
            "家长中心 onOpenSettings 应进 settings 设置主页",
            parentHomeBlock.contains("onOpenSettings = { navController.toSettingsHome() }"),
        )

        val helper = blockForHelper("toSettingsHome")
        assertNotNull("未找到 toSettingsHome 定义", helper)
        assertTrue(
            "toSettingsHome 应进入 SettingsDestination.HOME",
            helper!!.contains("navigate(SettingsDestination.HOME)"),
        )
    }

    @Test
    fun `学生首页护眼设置入口已接线到护眼设置页`() {
        val studentHomeBlock = blockForRoute(AuthDestination.STUDENT_HOME)
        assertTrue(
            "学生首页 onOpenThemeSettings 应直达 settings 护眼设置页",
            studentHomeBlock.contains("onOpenThemeSettings = { navController.toSettingsTheme() }"),
        )

        val helper = blockForHelper("toSettingsTheme")
        assertNotNull("未找到 toSettingsTheme 定义", helper)
        assertTrue(
            "toSettingsTheme 应进入 SettingsDestination.THEME",
            helper!!.contains("navigate(SettingsDestination.THEME)"),
        )
    }

    @Test
    fun `录入页去设置引导已接线到 OCR 配置页`() {
        val entryBlock = blockForRoute(HomeworkDestination.ENTRY)
        assertTrue(
            "录入页应注入 onGoToOcrSettings 跳转（是否展示按钮由 homework 按角色决定）",
            entryBlock.contains("onGoToOcrSettings = { navController.toSettingsOcrConfig() }"),
        )

        val helper = blockForHelper("toSettingsOcrConfig")
        assertNotNull("未找到 toSettingsOcrConfig 定义", helper)
        assertTrue(
            "toSettingsOcrConfig 应进入 SettingsDestination.OCR_CONFIG",
            helper!!.contains("navigate(SettingsDestination.OCR_CONFIG)"),
        )
    }

    @Test
    fun `设置主页两个二级入口分别接到 OCR 配置与护眼设置`() {
        val homeBlock = blockForRoute(SettingsDestination.HOME)
        assertTrue(
            "设置主页「识别设置」应接到 OCR 配置页",
            homeBlock.contains("onOpenOcrConfig = { navController.toSettingsOcrConfig() }"),
        )
        assertTrue(
            "设置主页「护眼设置」应接到护眼设置页",
            homeBlock.contains("onOpenTheme = { navController.toSettingsTheme() }"),
        )
    }

    // ---- 3. 回退语义：二级页普通压栈，不产生回退环 ----

    @Test
    fun `settings 导航扩展均普通压栈不产生回退环`() {
        listOf("toSettingsHome", "toSettingsOcrConfig", "toSettingsTheme").forEach { name ->
            val helper = blockForHelper(name)
            assertNotNull("未找到导航扩展函数 $name 定义", helper)
            assertFalse(
                "$name 为二级页跳转，不应携带 popUpTo（模板 inclusive 会破坏上一级回退）",
                helper!!.contains("popUpTo"),
            )
            assertFalse(
                "$name 不应以 popBackStack 代替压栈（否则二级页无法进入）",
                helper.contains("popBackStack"),
            )
            assertTrue(
                "$name 应为单条 navigate（普通压栈）",
                helper.count { it == '{' } <= 1 && helper.contains("navigate(SettingsDestination."),
            )
        }
    }

    // ---- 4. 无回归：既有 17 条路由常量仍全部注册且无重复 ----

    @Test
    fun `既有 auth homework timer stats 路由常量与 settings 一并注册且无重复`() {
        val expected = listOf(
            AuthDestination.ROLE_SELECT,
            AuthDestination.PARENT_LOGIN,
            AuthDestination.PARENT_REGISTER,
            AuthDestination.STUDENT_ENTER,
            AuthDestination.PARENT_HOME,
            AuthDestination.STUDENT_HOME,
            HomeworkDestination.LIST,
            HomeworkDestination.ENTRY,
            HomeworkDestination.TEMPLATE,
            HomeworkDestination.TIME_SET,
            "timer/execution/{studentId}/{homeworkId}",
            "timer/rest/{studentId}/{homeworkId}",
            "timer/next/{studentId}",
            "timer/completion/{studentId}",
            "stats/day/{studentId}?epochDay={epochDay}",
            "stats/item/{studentId}/{homeworkId}",
            "stats/history/{studentId}?fromEpochDay={fromEpochDay}&toEpochDay={toEpochDay}",
            SettingsDestination.HOME,
            SettingsDestination.OCR_CONFIG,
            SettingsDestination.THEME,
        )

        val registered = composableBlocks.mapNotNull { block ->
            val expression = Regex("route\\s*=\\s*([A-Za-z_][\\w.]*)\\s*[,)]")
                .find(block)
                ?.groupValues
                ?.get(1)
            expression?.let { routeConstantValue(it) }
        }

        assertEquals(
            "注册块数量应与解析出的 route 常量数量一致（新增路由需同步本测试白名单）",
            composableBlocks.size,
            registered.size,
        )
        assertEquals("既有 + settings 共 20 条路由模板应全部注册", expected.sorted(), registered.sorted())
        assertEquals(
            "不应存在重复注册的同名路由",
            emptyMap<String, Int>(),
            registered.groupingBy { it }.eachCount().filterValues { it > 1 },
        )
    }

    // ---- 源码解析工具（与既有契约测试同一约定：Gradle 单测工作目录为 app/，兼容仓库根运行） ----

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative), File("../app/" + relative))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative，尝试路径：${candidates.map { it.absolutePath }}", file)
        return file!!.readText()
    }

    /** 取某路由模板对应的注册块（按括号/花括号配平切块后，用常量表达式解析比对）。 */
    private fun blockForRoute(template: String): String {
        val block = composableBlocks.firstOrNull { candidate ->
            val match = Regex("route\\s*=\\s*([A-Za-z_][\\w.]*)\\s*[,)]").find(candidate)
                ?: return@firstOrNull false
            (routeConstantValue(match.groupValues[1]) ?: match.groupValues[1]) == template
        }
        assertNotNull("未找到路由 $template 的注册块", block)
        return block!!
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

    /** 把 `XxxDestination.ROUTE` 形式的注册 route 表达式解析为其常量值。 */
    private fun routeConstantValue(expression: String): String? = when (expression) {
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
        "TimerDestination.EXECUTION" -> "timer/execution/{studentId}/{homeworkId}"
        "TimerDestination.REST" -> "timer/rest/{studentId}/{homeworkId}"
        "TimerDestination.NEXT_ITEM" -> "timer/next/{studentId}"
        "TimerDestination.COMPLETION" -> "timer/completion/{studentId}"
        "StatsDestination.DAY_SUMMARY" -> "stats/day/{studentId}?epochDay={epochDay}"
        "StatsDestination.ITEM_DETAIL" -> "stats/item/{studentId}/{homeworkId}"
        "StatsDestination.HISTORY" ->
            "stats/history/{studentId}?fromEpochDay={fromEpochDay}&toEpochDay={toEpochDay}"
        "SettingsDestination.HOME" -> SettingsDestination.HOME
        "SettingsDestination.OCR_CONFIG" -> SettingsDestination.OCR_CONFIG
        "SettingsDestination.THEME" -> SettingsDestination.THEME
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
