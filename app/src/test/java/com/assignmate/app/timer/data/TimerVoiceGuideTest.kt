package com.assignmate.app.timer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音播报门面单测（真实门面 + TTS 引擎替身）：
 * 文案清洗后交给引擎、空白文案跳过、引擎异常时静默降级（不外泄异常、不阻塞调用方）、停止播报透传。
 */
class TimerVoiceGuideTest {

    @Test
    fun `播报前清洗文案并交给引擎`() {
        val player = FakeTextToSpeechPlayer()
        val guide = TtsTimerVoiceGuide(player)

        guide.speak("  休息结束啦 \n 下一项是数学口算  ")

        assertEquals(listOf("休息结束啦 下一项是数学口算"), player.spoken)
    }

    @Test
    fun `空白文案不播报`() {
        val player = FakeTextToSpeechPlayer()
        val guide = TtsTimerVoiceGuide(player)

        guide.speak("   ")
        guide.speak("")

        assertTrue(player.spoken.isEmpty())
    }

    @Test
    fun `引擎异常时静默降级不抛出`() {
        val player = FakeTextToSpeechPlayer(failing = true)
        val guide = TtsTimerVoiceGuide(player)

        // 引擎不可用（如语音包缺失/系统不支持 TTS）：调用方不应感知异常
        guide.speak("全部完成啦")
        guide.stop()

        assertTrue(player.spoken.isEmpty())
        assertEquals(1, player.stopCount)
    }

    @Test
    fun `停止播报透传给引擎`() {
        val player = FakeTextToSpeechPlayer()
        val guide = TtsTimerVoiceGuide(player)

        guide.stop()

        assertEquals(1, player.stopCount)
    }

    @Test
    fun `连续播报按调用顺序全部透传`() {
        val player = FakeTextToSpeechPlayer()
        val guide = TtsTimerVoiceGuide(player)

        guide.speak("开始做语文，加油！")
        guide.speak("计时先暂停，记得回来继续哦。")

        assertEquals(listOf("开始做语文，加油！", "计时先暂停，记得回来继续哦。"), player.spoken)
    }
}
