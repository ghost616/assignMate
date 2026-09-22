package com.assignmate.app.auth.ui

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离朱独立验证探针：auth「今日盘点」导航入口的边界值 / 重复触发 / 旧调用点兼容 / 反向一致性。
 *
 * 与 AuthDaySummaryEntryTest 关注点互补（不改动被测源码，仅新增本文件）：
 * 1. 边界与极限值：家长卡片回调对 0 / 负数 / Long 极限值学生 id 的原样透传
 *    （既有用例只覆盖 10L、20L 两个普通值）。
 * 2. 重复触发：学生端无参入口连续点击按次回调，不吞调用、不叠加。
 * 3. 未接线降级 + 旧调用点兼容：**省略** onOpenDaySummary 即可构造动作集（Kotlin 默认空实现），
 *    边界入参下不抛异常、不误触发其它入口；与 `:app:assembleDebug` 中 NavHost 零改动编译互为证据。
 * 4. 反向一致性：动作集各自只声明一个今日盘点回调字段、两处界面各只渲染一个今日盘点按钮；
 *    且路由参数中**所有**回调都位于 Modifier 之前（Modifier/viewModel 之后不得再追加回调）。
 */
class AuthDaySummaryEntryBoundaryProbeTest {

    @Test
    fun `家长卡片今日盘点回调对边界学生 id 原样透传`() {
        val received = mutableListOf<Long>()
        val actions = parentActionsWithDaySummary { received += it }
        val boundaryIds = listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 9_007_199_254_740_993L)

        boundaryIds.forEach(actions.onOpenDaySummary)

        assertEquals("边界/极限学生 id 应逐个原样透传", boundaryIds, received)
    }

    @Test
    fun `学生首页今日盘点入口连续点击按次回调`() {
        var opened = 0
        val actions = StudentHomeActions(
            onLogout = {},
            onEnterHomework = {},
            onOpenDaySummary = { opened++ },
        )

        repeat(5) { actions.onOpenDaySummary() }

        assertEquals("每次点击都应回调一次，不吞调用也不叠加", 5, opened)
    }

    @Test
    fun `省略今日盘点回调构造的动作集可安全承载边界点击`() {
        var otherCalls = 0
        val bump: () -> Unit = { otherCalls++ }

        // 刻意不传 onOpenDaySummary：使用动作集声明的默认空实现（旧调用点只传既有参数即可编译）
        val parent = ParentHomeActions(
            onAddClick = bump,
            onAddNameChange = { otherCalls++ },
            onAddConfirm = bump,
            onAddDismiss = bump,
            onRenameClick = { otherCalls++ },
            onRenameNameChange = { otherCalls++ },
            onRenameConfirm = bump,
            onRenameDismiss = bump,
            onDeleteClick = { otherCalls++ },
            onDeleteConfirm = bump,
            onDeleteDismiss = bump,
            onCodeClick = { otherCalls++ },
            onCodeInputChange = { otherCalls++ },
            onRegenerateCode = bump,
            onSaveCustomCode = bump,
            onCodeDismiss = bump,
            onEnterHomework = { otherCalls++ },
            onLogout = bump,
            onOpenSettings = bump,
        )
        val student = StudentHomeActions(
            onLogout = bump,
            onEnterHomework = bump,
            onOpenThemeSettings = bump,
        )

        listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE).forEach(parent.onOpenDaySummary)
        repeat(3) { student.onOpenDaySummary() }

        assertEquals("默认空实现不应误触发其它入口", 0, otherCalls)
    }

    @Test
    fun `今日盘点回调字段与入口按钮均不被重复声明`() {
        val parentFields = ParentHomeActions::class.java.declaredFields
            .filter { it.name == "onOpenDaySummary" }
        val studentFields = StudentHomeActions::class.java.declaredFields
            .filter { it.name == "onOpenDaySummary" }

        assertEquals("家长动作集应恰好声明一个今日盘点回调字段", 1, parentFields.size)
        assertEquals("学生动作集应恰好声明一个今日盘点回调字段", 1, studentFields.size)
        assertEquals("家长侧今日盘点回调应携带学生 id（Function1）", FUNCTION1, parentFields[0].type)
        assertEquals("学生侧今日盘点回调应为无参（Function0）", FUNCTION0, studentFields[0].type)

        assertEquals(
            "家长中心应只渲染一个「今日盘点」按钮",
            1,
            countOccurrences(normalizedSource(PARENT_SOURCE), "text = \"今日盘点\""),
        )
        assertEquals(
            "学生首页应只渲染一个「📊 今日盘点」按钮",
            1,
            countOccurrences(normalizedSource(STUDENT_SOURCE), "\"📊 今日盘点\""),
        )
    }

    @Test
    fun `路由参数中所有回调均位于 Modifier 之前`() {
        val parentParameters = routeParameters(PARENT_FACADE, "ParentHomeRoute")
        val studentParameters = routeParameters(STUDENT_FACADE, "StudentHomeRoute")

        assertEquals("家长中心路由参数个数应为 10", 10, parentParameters.size)
        assertEquals("参数 4 应为携带学生 id 的今日盘点回调", FUNCTION1, parentParameters[4])
        assertEquals("参数 5 应为 Modifier", MODIFIER_NAME, parentParameters[5].name)
        assertTrue(
            "Modifier 之后不得再追加回调参数：${parentParameters}",
            parentParameters.drop(6).none { it == FUNCTION0 || it == FUNCTION1 },
        )

        assertEquals("学生首页路由参数个数应为 9", 9, studentParameters.size)
        assertEquals("参数 3 应为无参今日盘点回调", FUNCTION0, studentParameters[3])
        assertEquals("参数 4 应为 Modifier", MODIFIER_NAME, studentParameters[4].name)
        assertTrue(
            "Modifier 之后不得再追加回调参数：${studentParameters}",
            studentParameters.drop(5).none { it == FUNCTION0 || it == FUNCTION1 },
        )
    }

    // ---- 辅助 ----

    /** 家长中心动作集：仅覆写今日盘点回调，其余入口保持默认空实现 */
    private fun parentActionsWithDaySummary(
        onOpenDaySummary: (Long) -> Unit,
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
        onLogout = {},
        onOpenDaySummary = onOpenDaySummary,
    )

    private fun routeParameters(fileFacade: String, functionName: String): List<Class<*>> {
        val clazz = Class.forName("$AUTH_UI_PACKAGE.$fileFacade")
        val method = clazz.declaredMethods
            .firstOrNull { it.name == functionName && Modifier.isStatic(it.modifiers) }

        assertNotNull("未找到顶层 @Composable 函数 $functionName", method)
        return method!!.parameterTypes.toList()
    }

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

    private fun countOccurrences(source: String, needle: String): Int {
        var count = 0
        var index = source.indexOf(needle)

        while (index >= 0) {
            count++
            index = source.indexOf(needle, index + needle.length)
        }

        return count
    }

    private companion object {

        const val AUTH_UI_PACKAGE = "com.assignmate.app.auth.ui"

        const val PARENT_FACADE = "ParentHomeScreenKt"

        const val STUDENT_FACADE = "StudentHomeScreenKt"

        const val PARENT_SOURCE = "ParentHomeScreen.kt"

        const val STUDENT_SOURCE = "StudentHomeScreen.kt"

        const val MODIFIER_NAME = "androidx.compose.ui.Modifier"

        val FUNCTION0: Class<*> = Class.forName("kotlin.jvm.functions.Function0")
        val FUNCTION1: Class<*> = Class.forName("kotlin.jvm.functions.Function1")
    }
}