package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「收敛单层化」反向探针（离朱补强，对应测试说明「建议复测重点」第 6 条与边界覆盖）。
 *
 * 上一轮的阻断项是「按角色收敛被同时放在 ViewModel 与 UI 两层」：UI 把「去设置」的按钮说明
 * 写进正文，ViewModel 再收敛一次就出现重复后缀。本轮的契约是**文案与动作只在
 * [ocrNoticeFor] 一次产出、按钮说明不写进正文**。
 *
 * 因此本探针不重复既有用例的断言，而是补三层结构性覆盖：
 * A. **反向幂等**：把渲染产物**再次喂回**同一渲染入口（[OcrSetupGuidance.notice]），
 *    对 role × hasSettingsEntry 全部 6 种组合验证二次收敛是**不动点**
 *    ——只要正文不再承载按钮说明，重复收敛就不可能再变形（这是上一轮漏测的反向路径）；
 * B. **逐字一致性边界**：家长正文与底层原文**逐字相等**（含重试汇总「识别成功 N 张」），
 *    有无按钮只影响 [OcrNotice.actionLabel]、不影响一个字符；
 * C. **边界输入**：空文案、非未配置文案（不得被改写）、同一片段多次出现（须全部替换）、
 *    学生侧禁用词全集（含「点击」与「开启并填写厂商参数」，不只是「设置页」）。
 *
 * 说明：Compose 组合层渲染无法在 JVM 断言（本仓库无设备、未引入 Robolectric），
 * 故断言对象为被 Route 直接消费的纯函数产物；像素观感仍需真机验证。
 */
class HomeworkEntryOcrNoticeConvergenceProbeTest {

    /** 未配置时 Handler 给出的底层原文（单条失败） */
    private val rawNotConfigured =
        "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"

    /** 未配置时 Handler 给出的底层原文（重试「部分成功」汇总：计数信息必须原样保留） */
    private val rawRetrySummary =
        "识别成功 1 张，其余仍失败：识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"

    /** 学生侧任何用户可见文案中都不允许出现的字样（含「点击」，不得声称可点击） */
    private val studentForbiddenMarkers =
        listOf("设置页", "去设置", "设置中", "开启并填写厂商参数", "点击")

    private val entryFlags = listOf(true, false)

    // ---- A. 反向幂等：二次收敛必须是不动点 ----

    @Test
    fun `所有角色与入口组合下二次收敛都是不动点`() {
        listOf<Role?>(Role.PARENT, Role.STUDENT, null).forEach { role ->
            entryFlags.forEach { hasEntry ->
                val guidance = ocrSetupGuidance(role, hasSettingsEntry = hasEntry)
                val first = guidance.notice(rawRetrySummary)
                val second = guidance.notice(first.message)

                val label = "role=$role hasSettingsEntry=$hasEntry"
                assertEquals("$label：二次收敛不得改变正文", first.message, second.message)
                assertEquals("$label：二次收敛不得改变动作", first.actionLabel, second.actionLabel)
                assertFalse("$label：正文永不声称可点击，实际：${second.message}", second.message.contains("点击"))
                assertFalse(
                    "$label：正文永不夹带按钮文案，实际：${second.message}",
                    second.message.contains(OCR_SETTINGS_ACTION_LABEL),
                )
            }
        }
    }

    @Test
    fun `家长侧收敛产物再喂回渲染入口不产生任何重复后缀`() {
        val guidance = ocrSetupGuidance(Role.PARENT, hasSettingsEntry = true)
        val once = guidance.notice(rawRetrySummary).message
        val twice = guidance.notice(once).message
        val thrice = guidance.notice(twice).message

        assertEquals("二次收敛正文必须逐字不变", once, twice)
        assertEquals("三次收敛正文必须逐字不变（收敛是幂等的）", once, thrice)
        assertEquals("待重试后缀只出现一次", 1, countOf(once, "（图片已保存待重试）"))
        assertEquals("成功计数不得被重复书写", 1, countOf(once, "识别成功 1 张"))
        assertEquals("按钮说明不得进入正文", 0, countOf(once, "点击"))
    }

    // ---- B. 逐字一致性：家长正文 == 底层原文 ----

    @Test
    fun `家长侧正文逐字等于底层汇总原文且有无按钮不改变一个字符`() {
        val withButton = ocrNoticeFor(Role.PARENT, hasSettingsEntry = true, baseMessage = rawRetrySummary)
        val withoutButton = ocrNoticeFor(Role.PARENT, hasSettingsEntry = false, baseMessage = rawRetrySummary)

        assertEquals("有按钮时正文必须逐字等于底层原文", rawRetrySummary, withButton.message)
        assertEquals("无按钮时正文必须逐字等于底层原文", rawRetrySummary, withoutButton.message)
        assertEquals("有无按钮只影响动作、不影响正文", withButton.message, withoutButton.message)
        assertEquals("正文长度不得被追加任何字符", rawRetrySummary.length, withButton.message.length)
        assertEquals("有按钮时动作 = 去设置", OCR_SETTINGS_ACTION_LABEL, withButton.actionLabel)
        assertNull("未注入回调时无按钮", withoutButton.actionLabel)
    }

    @Test
    fun `会话失效时正文逐字沿用底层原文且无动作`() {
        val notice = ocrNoticeFor(role = null, hasSettingsEntry = true, baseMessage = rawRetrySummary)

        assertEquals("role=null 沿用既有原文、不追加角色引导", rawRetrySummary, notice.message)
        assertNull("role=null 不渲染按钮", notice.actionLabel)
        assertEquals("成功计数原样保留", 1, countOf(notice.message, "识别成功 1 张"))
    }

    // ---- C. 边界输入 ----

    @Test
    fun `空文案与非未配置文案在所有角色下原样返回`() {
        val passthrough = "需要联网才能识别图片，已保存待重试"

        listOf("", passthrough).forEach { base ->
            listOf<Role?>(Role.PARENT, Role.STUDENT, null).forEach { role ->
                entryFlags.forEach { hasEntry ->
                    val notice = ocrNoticeFor(role, hasEntry, base)
                    assertEquals(
                        "非未配置文案不得被任何角色改写（role=$role hasEntry=$hasEntry base=「$base」）",
                        base,
                        notice.message,
                    )
                }
            }
            assertNull(
                "学生侧永无动作",
                ocrNoticeFor(Role.STUDENT, hasSettingsEntry = true, baseMessage = base).actionLabel,
            )
            assertNull(
                "会话失效永无动作",
                ocrNoticeFor(role = null, hasSettingsEntry = true, baseMessage = base).actionLabel,
            )
        }
    }

    @Test
    fun `学生侧同一片段多次出现时全部改写且计数信息不受影响`() {
        val doubled = "$rawNotConfigured；另：$rawNotConfigured"
        val notice = ocrNoticeFor(Role.STUDENT, hasSettingsEntry = true, baseMessage = doubled)

        assertEquals("底层设置页片段不得残留", 0, countOf(notice.message, "请到设置页开启并填写厂商参数"))
        assertEquals("每处片段都应改写为请家长配置", 2, countOf(notice.message, "请让家长先配置识别服务"))
        assertEquals("每处「图片已保存待重试」都必须在原位保留", 2, countOf(notice.message, "（图片已保存待重试）"))
        assertNull("学生侧永不携带动作", notice.actionLabel)
    }

    @Test
    fun `学生侧禁用词全集在两条文案样本下均不出现`() {
        listOf(rawNotConfigured, rawRetrySummary).forEach { base ->
            val message = ocrNoticeFor(Role.STUDENT, hasSettingsEntry = true, baseMessage = base).message
            studentForbiddenMarkers.forEach { marker ->
                assertFalse("学生文案不得含「$marker」，实际：$message", message.contains(marker))
            }
            assertEquals("学生文案必须恰好指向家长配置", 1, countOf(message, OCR_PARENT_SETUP_HINT))
        }
    }

    private fun countOf(text: String, marker: String): Int =
        if (marker.isEmpty()) 0 else Regex(Regex.escape(marker)).findAll(text).count()
}