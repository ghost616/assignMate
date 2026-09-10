package com.assignmate.app.timer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 到点提醒投递规则的补充单测（在 HomeworkReminderDeliveryRulesTest 之外）：
 * 完整决策表（三种核对结果 × 三种投递动作一一对应）、每种投递的文案契约（含空串内容）、
 * 中性文案与点名文案的字段级口径、以及「静默」与「中性」是可区分的两种处置。
 */
class HomeworkReminderDeliveryRulesExtraTest {

    @Test
    fun `三种核对结果与三种投递动作一一对应`() {
        assertEquals(
            ReminderDelivery.Named("语文生字"),
            HomeworkReminderDeliveryRules.decide(ReminderVerification.ConfirmedPending("语文生字")),
        )
        assertEquals(ReminderDelivery.Silent, HomeworkReminderDeliveryRules.decide(ReminderVerification.Stale))
        assertEquals(ReminderDelivery.Neutral, HomeworkReminderDeliveryRules.decide(ReminderVerification.Unknown))
    }

    @Test
    fun `静默与中性是两种不同处置`() {
        assertNotEquals(
            HomeworkReminderDeliveryRules.decide(ReminderVerification.Stale),
            HomeworkReminderDeliveryRules.decide(ReminderVerification.Unknown),
        )
        assertNull(HomeworkReminderDeliveryRules.textsOf(ReminderDelivery.Silent))
        assertNotNull(HomeworkReminderDeliveryRules.textsOf(ReminderDelivery.Neutral))
    }

    @Test
    fun `空串内容同样退化为中性文案`() {
        assertEquals(
            HomeworkReminderDeliveryRules.NEUTRAL_TEXTS,
            HomeworkReminderDeliveryRules.textsOf(ReminderDelivery.Named("")),
        )
        assertEquals(
            HomeworkReminderDeliveryRules.NEUTRAL_TEXTS,
            HomeworkReminderDeliveryRules.textsOf(ReminderDelivery.Named("\n\t ")),
        )
    }

    @Test
    fun `中性文案为固定契约且不点名任何作业`() {
        val texts = HomeworkReminderDeliveryRules.NEUTRAL_TEXTS

        assertEquals("作业时间到啦", texts.title)
        assertFalse("标题不应出现点名分隔符", texts.title.contains("："))
        assertTrue("正文应引导去清单核对", texts.body.contains("清单"))
        assertTrue("正文应给出下一步动作", texts.body.contains("计时"))
    }

    @Test
    fun `点名文案为固定契约且含内容与计时引导`() {
        val texts = HomeworkReminderDeliveryRules.textsOf(ReminderDelivery.Named("数学口算"))

        assertNotNull(texts)
        assertEquals("作业时间到啦：数学口算", texts!!.title)
        assertEquals("点开开始计时，一起专心完成这一项吧！", texts.body)

        val neutral = HomeworkReminderDeliveryRules.textsOf(
            HomeworkReminderDeliveryRules.decide(ReminderVerification.Unknown),
        )
        assertNotEquals("中性文案不应与点名文案相同", neutral, texts)
    }

    @Test
    fun `点名只去除内容首尾空白`() {
        assertEquals(
            "作业时间到啦：数学口算",
            HomeworkReminderDeliveryRules.textsOf(ReminderDelivery.Named("\t 数学口算 \n"))?.title,
        )
        // 内容内部空白按原样保留（需求只要求去首尾空白），至少不得丢失内容本身
        val inner = HomeworkReminderDeliveryRules.textsOf(ReminderDelivery.Named("数学 口算"))
        assertEquals("作业时间到啦：数学 口算", inner?.title)
    }

    @Test
    fun `核对通过返回的库内内容原样进入点名文案`() {
        val verification = ReminderVerification.ConfirmedPending("  语文第 3 课生字  ")
        val delivery = HomeworkReminderDeliveryRules.decide(verification)

        assertEquals(ReminderDelivery.Named("  语文第 3 课生字  "), delivery)
        assertEquals(
            "作业时间到啦：语文第 3 课生字",
            HomeworkReminderDeliveryRules.textsOf(delivery)?.title,
        )
    }
}
