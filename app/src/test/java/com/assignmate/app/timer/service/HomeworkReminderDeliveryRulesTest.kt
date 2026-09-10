package com.assignmate.app.timer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 到点提醒投递规则单测（纯函数）：核对结果 → 投递动作 → 通知文案。
 *
 * 覆盖评审要求的加固口径：
 * - 核对通过才点名作业内容（且内容取自库内）；
 * - 作业已删除/已完成（Stale）→ 静默，不发通知也不震动；
 * - 无法核对（Unknown）→ 中性文案「去作业清单看看这一项」，绝不点名可能失效的作业；
 * - 点名内容为空时同样退化为中性文案。
 */
class HomeworkReminderDeliveryRulesTest {

    @Test
    fun `核对通过时点名库内作业内容`() {
        val delivery = HomeworkReminderDeliveryRules.decide(
            ReminderVerification.ConfirmedPending("语文生字"),
        )

        assertEquals(ReminderDelivery.Named("语文生字"), delivery)
    }

    @Test
    fun `作业已删除或已完成时静默不打扰`() {
        val delivery = HomeworkReminderDeliveryRules.decide(ReminderVerification.Stale)

        assertEquals(ReminderDelivery.Silent, delivery)
        assertNull("静默投递没有文案", HomeworkReminderDeliveryRules.textsOf(delivery))
    }

    @Test
    fun `无法核对时退化为中性文案`() {
        val delivery = HomeworkReminderDeliveryRules.decide(ReminderVerification.Unknown)

        assertEquals(ReminderDelivery.Neutral, delivery)
        assertEquals(
            HomeworkReminderDeliveryRules.NEUTRAL_TEXTS,
            HomeworkReminderDeliveryRules.textsOf(delivery),
        )
    }

    @Test
    fun `点名内容为空白时退化为中性文案`() {
        val delivery = HomeworkReminderDeliveryRules.decide(
            ReminderVerification.ConfirmedPending("   "),
        )

        assertEquals(
            HomeworkReminderDeliveryRules.NEUTRAL_TEXTS,
            HomeworkReminderDeliveryRules.textsOf(delivery),
        )
    }

    @Test
    fun `中性文案不点名作业且引导去清单查看`() {
        val texts = HomeworkReminderDeliveryRules.NEUTRAL_TEXTS

        assertTrue(texts.title.isNotBlank())
        assertTrue(texts.body.isNotBlank())
        assertTrue("中性文案应引导去清单核对", texts.body.contains("清单"))
        assertFalse("中性文案不应包含具体作业内容", texts.body.contains("："))
    }

    @Test
    fun `点名文案包含作业内容与开始计时引导`() {
        val texts = HomeworkReminderDeliveryRules.textsOf(ReminderDelivery.Named("数学口算"))

        assertEquals("作业时间到啦：数学口算", texts?.title)
        assertTrue(texts!!.body.contains("计时"))
    }

    @Test
    fun `点名文案会去掉作业内容首尾空白`() {
        val texts = HomeworkReminderDeliveryRules.textsOf(ReminderDelivery.Named("  数学口算  "))

        assertEquals("作业时间到啦：数学口算", texts?.title)
    }
}
