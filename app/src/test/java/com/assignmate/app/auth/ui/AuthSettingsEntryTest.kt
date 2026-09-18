package com.assignmate.app.auth.ui

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * auth 模块「设置 / 护眼设置」入口契约单测（纯 JVM，不依赖 Compose 运行时与 Android 框架）。
 *
 * 覆盖三类契约：
 * 1. 入口回调可被触发：ParentHomeActions.onOpenSettings / StudentHomeActions.onOpenThemeSettings
 *    点击即回调；未接线（默认空实现）下点击不抛异常、不误触发其它动作、既有入口行为不变。
 * 2. Route 接线契约：
 *    - JVM 位置签名：新回调以 Function0 形态紧随 onEnterHomework（位于 Modifier 之前）；
 *    - 源码声明：新增参数带 `= {}` 默认空实现，故 framework NavHost 旧调用点无需改动即可编译
 *      （Compose 路由函数不生成 Kotlin `$default` 合成方法，反射无法读取默认值，故按源码声明断言）；
 *    - 本次仅新增一个导航回调参数（不做「参数名为 onOpen*」之类的命名强约束）。
 * 3. 模块边界：auth 不依赖 settings 实现——auth/ui 源码无 settings 包 import，
 *    运行时类加载器也取不到 settings 类型。
 *
 * 说明：Compose 组件无法在 JVM 单测中直接执行（依赖 Android SDK 与 LocalContext），
 * 故「默认空实现不崩溃」的验证落在各入口实际持有的空回调上；页面按钮一律从动作集取回调
 * （ParentHomeContent 的「设置」、StudentHomeContent 的「护眼设置」），动作集回调即页面点击行为。
 *
 * 注意：本工程未开启 Kotlin 的 `-java-parameters`，`Parameter.getName()` 只会得到 arg0/p0，
 * 故一律按 JVM 参数**下标**（非名字）断言签名。
 */
class AuthSettingsEntryTest {

    // ---- 一、入口回调可被触发 ----

    @Test
    fun `家长中心设置入口被点击时回调被调用`() {
        var opened = 0
        val actions = parentActions(onOpenSettings = { opened++ })

        actions.onOpenSettings()

        assertEquals("点击「设置」应回调一次", 1, opened)
    }

    @Test
    fun `学生首页护眼设置入口被点击时回调被调用`() {
        var opened = 0
        val actions = StudentHomeActions(
            onLogout = {},
            onEnterHomework = {},
            onOpenThemeSettings = { opened++ },
        )

        actions.onOpenThemeSettings()

        assertEquals("点击「护眼设置」应回调一次", 1, opened)
    }

    @Test
    fun `默认空实现下新增入口被点击不抛异常且不触发其它动作`() {
        var otherCalls = 0
        val parent = ParentHomeActions(
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
            onLogout = { otherCalls++ },
        )
        val student = StudentHomeActions(
            onLogout = { otherCalls++ },
            onEnterHomework = {},
        )

        parent.onOpenSettings()
        student.onOpenThemeSettings()

        assertEquals("默认空实现不应触发其它动作", 0, otherCalls)
    }

    @Test
    fun `默认动作集下既有进入作业与退出登录行为不变`() {
        var enterHomework = 0
        var studentLogout = 0
        val student = StudentHomeActions(
            onLogout = { studentLogout++ },
            onEnterHomework = { enterHomework++ },
        )

        student.onEnterHomework()
        student.onLogout()

        assertEquals("既有「进入我的作业」行为不变", 1, enterHomework)
        assertEquals("既有「退出登录」行为不变", 1, studentLogout)
    }

    @Test
    fun `默认动作集工厂函数可直接用于未接线渲染`() {
        val parent = parentHomeActionsDefault()
        val student = studentHomeActionsDefault()

        // 全部入口为空实现：逐个调用均不得抛异常（页面在未接线时点击无操作）
        parent.onOpenSettings()
        student.onOpenThemeSettings()
        parent.onLogout()
        student.onLogout()
        student.onEnterHomework()
    }

    // ---- 二、Route 接线契约 ----

    @Test
    fun `家长中心路由在 onEnterHomework 之后新增无参设置回调`() {
        val parameters = routeParameters("ParentHomeScreenKt", "ParentHomeRoute")

        assertEquals("家长中心路由参数个数应与接线契约一致", 9, parameters.size)
        assertEquals("参数 0 为会话失效回调", FUNCTION0, parameters[0])
        assertEquals("参数 1 为登出回调", FUNCTION0, parameters[1])
        assertEquals("参数 2 为进入作业回调", FUNCTION1, parameters[2])
        assertEquals("参数 3（新增）应为无参设置回调", FUNCTION0, parameters[3])
        assertEquals("参数 4 应为 Modifier", MODIFIER_NAME, parameters[4].name)
    }

    @Test
    fun `学生首页路由在 onEnterHomework 之后新增无参护眼设置回调`() {
        val parameters = routeParameters("StudentHomeScreenKt", "StudentHomeRoute")

        assertEquals("学生首页路由参数个数应与接线契约一致", 8, parameters.size)
        assertEquals("参数 0 为登出回调", FUNCTION0, parameters[0])
        assertEquals("参数 1 为进入作业回调", FUNCTION0, parameters[1])
        assertEquals("参数 2（新增）应为无参护眼设置回调", FUNCTION0, parameters[2])
        assertEquals("参数 3 应为 Modifier", MODIFIER_NAME, parameters[3].name)
    }

    @Test
    fun `两个首页路由仅新增一个导航回调参数`() {
        val parentCallbacks = callbackParameterNumbers("ParentHomeScreenKt", "ParentHomeRoute")
        val studentCallbacks = callbackParameterNumbers("StudentHomeScreenKt", "StudentHomeRoute")

        assertEquals("家长中心新增后共 4 个回调参数", listOf(0, 1, 2, 3), parentCallbacks)
        assertEquals("学生首页新增后共 3 个回调参数", listOf(0, 1, 2), studentCallbacks)
    }

    @Test
    fun `两个首页路由源码声明新回调的默认空实现`() {
        assertNewCallbackHasDefault("ParentHomeScreen.kt", "onOpenSettings")
        assertNewCallbackHasDefault("StudentHomeScreen.kt", "onOpenThemeSettings")
    }

    @Test
    fun `动作集对外暴露的入口回调包含新增入口且保留既有入口`() {
        val parentCallbacks = functionFields(ParentHomeActions::class.java)

        assertTrue("家长中心动作集应保留既有登出入口", "onLogout" in parentCallbacks)
        assertTrue("家长中心动作集应新增设置入口", "onOpenSettings" in parentCallbacks)
        assertTrue("家长中心动作集应保留既有进入作业入口", "onEnterHomework" in parentCallbacks)

        val studentCallbacks = functionFields(StudentHomeActions::class.java)

        assertEquals(
            "学生首页动作集应恰好暴露三个入口",
            setOf("onLogout", "onEnterHomework", "onOpenThemeSettings"),
            studentCallbacks,
        )
    }

    // ---- 三、模块边界：不依赖 settings ----

    @Test
    fun `auth 界面层源码不 import settings 包`() {
        val sources = listOf("ParentHomeScreen.kt", "StudentHomeScreen.kt")

        sources.forEach { fileName ->
            val settingsImports = normalizedSource(fileName).lines().filter { line ->
                val trimmed = line.trim()
                trimmed.startsWith("import ") && trimmed.contains(SETTINGS_PACKAGE_SOURCE)
            }

            assertTrue(
                "$fileName 不应 import settings 包（auth 只暴露导航意图）：$settingsImports",
                settingsImports.isEmpty(),
            )
        }
    }

    // ---- 辅助 ----

    /** 取路由函数的 JVM 参数类型列表（按位置断言，规避未开 -java-parameters 的参数名缺失） */
    private fun routeParameters(fileFacade: String, functionName: String): List<Class<*>> {
        val clazz = Class.forName("$AUTH_UI_PACKAGE.$fileFacade")
        val method = clazz.declaredMethods
            .firstOrNull { it.name == functionName && Modifier.isStatic(it.modifiers) }

        assertNotNull("未找到顶层 @Composable 函数 $functionName", method)
        return method!!.parameterTypes.toList()
    }

    /** 回调参数（Function0/Function1）在路由方法中的参数序号 */
    private fun callbackParameterNumbers(fileFacade: String, functionName: String): List<Int> =
        routeParameters(fileFacade, functionName)
            .mapIndexedNotNull { index, type -> index.takeIf { type == FUNCTION0 || type == FUNCTION1 } }

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
     * 断言「新回调以 `= {}` 声明默认空实现」：
     * Compose 路由函数不生成 Kotlin `$default` 合成方法（本工程实测），反射无法读取默认值，
     * 故按源码声明校验；同时反向断言该参数未被写成必填（去掉默认值会破坏既有接线兼容）。
     */
    private fun assertNewCallbackHasDefault(fileName: String, callbackName: String) {
        val source = normalizedSource(fileName)
        val declared = "$callbackName: () -> Unit = {}"
        val required = "$callbackName: () -> Unit,"

        assertTrue("$fileName 中 $callbackName 应声明默认空实现：$declared", source.contains(declared))
        assertFalse("$fileName 中 $callbackName 不应为必填参数（须保留默认空实现）", source.contains(required))
    }

    /**
     * 动作集中承载回调的字段名。
     *
     * 注意：家长中心动作集含 `onEnterHomework: (Student) -> Unit`（Function1），
     * 若只收 Function0 会把既有入口漏掉，故两种元数都收。
     */
    private fun functionFields(clazz: Class<*>): Set<String> =
        clazz.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && (it.type == FUNCTION0 || it.type == FUNCTION1) }
            .map { it.name }
            .toSet()

    private fun parentActions(
        onEnterHomework: (com.assignmate.app.auth.domain.Student) -> Unit = {},
        onLogout: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
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
        onEnterHomework = onEnterHomework,
        onLogout = onLogout,
        onOpenSettings = onOpenSettings,
    )

    private companion object {

        const val AUTH_UI_PACKAGE = "com.assignmate.app.auth.ui"

        const val SETTINGS_PACKAGE_SOURCE = "com.assignmate.app.settings"

        /** Compose Modifier 全限定名（按类型名断言，避免在单测中加载 Compose 类型） */
        const val MODIFIER_NAME = "androidx.compose.ui.Modifier"

        val FUNCTION0: Class<*> = Class.forName("kotlin.jvm.functions.Function0")
        val FUNCTION1: Class<*> = Class.forName("kotlin.jvm.functions.Function1")
    }
}