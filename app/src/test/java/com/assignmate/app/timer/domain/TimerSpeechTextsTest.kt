package com.assignmate.app.timer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音文案组装单测：清洗（去空白/合并非空）、休息结束下一项引导、全部完成表扬播报、
 * 超时鼓励播报、开始/暂停/恢复/完成各场景文案，以及内容缺失时的兜底文案。
 */
class TimerSpeechTextsTest {

    @Test
    fun `清洗文案去首尾空白并合并连续空白`() {
        assertEquals("语文 第3课", TimerSpeechTexts.sanitize("  语文 \n 第3课  "))
        assertEquals("a b c", TimerSpeechTexts.sanitize("a\t\tb\n\nc"))
    }

    @Test
    fun `纯空白文案清洗为空串以便调用方跳过播报`() {
        assertEquals("", TimerSpeechTexts.sanitize("   \n\t "))
        assertEquals("", TimerSpeechTexts.sanitize(""))
    }

    @Test
    fun `休息结束引导播报下一项作业内容`() {
        val text = TimerSpeechTexts.restFinishedNextItem("数学口算")

        assertTrue(text.contains("休息结束"))
        assertTrue(text.contains("数学口算"))
    }

    @Test
    fun `休息结束引导会清洗作业内容中的换行`() {
        val text = TimerSpeechTexts.restFinishedNextItem("数学\n口算")

        assertTrue(text.contains("数学 口算"))
        assertFalse(text.contains("\n"))
    }

    @Test
    fun `下一项内容为空时退化为一般引导`() {
        val text = TimerSpeechTexts.restFinishedNextItem("   ")

        assertTrue(text.contains("休息结束"))
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `全部完成播报表扬语与完成数量`() {
        val text = TimerSpeechTexts.allCompleted(
            praise = "太棒啦！",
            completedCount = 3,
            totalCount = 5,
        )

        assertTrue(text.contains("太棒啦！"))
        assertTrue(text.contains("3"))
        assertTrue(text.contains("5"))
    }

    @Test
    fun `休息结束时若已全部完成则播报表扬播报`() {
        assertEquals(
            TimerSpeechTexts.allCompleted("真厉害！", 2, 2),
            TimerSpeechTexts.restFinishedAllCompleted("真厉害！", 2, 2),
        )
    }

    @Test
    fun `超时鼓励播报包含鼓励语与收尾安抚`() {
        val text = TimerSpeechTexts.overdueEncouragement("已经完成 2/5 项啦")

        assertTrue(text.contains("已经完成 2/5 项啦"))
        assertTrue(text.contains("休息"))
    }

    @Test
    fun `鼓励语为空时给出兜底安抚文案`() {
        val text = TimerSpeechTexts.overdueEncouragement("   ")

        assertTrue(text.isNotBlank())
        assertTrue(text.contains("休息"))
    }

    @Test
    fun `开始暂停恢复完成的播报文案均有效且含作业内容`() {
        assertTrue(TimerSpeechTexts.startTicking("语文生字").contains("语文生字"))
        assertTrue(TimerSpeechTexts.completed("语文生字").contains("语文生字"))
        assertTrue(TimerSpeechTexts.paused().isNotBlank())
        assertTrue(TimerSpeechTexts.resumed().isNotBlank())
    }

    @Test
    fun `作业内容为空时开始与完成播报仍有兜底文案`() {
        assertTrue(TimerSpeechTexts.startTicking("").isNotBlank())
        assertTrue(TimerSpeechTexts.completed("").isNotBlank())
    }
}
