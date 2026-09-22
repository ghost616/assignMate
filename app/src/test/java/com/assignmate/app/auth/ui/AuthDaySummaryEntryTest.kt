package com.assignmate.app.auth.ui

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * auth 模块「今日盘点」导航入口契约单测（纯 JVM，不依赖 Compose 运行时与 Android 框架）。
 *
 * 覆盖四类契约：
 * 1. 入口回调语义：家长侧 `ParentHomeActions.onOpenDaySummary(studentId)` 原样携带该卡片学生 id；
 *    学生侧 `StudentHomeActions.onOpenDaySummary` 为 Function0（无参）——盘点页按会话收敛为本人。
 * 2. 未接线安全：`parentHomeActionsDefault()` / `studentHomeActionsDefault()` 提供空实现，
 *    点击不抛异常、不误触发其它动作；既有入口（进入作业 / 护眼设置 / 退出登录）行为不变。
 * 3. Route 接线契约：JVM 下标记签——新增回调位于对应「设置类」回调之后、`Modifier` 之前
 *    （家长侧 Function1、学生侧 Function0）；源码声明 `= {}` 默认空实现，故 framework 旧调用点
 *    无需改动即可编译（Compose 路由函数不生成 Kotlin `$default` 合成方法，反射读不到默认值，按源码断言）。
 * 4. 页面接线与模块边界：家长卡片「今日盘点」按钮以 `student.id` 触发回调、学生首页按钮取动作集回调、
 *    入口文案与按钮次序就位，且 auth/ui 源码不 import stats / timer / settings 包（只暴露导航意图）。
 *
 * 说明：Compose 组件无法在 JVM 单测中执行（依赖 Android SDK 与 LocalContext），
 * 故「页面点击」行为落在「动作集回调 + 源码接线断言」两处证据上。
 * 注意：本工程未开启 Kotlin 的 `-java-parameters`，一律按 JVM 参数**下标**断言签名。
 */
class AuthDaySummaryEntryTest {

    // ---- 一、入口回调语义 ----

    @Test
    fun `家长中心卡片今日盘点回调携带该卡片学生 id`() {
        val received = mutableListOf<Long>()
        val actions = parentActions(onOpenDaySummary = { received += it })

        actions.onOpenDaySummary(FIRST_STUDENT_ID)
        actions.onOpenDaySummary(SECOND_STUDENT_ID)

        assertEquals(
            "回调应原样带回被点击卡片的学生 id",
            listOf(FIRST_STUDENT_ID, SECOND_STUDENT_ID),
            received,
        )
    }

    @Test
    fun `学生首页今日盘点入口被点击时回调被调用`() {
        var opened = 0
        val actions = StudentHomeActions(
            onLogout = {},
            onEnterHomework = {},
            onOpenDaySummary = { opened++ },
        )

        actions.onOpenDaySummary()

        assertEquals("点击「今日盘点」应回调一次", 1, opened)
    }

    @Test
    fun `学生端今日盘点回调为无参形态`() {
        val field = StudentHomeActions::class.java.declaredFields
            .firstOrNull { it.name == "onOpenDaySummary" }

        assertNotNull("学生首页动作集应暴露 onOpenDaySummary", field)
        assertEquals(
            "学生端入口须为无参回调（盘点页按会话收敛为本人，不传学生 id）",
            FUNCTION0,
            field!!.type,
        )
    }

    @Test
    fun `默认空实现下今日盘点被点击不抛异常且不触发其它动作`() {
        var otherCalls = 0
        val parent = parentActions(onLogout = { otherCalls++ })
        val student = StudentHomeActions(
            onLogout = { otherCalls++ },
            onEnterHomework = { otherCalls++ },
        )

        // 未接线（回调保持默认空实现）下点击「今日盘点」：无操作、不崩溃、不误触其它入口
        parent.onOpenDaySummary(FIRST_STUDENT_ID)
        student.onOpenDaySummary()

        assertEquals("默认空实现不应触发其它动作", 0, otherCalls)
    }

    @Test
    fun `默认动作集下既有入口行为不变`() {
        var enterHomework = 0
        var themeSettings = 0
        var logout = 0
        val student = StudentHomeActions(
            onLogout = { logout++ },
            onEnterHomework = { enterHomework++ },
            onOpenThemeSettings = { themeSettings++ },
        )

        student.onEnterHomework()
        student.onOpenThemeSettings()
        student.onLogout()

        assertEquals("既有「进入我的作业」行为不变", 1, enterHomework)
        assertEquals("既有「护眼设置」行为不变", 1, themeSettings)
        assertEquals("既有「退出登录」行为不变", 1, logout)
    }

    @Test
    fun `默认动作集工厂函数可直接用于未接线渲染`() {
        val parent = parentHomeActionsDefault()
        val student = studentHomeActionsDefault()

        // 新增入口的默认空实现逐个调用：不得抛异常（预览与未接线渲染仍可用）
        parent.onOpenDaySummary(FIRST_STUDENT_ID)
        student.onOpenDaySummary()
    }

    // ---- 二、Route 接线契约 ----

    @Test
    fun `家长中心路由的今日盘点回调位于设置回调之后 Modifier 之前`() {
        val parameters = routeParameters(PARENT_FACADE, "ParentHomeRoute")

        assertEquals("参数 3 应为「设置」回调", FUNCTION0, parameters[3])
        assertEquals("参数 4 应为携带学生 id 的「今日盘点」回调", FUNCTION1, parameters[4])
        assertEquals("今日盘点回调之后应为 Modifier", MODIFIER_NAME, parameters[5].name)
    }

    @Test
    fun `学生首页路由的今日盘点回调位于护眼设置回调之后 Modifier 之前`() {
        val parameters = routeParameters(STUDENT_FACADE, "StudentHomeRoute")

        assertEquals("参数 2 应为「护眼设置」回调", FUNCTION0, parameters[2])
        assertEquals("参数 3 应为无参「今日盘点」回调", FUNCTION0, parameters[3])
        assertEquals("今日盘点回调之后应为 Modifier", MODIFIER_NAME, parameters[4].name)
    }

    @Test
    fun `两个首页路由源码声明今日盘点回调的默认空实现`() {
        assertCallbackHasDefault("ParentHomeScreen.kt", PARENT_CALLBACK_DECLARATION)
        assertCallbackHasDefault("StudentHomeScreen.kt", STUDENT_CALLBACK_DECLARATION)
    }

    // ---- 三、页面接线与模块边界 ----

    @Test
    fun `家长卡片今日盘点按钮以卡片学生 id 触发回调`() {
        val code = codeOnly(normalizedSource("ParentHomeScreen.kt"))
        val cardBody = functionBodyOf(code, "fun StudentCard(")

        assertTrue(
            "卡片操作行应把所属学生 id 交给今日盘点回调：$CARD_WIRING",
            cardBody.contains(CARD_WIRING),
        )
    }

    @Test
    fun `家长中心卡片渲染今日盘点入口文案`() {
        assertTrue(
            "家长中心学生卡片应出现「今日盘点」按钮文案",
            normalizedSource("ParentHomeScreen.kt").contains("\"今日盘点\""),
        )
    }

    @Test
    fun `学生首页今日盘点入口渲染文案并取动作集回调`() {
        val source = normalizedSource("StudentHomeScreen.kt")

        assertTrue("学生首页应渲染「📊 今日盘点」入口", source.contains("\"📊 今日盘点\""))
        assertTrue(
            "学生首页按钮应取动作集回调 onClick = actions.onOpenDaySummary",
            codeOnly(source).contains("onClick = actions.onOpenDaySummary"),
        )
    }

    @Test
    fun `学生首页新增入口与既有入口并列且退出登录仍在最后`() {
        val source = normalizedSource("StudentHomeScreen.kt")
        val enterHomework = source.indexOf("\"📚 进入我的作业\"")
        val themeSettings = source.indexOf("\"🌙 护眼设置\"")
        val daySummary = source.indexOf("\"📊 今日盘点\"")
        val logout = source.indexOf("\"退出登录\"")

        assertTrue(
            "四个入口文案应齐备：$enterHomework/$themeSettings/$daySummary/$logout",
            listOf(enterHomework, themeSettings, daySummary, logout).all { it >= 0 },
        )
        assertTrue(
            "按钮次序应为 进入我的作业 → 护眼设置 → 今日盘点 → 退出登录",
            enterHomework < themeSettings && themeSettings < daySummary && daySummary < logout,
        )
    }

    @Test
    fun `auth 界面层不 import stats timer settings 包`() {
        listOf("ParentHomeScreen.kt", "StudentHomeScreen.kt").forEach { fileName ->
            val imports = normalizedSource(fileName).lines()
                .map { it.trim() }
                .filter { it.startsWith("import ") }

            FORBIDDEN_PACKAGES.forEach { packageName ->
                assertTrue(
                    "$fileName 不应 import $packageName（auth 只暴露导航意图）：$imports",
                    imports.none { it.contains(packageName) },
                )
            }
        }
    }

    // ---- 辅助 ----

    /** 家长中心动作集：仅覆写本用例关心的入口，其余保持默认空实现 */
    private fun parentActions(
        onLogout: () -> Unit = {},
        onOpenDaySummary: (Long) -> Unit = {},
    ): ParentHomeActions = ParentHomeActions(
        onAddClick = {},
        onAddNameChange = {},
        onAddConfirm = {},
        onAddDismiss = {},
        onRenameClick = {},
        onRenameNameChange = {},
        onRenameConfirm = {},
        onRenameDismiss = {},
        onDeleteClick = {},
        onDeleteConfirm = {},
        onDeleteDismiss = {},
        onCodeClick = {},
        onCodeInputChange = {},
        onRegenerateCode = {},
        onSaveCustomCode = {},
        onCodeDismiss = {},
        onEnterHomework = {},
        onLogout = onLogout,
        onOpenDaySummary = onOpenDaySummary,
    )

    /** 取路由函数的 JVM 参数类型列表（按位置断言，规避未开 -java-parameters 的参数名缺失） */
    private fun routeParameters(fileFacade: String, functionName: String): List<Class<*>> {
        val clazz = Class.forName("$AUTH_UI_PACKAGE.$fileFacade")
        val method = clazz.declaredMethods
            .firstOrNull { it.name == functionName && Modifier.isStatic(it.modifiers) }

        assertNotNull("未找到顶层 @Composable 函数 $functionName", method)
        return method!!.parameterTypes.toList()
    }

    /** 读取主源码并统一行尾，便于按行/子串断言；工作目录随执行器不同，按需向上查找 */
    private fun normalizedSource(fileName: String): String {
        val relative = "app/src/main/java/com/assignmate/app/auth/ui/$fileName"
        var dir: File? = File("").absoluteFile

        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, fileName))) {
                if (candidate.isFile) {
                    return candidate.readText().replace("\r\n", "\n")
                }
            }
            dir = dir.parentFile
        }

        throw AssertionError("未找到主源码文件 $relative（当前工作目录 ${File("").absolutePath}）")
    }

    /**
     * 断言「回调以 `= {}` 声明默认空实现」：
     * Compose 路由函数不生成 Kotlin `$default` 合成方法（本工程实测），反射无法读取默认值，
     * 故按源码声明校验；同时反向断言该参数未被写成必填（去掉默认值会破坏既有接线兼容）。
     */
    private fun assertCallbackHasDefault(fileName: String, declaration: String) {
        val source = normalizedSource(fileName)
        val required = declaration.removeSuffix(" = {}") + ","

        assertTrue("$fileName 应声明回调默认空实现：$declaration", source.contains(declaration))
        assertFalse("$fileName 中该回调不应为必填参数（须保留默认空实现）", source.contains(required))
    }

    /** 取源码中具名函数的函数体（大括号配对；入参须为已去注释/去字符串的 [codeOnly] 文本） */
    private fun functionBodyOf(code: String, signature: String): String {
        val signatureIndex = code.indexOf(signature)
        assertTrue("未找到函数声明 $signature", signatureIndex >= 0)
        val bodyStart = code.indexOf('{', signatureIndex)
        assertTrue("函数声明 $signature 无函数体", bodyStart >= 0)

        var depth = 0
        var index = bodyStart
        while (index < code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(bodyStart, index + 1)
                }
            }
            index++
        }

        throw AssertionError("函数声明 $signature 的大括号未配对")
    }

    /** 去掉行/块注释与字符串字面量，避免断言文本被注释或文案干扰（忽略嵌套模板的简化实现，够本用例使用） */
    private fun codeOnly(source: String): String {
        val builder = StringBuilder()
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var index = 0

        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when {
                inLineComment -> if (current == '\n') {
                    inLineComment = false
                    builder.append(current)
                }

                inBlockComment -> if (current == '*' && next == '/') {
                    inBlockComment = false
                    index++
                }

                inString -> when {
                    current == '\\' -> index++
                    current == '"' -> inString = false
                }

                inChar -> when {
                    current == '\\' -> index++
                    current == '\'' -> inChar = false
                }

                current == '/' && next == '/' -> inLineComment = true
                current == '/' && next == '*' -> {
                    inBlockComment = true
                    index++
                }

                current == '"' -> inString = true
                current == '\'' -> inChar = true
                else -> builder.append(current)
            }
            index++
        }

        return builder.toString()
    }

    private companion object {

        const val AUTH_UI_PACKAGE = "com.assignmate.app.auth.ui"

        const val PARENT_FACADE = "ParentHomeScreenKt"

        const val STUDENT_FACADE = "StudentHomeScreenKt"

        /** 家长侧卡片到回调的接线片段（源文件实际写法） */
        const val CARD_WIRING = "actions.onOpenDaySummary(student.id)"

        const val PARENT_CALLBACK_DECLARATION = "onOpenDaySummary: (Long) -> Unit = {}"

        const val STUDENT_CALLBACK_DECLARATION = "onOpenDaySummary: () -> Unit = {}"

        const val FIRST_STUDENT_ID = 10L

        const val SECOND_STUDENT_ID = 20L

        /** Compose Modifier 全限定名（按类型名断言，避免在单测中加载 Compose 类型） */
        const val MODIFIER_NAME = "androidx.compose.ui.Modifier"

        /** auth/ui 不得依赖的兄弟模块实现包（只暴露导航意图，接线由 framework 注入） */
        val FORBIDDEN_PACKAGES = listOf(
            "com.assignmate.app.stats",
            "com.assignmate.app.timer",
            "com.assignmate.app.settings",
        )

        val FUNCTION0: Class<*> = Class.forName("kotlin.jvm.functions.Function0")
        val FUNCTION1: Class<*> = Class.forName("kotlin.jvm.functions.Function1")
    }
}
