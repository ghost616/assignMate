package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离朱补强探针（对应测试说明「四、建议复测重点」第 1 条的后半句：
 * 「确认 Route 消费的正是 `cardText`（读码 + 该函数被生产代码调用）」）。
 *
 * 为什么需要这一层：既有测试（[HomeworkEntryOcrConfigNoticeTest] 的 `OcrNoticeRenderCounter`）
 * 复刻了 Route 的消费逻辑，能抓住「`ocrSetupGuidanceAction` 自身退化为静态短句」；
 * 但它的 `cardText` 来自**测试自己调用生产函数**的结果，因此抓不住另一种变异：
 * **Route 分支改回直接取静态常量**（如 `ocrSetupHint = OCR_PARENT_SETUP_NOTICE` 或
 * `ocrSetupHint = guidance.inlineHint`）——此时纯函数仍是对的，既有用例全绿，学生可见通道却已断裂。
 * 本类用「生产源码结构契约 + 分支级取值」补齐这一段：**只读取源码，不复制推导**。
 *
 * 覆盖：
 * A. 生产代码的 `NeedsOcrConfiguration` 分支消费 `ocrSetupGuidanceAction` 的产物（学生取 `action.cardText`）；
 * B. 该分支不存在「绕过收敛函数直接取静态常量 / 自行调用渲染入口」的写法；
 * C. 学生可见通道逐环节取值：收敛产物 → `cardText` → `ocrSetupHint` → `OcrSetupHintCard(text = …)`；
 * D. 学生卡片正文经 `cardText` 取出时保留「识别成功 N 张」且不等于无汇总信息的静态短句；
 * E. 通道互斥：家长/会话失效只走 Snackbar（无卡片），学生只走卡片（无 Snackbar）。
 */
class HomeworkEntryOcrStudentChannelWiringProbeTest {

    /** 学生侧任何用户可见文案中都不允许出现的字样 */
    private val studentForbiddenMarkers =
        listOf("设置页", "去设置", "设置中", "开启并填写厂商参数", "点击")

    private val rawSingleFailure =
        "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"

    private val rawRetrySummary =
        "识别成功 1 张，其余仍失败：识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"

    private fun studentAction(baseMessage: String): OcrSetupAction =
        ocrSetupGuidanceAction(ocrSetupGuidance(Role.STUDENT, hasSettingsEntry = true), baseMessage)

    // ---- A. 生产代码确实调用收敛函数并消费 cardText ----

    @Test
    fun `生产代码的未配置分支调用收敛函数并消费学生取用的卡片正文`() {
        val source = productionScreenSource()

        assertTrue(
            "Route 必须以 ocrSetupGuidance(role, onGoToOcrSettings != null) 推导引导意图",
            source.contains("val guidance = ocrSetupGuidance(uiState.role, onGoToOcrSettings != null)"),
        )
        assertTrue(
            "Route 必须消费生产收敛函数 ocrSetupGuidanceAction(guidance, event.message)",
            source.contains("val action = ocrSetupGuidanceAction(guidance, event.message)"),
        )
        assertTrue(
            "学生分支必须取收敛产物 cardText（否则成功计数到不了学生可见通道）",
            source.contains("is OcrSetupGuidance.StudentAskParent -> ocrSetupHint = action.cardText"),
        )
        assertTrue(
            "家长/会话失效分支必须取 snackbarMessage + actionLabel（动作只走按钮）",
            source.contains("message = action.snackbarMessage,") &&
                source.contains("actionLabel = action.actionLabel,"),
        )
        assertEquals(
            "未配置事件只保留一个 Snackbar 渲染点（识别失败链路里不得再有第二个 showSnackbar）",
            1,
            notConfiguredBranchLines().count { it.contains("showSnackbar(") },
        )
    }

    @Test
    fun `生产代码未配置分支不绕过收敛函数直接取静态常量`() {
        val code = productionScreenCodeOnly()

        listOf(
            "ocrSetupHint = OCR_PARENT_SETUP_NOTICE",
            "ocrSetupHint = OCR_PARENT_SETUP_HINT",
            "ocrSetupHint = guidance.inlineHint",
            "ocrSetupHint = studentSafeOcrMessage",
            "ocrSetupHint = ocrNoticeFor",
        ).forEach { shortcut ->
            assertFalse(
                "学生卡片正文必须来自 ocrSetupGuidanceAction 的 cardText，不得出现「$shortcut」",
                code.contains(shortcut),
            )
        }
        assertFalse(
            "Route 的识别提示分支不得自行调用渲染入口（提示正文只能来自收敛函数，自行调用即双重推导）",
            notConfiguredBranchCodeLines().any { it.contains("ocrNotice" + "For") },
        )
        assertFalse(
            "未配置分支不得绕过收敛产物直接取静态短句常量",
            code.contains("ocrSetupHint = OCR_PARENT_SETUP_NOTICE"),
        )
    }

    @Test
    fun `学生卡片正文参数已从静态短句字段切换为收敛产物`() {
        val source = productionScreenSource()

        assertFalse(
            "OcrSetupGuidance.inlineHint 字段应已删除（唯一消费方改为 cardText）",
            source.contains("val inlineHint"),
        )
        assertFalse("不得残留 guidance.inlineHint 消费", source.contains("guidance.inlineHint"))
        assertTrue(
            "卡片参数 KDoc 应写明取值来自 ocrSetupGuidanceAction 的 cardText",
            source.contains("取值来自 [ocrSetupGuidanceAction] 的 `cardText`"),
        )
    }

    // ---- B. 学生可见通道逐环节可达 ----

    @Test
    fun `学生可见通道各环节取值逐字一致`() {
        val code = productionScreenCodeOnly()

        assertTrue("Route 持有就地卡片状态 ocrSetupHint", code.contains("var ocrSetupHint by remember"))
        assertTrue("卡片状态必须传入无状态内容函数", code.contains("ocrSetupHint = ocrSetupHint,"))
        assertTrue("内容函数必须接收该参数", code.contains("ocrSetupHint: String? = null,"))
        assertTrue(
            "非空即渲染就地卡片，且正文逐字取该状态",
            code.contains("if (ocrSetupHint != null)") &&
                code.contains("OcrSetupHintCard(text = ocrSetupHint)"),
        )

        val cardText = studentAction(rawSingleFailure).cardText
        assertEquals(
            "卡片取值必须逐字等于渲染入口对同一原文的收敛产物",
            ocrSetupGuidance(Role.STUDENT, hasSettingsEntry = true).notice(rawSingleFailure).message,
            cardText,
        )
    }

    @Test
    fun `学生卡片正文对无汇总原文等于静态短句且零设置页字样`() {
        val cardText = studentAction(rawSingleFailure).cardText

        assertNotNull("学生分支必须给出卡片正文", cardText)
        assertEquals("无汇总信息时卡片正文等于既有兜底短句", OCR_PARENT_SETUP_NOTICE, cardText)
        assertTrue("须保留待重试事实", cardText!!.contains("图片已保存待重试"))
        assertTrue("须指向家长配置", cardText.contains(OCR_PARENT_SETUP_HINT))
        studentForbiddenMarkers.forEach { marker ->
            assertFalse("学生文案不得含「$marker」，实际：$cardText", cardText.contains(marker))
        }
        assertEquals("学生分支不携带动作", null, studentAction(rawSingleFailure).actionLabel)
    }

    // ---- C. 计数保留（学生可见通道的实际取值，经 cardText） ----

    @Test
    fun `学生卡片正文经 cardText 保留识别成功计数且不等于静态短句`() {
        val action = studentAction(rawRetrySummary)
        val cardText = action.cardText

        assertNotNull("重试部分成功时学生分支同样必须给出卡片正文", cardText)
        assertTrue("学生卡片正文必须保留「识别成功 1 张」，实际：$cardText", cardText!!.contains("识别成功 1 张"))
        assertFalse(
            "卡片正文不得退化为无汇总信息的静态短句（否则成功计数丢失）",
            cardText == OCR_PARENT_SETUP_NOTICE,
        )
        assertTrue("须保留待重试事实", cardText.contains("图片已保存待重试"))
        assertTrue("须指向家长配置", cardText.contains(OCR_PARENT_SETUP_HINT))
        studentForbiddenMarkers.forEach { marker ->
            assertFalse("学生文案不得含「$marker」，实际：$cardText", cardText.contains(marker))
        }
        assertEquals("学生分支不携带动作", null, action.actionLabel)
        assertEquals(
            "学生卡片正文 = 收敛函数产物（不是测试另写的一份推导）",
            ocrSetupGuidance(Role.STUDENT, hasSettingsEntry = true).notice(rawRetrySummary).message,
            cardText,
        )
    }

    // ---- D. 通道互斥：Snackbar 与卡片不会同时出现 ----

    @Test
    fun `学生只走卡片而家长与会话失效只走单条 Snackbar`() {
        Role.entries.forEach { role ->
            val action = ocrSetupGuidanceAction(ocrSetupGuidance(role, hasSettingsEntry = true), rawRetrySummary)
            when (role) {
                Role.STUDENT -> {
                    assertNotNull("学生必须走卡片通道（role=$role）", action.cardText)
                    assertTrue(
                        "学生分支的 Snackbar 文案不得被消费（UI 只取 cardText）",
                        action.cardText == action.snackbarMessage,
                    )
                }

                Role.PARENT -> {
                    assertEquals("家长不得渲染就地卡片", null, action.cardText)
                    assertEquals("家长动作 = 去设置", OCR_SETTINGS_ACTION_LABEL, action.actionLabel)
                    assertEquals("家长正文 = 底层原文", rawRetrySummary, action.snackbarMessage)
                }
            }
        }

        val sessionInvalid =
            ocrSetupGuidanceAction(ocrSetupGuidance(role = null, hasSettingsEntry = true), rawRetrySummary)
        assertEquals("会话失效不渲染就地卡片", null, sessionInvalid.cardText)
        assertEquals("会话失效正文 = 底层原文", rawRetrySummary, sessionInvalid.snackbarMessage)
        assertEquals("会话失效无动作", null, sessionInvalid.actionLabel)
    }

    // ---- 测试工具 ----

    /** 只读取生产源码（不复制推导） */
    private fun productionScreenSource(): String = productionScreenFile().readText(Charsets.UTF_8)

    /**
     * 去掉纯注释行后的生产源码：断言「不能这样写」时应看代码而非注释，
     * 避免把说明文字误判为实际写法。
     */
    private fun productionScreenCodeOnly(): String = productionScreenLines()
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .joinToString("\n")

    private fun productionScreenLines(): List<String> = productionScreenSource()
        .removePrefix("\uFEFF")
        .split("\n", "\r\n")
        .map { it.trimStart() }

    /**
     * 只截取「识别未配置提示」那一段（`HomeworkEntryEvent.NeedsOcrConfiguration` 分支）：
     * 全文件级别的计数会把 `Saved`、`ShowMessage` 分支与函数定义/注释一起算进来，
     * 那是测试自身的抓取范围问题，不是产品缺陷。切片以花括号配对为界，不依赖具体列宽。
     */
    private fun notConfiguredBranchLines(): List<String> {
        val lines = productionScreenLines()
        val start = lines.indexOfFirst { it.contains("is HomeworkEntryEvent.NeedsOcrConfiguration") }
        assertTrue("生产源码中应存在 NeedsOcrConfiguration 分支（读码锚点失效）", start >= 0)
        val head = lines[start]
        if (!head.endsWith("{")) {
            return listOf(head)
        }
        var depth = 0
        for (index in start until lines.size) {
            depth += lines[index].count { it == '{' } - lines[index].count { it == '}' }
            if (depth == 0 && index > start) {
                return lines.subList(start, index + 1)
            }
        }
        return lines.subList(start, lines.size)
    }

    /** 同上，但剔除注释行（断言「不得这样写」时只看实际代码） */
    private fun notConfiguredBranchCodeLines(): List<String> = notConfiguredBranchLines()
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun productionScreenFile(): File {
        val relative = "src/main/java/com/assignmate/app/homework/ui/HomeworkEntryScreen.kt"
        val candidates = listOf(
            File("app/$relative"),
            File(relative),
            File("../app/$relative"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "无法定位生产源码 HomeworkEntryScreen.kt（读码断言无法执行），尝试过：${candidates.map { it.absolutePath }}",
            found,
        )
        return found!!
    }
}
