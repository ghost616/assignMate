package com.assignmate.app.timer.domain

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音文案组装的补充边界单测（在 TimerSpeechTextsTest 之外）：
 * 固定文案精确断言、换行/制表/回车清洗、全角空格等非 ASCII 空白的处理口径、
 * 表扬语与鼓励语接入语料池后的占位符与数量校验。
 */
class TimerSpeechTextsEdgeCaseTest {

    @Test
    fun `暂停与恢复的播报文案与实现口径一致`() {
        assertEquals("计时先暂停，记得回来继续哦。", TimerSpeechTexts.paused())
        assertEquals("欢迎回来，我们接着做吧！", TimerSpeechTexts.resumed())
    }

    @Test
    fun `清洗回车与制表等连续空白`() {
        assertEquals("a b", TimerSpeechTexts.sanitize("a\r\n\tb"))
        assertEquals("语文 生字 数学", TimerSpeechTexts.sanitize("语文\t \n生字\r\n数学"))
    }

    @Test
    fun `非 ASCII 空白（全角空格）不会破坏清洗结果的可用性`() {
        // 规格只要求合并连续空白（含换行/制表）；此处记录当前口径：全角空格不被折叠，
        // 但首尾空白（含全角）一律去除，且不会残留连续 ASCII 空白
        val text = TimerSpeechTexts.sanitize("　语文　 生字　")

        assertEquals("语文", text.take(2))
        assertTrue(text.endsWith("生字"))
        assertFalse("不应残留连续 ASCII 空白", text.contains("  "))
        assertTrue(text.contains("语文"))
        assertTrue(text.contains("生字"))
    }

    @Test
    fun `表扬语含换行时先清洗再拼接完成数量`() {
        val text = TimerSpeechTexts.allCompleted("太棒\n啦", completedCount = 2, totalCount = 3)

        assertFalse("不应残留换行", text.contains("\n"))
        assertTrue(text.contains("太棒 啦"))
        assertTrue(text.contains("今天完成了 2/3 项作业。"))
    }

    @Test
    fun `表扬语为空白时不产生前导空白且仍播报完成数量`() {
        assertEquals(
            "今天完成了 1/1 项作业。",
            TimerSpeechTexts.allCompleted("   ", completedCount = 1, totalCount = 1),
        )
    }

    @Test
    fun `全部完成播报接入语料池后无占位符残留且含数量`() {
        val text = TimerSpeechTexts.allCompleted(
            praise = TimerFeedback.praiseText(Random(1)),
            completedCount = 4,
            totalCount = 4,
        )

        assertFalse(text.contains("{"))
        assertTrue(TimerFeedback.PRAISE_TEXTS.any { text.startsWith(it) })
        assertTrue(text.contains("今天完成了 4/4 项作业。"))
    }

    @Test
    fun `超时鼓励播报接入鼓励语池后无占位符残留且含数量`() {
        val text = TimerSpeechTexts.overdueEncouragement(
            TimerFeedback.encouragementText(completedCount = 2, totalCount = 5, random = Random(7)),
        )

        assertFalse(text.contains("{"))
        assertTrue(text.contains("2"))
        assertTrue(text.contains("5"))
        assertTrue(text.contains("休息"))
    }

    @Test
    fun `超时鼓励语含换行时先清洗再拼接收尾安抚`() {
        val text = TimerSpeechTexts.overdueEncouragement("已完成 1/2\n项啦")

        assertFalse(text.contains("\n"))
        assertTrue(text.contains("已完成 1/2 项啦"))
        assertTrue(text.contains("做完这一项就可以休息啦。"))
    }

    @Test
    fun `休息结束下一项引导清洗制表符`() {
        val text = TimerSpeechTexts.restFinishedNextItem("数学\t口算")

        assertTrue(text.contains("数学 口算"))
        assertFalse(text.contains("\t"))
    }

    @Test
    fun `开始与完成播报对纯空白内容退化为兜底文案`() {
        assertEquals("开始计时啦，专心做这一项吧！", TimerSpeechTexts.startTicking("\n\t "))
        assertEquals("这一项完成啦，真棒！", TimerSpeechTexts.completed("  \n"))
    }

    @Test
    fun `开始与完成播报清洗内容中的制表符`() {
        assertEquals("开始做语文 生字，加油！", TimerSpeechTexts.startTicking("语文\t生字"))
        assertEquals("语文 生字 完成啦，真棒！", TimerSpeechTexts.completed("语文\n生字"))
    }
}
