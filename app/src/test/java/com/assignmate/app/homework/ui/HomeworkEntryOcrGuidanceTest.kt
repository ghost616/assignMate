package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「识别服务未配置」引导意图与**渲染入口**的角色分支单测（[ocrSetupGuidance] / [ocrNoticeFor]）。
 *
 * 收敛口径（只有单层）：正文与「去设置」动作由 UI 渲染入口 [ocrNoticeFor] 一次性产出；
 * `OcrSetupGuidance.notice(baseMessage)` 是它的角色封装（UI 两个分支消费同一产物），
 * ViewModel 不预先收敛（否则两层各收敛一次会让家长正文出现重复后缀、或同一提示被渲染两次）。
 *
 * 覆盖：
 * - 家长：**有按钮时正文仍逐字等于底层原文**（按钮说明只走 [OcrNotice.actionLabel]，不写进正文、
 *   正文不含「点击」）；无按钮时 actionLabel 为 null 且正文仍为原文、不声称可点击；
 * - 学生：无按钮、正文为「请让家长先配置识别服务」版本（无任何指向设置页的文字），
 *   且**只替换设置页片段**——「识别成功 N 张」等计数信息必须原样保留；
 * - role = null：沿用底层原文、无按钮、不额外引导；
 * - 分支互斥（Role.entries）与「正文-按钮一致性」的通用约束。
 *
 * 说明：Compose 组合层渲染无法在 JVM 断言（本仓库无设备/未引入 Robolectric），
 * 故把「正文 + 动作」的推导抽为纯函数 [ocrNoticeFor] 并对其断言；
 * Route 按 [ocrSetupGuidance] 决定渲染 Snackbar（家长/会话失效，消费 message+actionLabel）
 * 或就地卡片（学生，消费 message）——两侧消费同一产物的断言见 HomeworkEntryOcrConfigNoticeTest。
 */
class HomeworkEntryOcrGuidanceTest {

    // ---- 家长分支 ----

    @Test
    fun `家长会话且已注入设置页入口时出现去设置意图`() {
        val guidance = ocrSetupGuidance(role = Role.PARENT, hasSettingsEntry = true)

        assertEquals("去设置", guidance.actionLabel)
        assertTrue("家长分支应为 Parent 意图", guidance is OcrSetupGuidance.Parent)
        assertEquals(
            "有按钮时正文仍等于原文（按钮说明只走 actionLabel）",
            "识别服务未配置",
            guidance.notice("识别服务未配置").message,
        )
    }

    @Test
    fun `家长会话未注入设置页入口时只提示无按钮`() {
        val guidance = ocrSetupGuidance(role = Role.PARENT, hasSettingsEntry = false)

        assertNull("无设置页入口时不渲染按钮（保持既有行为）", guidance.actionLabel)
        assertTrue(guidance is OcrSetupGuidance.Parent)
        assertNull("无按钮时动作仍为 null", guidance.notice("识别服务未配置").actionLabel)
    }

    @Test
    fun `家长分支正文等于原文且去设置只作为动作呈现`() {
        val baseMessage = "识别服务未配置"
        val notice = ocrNoticeFor(role = Role.PARENT, hasSettingsEntry = true, baseMessage = baseMessage)

        assertEquals("正文等于既有原文（不夹带按钮说明）", baseMessage, notice.message)
        assertEquals("去设置只作为 Snackbar 动作（按钮）呈现", "去设置", notice.actionLabel)
        assertFalse("正文不得声称可点击", notice.message.contains("点击"))
        assertFalse("正文不得混入按钮文案", notice.message.contains(OCR_SETTINGS_ACTION_LABEL))
    }

    @Test
    fun `家长分支无按钮时文案不得声称可点击`() {
        val baseMessage = "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"
        val notice = ocrNoticeFor(role = Role.PARENT, hasSettingsEntry = false, baseMessage = baseMessage)

        assertNull("未注入回调即无按钮", notice.actionLabel)
        assertFalse("无按钮时正文不得出现「点击」字样", notice.message.contains("点击"))
        assertEquals("无按钮时正文仍是既有原文（只提示、无按钮）", baseMessage, notice.message)
    }

    @Test
    fun `家长分支同一提示只产出一次且后缀不重复`() {
        val baseMessage = "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"
        val notice = ocrNoticeFor(Role.PARENT, hasSettingsEntry = true, baseMessage = baseMessage)

        assertEquals("正文逐字等于既有原文，无任何追加后缀", baseMessage, notice.message)
        assertEquals("去设置", notice.actionLabel)
        assertTrue("家长文案保留识别原文", notice.message.startsWith(baseMessage))
        assertTrue("家长文案保留待重试事实", notice.message.contains("图片已保存待重试"))
    }

    @Test
    fun `家长文案重复收敛也不变形（按钮说明不再写进正文）`() {
        val baseMessage = "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"
        val guidance = ocrSetupGuidance(Role.PARENT, hasSettingsEntry = true)
        val notice = guidance.notice(baseMessage)

        // 正文不再承载按钮说明，因此即使调用方误二次收敛也不会出现重复后缀
        assertEquals("二次收敛结果不变", notice.message, guidance.notice(notice.message).message)
        assertEquals("按钮说明不会出现在正文中", 0, Regex("点击「").findAll(notice.message).count())
    }

    @Test
    fun `学生分支的改写是幂等的（重复收敛不会继续变形）`() {
        val baseMessage = "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"
        val guidance = ocrSetupGuidance(Role.STUDENT, hasSettingsEntry = true)
        val notice = guidance.notice(baseMessage)

        assertEquals(OCR_PARENT_SETUP_NOTICE, notice.message)
        assertEquals(
            "学生分支按片段替换，重复收敛结果不变",
            notice.message,
            guidance.notice(notice.message).message,
        )
    }

    // ---- 学生分支 ----

    @Test
    fun `学生会话不出现去设置意图而是提示请家长配置`() {
        val guidance = ocrSetupGuidance(role = Role.STUDENT, hasSettingsEntry = true)
        val notice = guidance.notice("识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）")

        assertNull("学生会话不得渲染「去设置」按钮（会撞上无权访问的设置页）", guidance.actionLabel)
        assertNull("学生分支产物无动作", notice.actionLabel)
        assertEquals("学生正文为请家长配置版本", OCR_PARENT_SETUP_NOTICE, notice.message)
        assertEquals(OcrSetupGuidance.StudentAskParent, guidance)
    }

    @Test
    fun `学生会话在未注入设置页入口时同样不出现去设置意图`() {
        val guidance = ocrSetupGuidance(role = Role.STUDENT, hasSettingsEntry = false)

        assertNull(guidance.actionLabel)
        assertNull(guidance.notice("识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）").actionLabel)
    }

    @Test
    fun `学生会话的提示文案收敛为请家长配置且不夹带去设置引导`() {
        val notice = ocrNoticeFor(
            role = Role.STUDENT,
            hasSettingsEntry = true,
            baseMessage = "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）",
        )

        assertEquals(OCR_PARENT_SETUP_NOTICE, notice.message)
        assertNull(notice.actionLabel)
        assertTrue("学生文案不得出现指向设置页的引导", OCR_SETTINGS_ENTRY_MARKERS.none { notice.message.contains(it) })
        assertTrue("学生文案不得夹带「去设置」按钮说明", !notice.message.contains(OCR_SETTINGS_ACTION_LABEL))
        assertTrue("学生文案仍须保留待重试事实", notice.message.contains("图片已保存待重试"))
        assertTrue("学生文案须指向家长配置", notice.message.contains(OCR_PARENT_SETUP_HINT))
    }

    @Test
    fun `学生分支只改写设置页片段而保留其余信息`() {
        val summary = "识别成功 2 张，其余仍失败：识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"
        val notice = ocrNoticeFor(role = Role.STUDENT, hasSettingsEntry = true, baseMessage = summary)

        assertTrue("成功计数必须保留，实际：${notice.message}", notice.message.contains("识别成功 2 张"))
        assertTrue("待重试事实必须保留，实际：${notice.message}", notice.message.contains("图片已保存待重试"))
        assertTrue("须指向家长配置，实际：${notice.message}", notice.message.contains(OCR_PARENT_SETUP_HINT))
        assertTrue(
            "不得残留设置页字样，实际：${notice.message}",
            OCR_SETTINGS_ENTRY_MARKERS.none { notice.message.contains(it) },
        )
        assertEquals(
            "汇总前缀与结尾应逐字保留，仅替换引导片段",
            "识别成功 2 张，其余仍失败：识别服务未配置，请让家长先配置识别服务（图片已保存待重试）",
            notice.message,
        )
    }

    // ---- 会话失效分支 ----

    @Test
    fun `会话失效时不额外引导`() {
        val withEntry = ocrSetupGuidance(role = null, hasSettingsEntry = true)
        val withoutEntry = ocrSetupGuidance(role = null, hasSettingsEntry = false)

        assertEquals(OcrSetupGuidance.SessionInvalid, withEntry)
        assertEquals(OcrSetupGuidance.SessionInvalid, withoutEntry)
        assertNull("会话失效不渲染按钮", withEntry.actionLabel)
        assertNull("会话失效提示无动作", withEntry.notice("识别服务未配置").actionLabel)
        assertEquals("识别服务未配置", withEntry.notice("识别服务未配置").message)
    }

    // ---- 分支互斥与正文-按钮一致性 ----

    @Test
    fun `家长与学生分支的引导意图互斥`() {
        Role.entries.forEach { role ->
            val guidance = ocrSetupGuidance(role, hasSettingsEntry = true)
            val notice = guidance.notice("识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）")
            when (role) {
                Role.PARENT -> {
                    assertEquals("去设置", guidance.actionLabel)
                    assertEquals("去设置", notice.actionLabel)
                    assertEquals(
                        "家长正文等于原文（按钮说明不写进正文）",
                        "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）",
                        notice.message,
                    )
                }

                Role.STUDENT -> {
                    assertNull("学生分支永不出现去设置意图", guidance.actionLabel)
                    assertNull(notice.actionLabel)
                    assertEquals("学生正文为请家长配置版本", OCR_PARENT_SETUP_NOTICE, notice.message)
                    assertTrue(
                        "学生文案永不出现设置页字样",
                        OCR_SETTINGS_ENTRY_MARKERS.none { notice.message.contains(it) },
                    )
                }
            }
        }
    }

    @Test
    fun `所有角色下文案与按钮可用性一致`() {
        val base = "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"
        val cases = listOf(
            Triple(Role.PARENT, true, "家长（有按钮）"),
            Triple(Role.PARENT, false, "家长（无按钮）"),
            Triple(Role.STUDENT, true, "学生（有设置页入口）"),
            Triple(Role.STUDENT, false, "学生（无设置页入口）"),
            Triple(noRole, true, "会话失效"),
            Triple(noRole, false, "会话失效（无入口）"),
        )

        cases.forEach { (role, hasEntry, label) ->
            val notice = ocrNoticeFor(role = role, hasSettingsEntry = hasEntry, baseMessage = base)
            assertFalse("$label：正文永不声称可点击（动作只走 actionLabel）", notice.message.contains("点击"))
            if (notice.actionLabel == null) {
                assertFalse("$label：无按钮时正文不得混入按钮文案", notice.message.contains(OCR_SETTINGS_ACTION_LABEL))
            } else {
                assertEquals("$label：有按钮时动作文案为去设置", OCR_SETTINGS_ACTION_LABEL, notice.actionLabel)
            }
        }
    }

    @Test
    fun `有按钮与无按钮两种家长文案均为既有原文`() {
        val base = "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"

        assertEquals(
            "有按钮：正文仍是原文，动作由 actionLabel 承担",
            base,
            ocrNoticeFor(Role.PARENT, hasSettingsEntry = true, baseMessage = base).message,
        )
        assertEquals(
            "无按钮：正文仍是原文，不声称可点击",
            base,
            ocrNoticeFor(Role.PARENT, hasSettingsEntry = false, baseMessage = base).message,
        )
    }

    private companion object Constants {

        /** 角色为 null 的会话失效场景（显式命名，避免 `Role?` 类型推断歧义） */
        val noRole: Role? = null

        /** 指向设置页的文案标记：学生侧提示中一个都不允许出现 */
        val OCR_SETTINGS_ENTRY_MARKERS = listOf("设置页", "去设置", "设置中", "开启并填写厂商参数")
    }
}
