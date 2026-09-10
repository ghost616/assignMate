package com.assignmate.app.timer.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈语料池单测：池非空且文案有效、鼓励语模板占位符齐备、
 * 随机取语范围收敛在池内、同种子可复现、渲染无占位符残留。
 */
class TimerFeedbackTest {

    @Test
    fun `表扬语池非空且每条文案都有效`() {
        assertTrue(TimerFeedback.PRAISE_TEXTS.isNotEmpty())
        TimerFeedback.PRAISE_TEXTS.forEach { text ->
            assertTrue("表扬语不应为空白: '$text'", text.isNotBlank())
        }
    }

    @Test
    fun `鼓励语池非空且每条都含完成数量占位符`() {
        assertTrue(TimerFeedback.ENCOURAGE_TEXTS.isNotEmpty())
        TimerFeedback.ENCOURAGE_TEXTS.forEach { text ->
            assertTrue("鼓励语应含已完成占位符: '$text'", text.contains(TimerFeedback.PLACEHOLDER_COMPLETED))
            assertTrue("鼓励语应含总数占位符: '$text'", text.contains(TimerFeedback.PLACEHOLDER_TOTAL))
        }
    }

    @Test
    fun `随机表扬语始终落在语料池内`() {
        val random = Random(2024)
        repeat(50) {
            assertTrue(TimerFeedback.praiseText(random) in TimerFeedback.PRAISE_TEXTS)
        }
    }

    @Test
    fun `随机鼓励语渲染出完成数量且无占位符残留`() {
        val random = Random(7)
        repeat(50) {
            val text = TimerFeedback.encouragementText(completedCount = 2, totalCount = 5, random = random)
            assertFalse(text.contains(TimerFeedback.PLACEHOLDER_COMPLETED))
            assertFalse(text.contains(TimerFeedback.PLACEHOLDER_TOTAL))
            assertTrue("鼓励语应包含已完成数量: '$text'", text.contains("2"))
            assertTrue("鼓励语应包含总数: '$text'", text.contains("5"))
        }
    }

    @Test
    fun `同一随机种子取语结果可复现`() {
        assertEquals(TimerFeedback.praiseText(Random(42)), TimerFeedback.praiseText(Random(42)))
        assertEquals(
            TimerFeedback.encouragementText(1, 3, Random(42)),
            TimerFeedback.encouragementText(1, 3, Random(42)),
        )
    }

    @Test
    fun `模板渲染为纯字符串替换`() {
        assertEquals(
            "已完成 3/7 项",
            TimerFeedback.render("已完成 {completed}/{total} 项", completedCount = 3, totalCount = 7),
        )
    }
}
