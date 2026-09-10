package com.assignmate.app.timer.domain

/**
 * 语音引导文案组装（纯函数、集中可单测）。
 *
 * 设计约定：
 * - 本对象只负责「把领域文案拼成适合朗读的整句」，不接触 TTS 引擎与开关（播放与降级见
 *   timer.data.TimerVoiceGuide，开关见 timer.data.TimerVoiceSettings）；
 * - 随机语料仍来自 [TimerFeedback]（表扬语/鼓励语池），本对象负责把语料与数量、下一项内容拼装；
 * - [sanitize] 供播报前清洗（去首尾空白、合并换行与连续空白），避免 TTS 因换行产生异常停顿；
 *   清洗后为空串时调用方应跳过播报（空文案交给引擎无意义）。
 */
object TimerSpeechTexts {

    /** 白名单式清洗：连续空白（含换行/制表）合并为单个空格 */
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** 播报前清洗文案：去首尾空白并把连续空白合并为单个空格 */
    fun sanitize(text: String): String = text.trim().replace(WHITESPACE_REGEX, " ")

    /**
     * 休息结束的下一项引导：「休息结束啦，下一项是 XXX，我们开始吧！」
     *
     * @param content 下一项作业内容（为空时退化为不点名内容的一般引导）
     */
    fun restFinishedNextItem(content: String): String {
        val trimmed = sanitize(content)
        return if (trimmed.isEmpty()) {
            "休息结束啦，我们开始下一项作业吧！"
        } else {
            "休息结束啦，下一项是$trimmed，我们开始吧！"
        }
    }

    /** 休息结束但清单已全部完成：播报表扬语 + 完成数量 */
    fun restFinishedAllCompleted(
        praise: String,
        completedCount: Int,
        totalCount: Int,
    ): String = allCompleted(praise, completedCount, totalCount)

    /** 全部完成的播报：表扬语 + 完成数量（表扬语来自 [TimerFeedback.PRAISE_TEXTS]） */
    fun allCompleted(
        praise: String,
        completedCount: Int,
        totalCount: Int,
    ): String = "${sanitize(praise)}今天完成了 $completedCount/$totalCount 项作业。".trim()

    /** 超时未完成的播报：鼓励语（已含完成数量文案）+ 收尾安抚 */
    fun overdueEncouragement(encouragement: String): String {
        val trimmed = sanitize(encouragement)
        return if (trimmed.isEmpty()) {
            "时间有点久啦，慢慢来，做完这一项就可以休息了。"
        } else {
            "$trimmed 做完这一项就可以休息啦。"
        }
    }

    /** 开始计时的播报 */
    fun startTicking(content: String): String {
        val trimmed = sanitize(content)
        return if (trimmed.isEmpty()) "开始计时啦，专心做这一项吧！" else "开始做$trimmed，加油！"
    }

    /** 暂停（有事走开）的播报 */
    fun paused(): String = "计时先暂停，记得回来继续哦。"

    /** 恢复（我回来啦）的播报 */
    fun resumed(): String = "欢迎回来，我们接着做吧！"

    /** 完成一项作业的播报 */
    fun completed(content: String): String {
        val trimmed = sanitize(content)
        return if (trimmed.isEmpty()) "这一项完成啦，真棒！" else "$trimmed 完成啦，真棒！"
    }
}
