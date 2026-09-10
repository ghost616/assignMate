package com.assignmate.app.timer.domain

import kotlin.random.Random

/**
 * 计时过程中的反馈语料池与随机取语（纯函数，随机源可注入便于测试）。
 *
 * 两类语料（分别对应两种反馈场景，均由 timer-B 计划接入 TTS 播报，本模块只提供文本）：
 * - [PRAISE_TEXTS]：全部作业完成时的随机表扬语；
 * - [ENCOURAGE_TEXTS]：超时仍未完成时的随机鼓励语，模板含 {completed}/{total} 占位符，
 *   渲染时填入当日已完成数量与总数。
 *
 * 约定：语料池必须非空（单测保证），取语一律经 [Random] 注入，避免不可测的隐式随机。
 */
object TimerFeedback {

    /** 完成情况的占位符（渲染时替换为数字文案） */
    const val PLACEHOLDER_COMPLETED = "{completed}"

    /** 任务总数的占位符 */
    const val PLACEHOLDER_TOTAL = "{total}"

    /** 全部完成时的表扬语池（随机取一条） */
    val PRAISE_TEXTS: List<String> = listOf(
        "太棒啦！今天的作业全部完成，你真厉害！",
        "全部搞定！你今天的专注力满分，给自己一个大大的赞！",
        "哇，一项不剩全做完啦！你真是学习小能手！",
        "今天的任务全部完成，辛苦啦，好好休息一下吧！",
        "全部完成！坚持到底的你，超级了不起！",
    )

    /** 超时仍未完成时的鼓励语池（模板含已完成数量文案） */
    val ENCOURAGE_TEXTS: List<String> = listOf(
        "已经完成 {completed}/{total} 项啦，慢慢来，我们继续加油！",
        "现在完成 {completed}/{total} 项，剩下的也不多，再坚持一下下！",
        "别着急，今天已经拿下 {completed}/{total} 项，下一小步就成功！",
        "还有力气吗？{completed}/{total} 项已完成，我们一起把剩下的做完！",
        "时间有点久啦，不过你已完成 {completed}/{total} 项，很棒，继续加油！",
    )

    /** 随机取一条表扬语（[random] 可注入固定种子便于测试） */
    fun praiseText(random: Random = Random.Default): String =
        PRAISE_TEXTS[random.nextInt(PRAISE_TEXTS.size)]

    /**
     * 随机取一条鼓励语并渲染完成数量文案。
     *
     * @param completedCount 当日已完成作业数量
     * @param totalCount 当日作业总数
     */
    fun encouragementText(
        completedCount: Int,
        totalCount: Int,
        random: Random = Random.Default,
    ): String = render(
        template = ENCOURAGE_TEXTS[random.nextInt(ENCOURAGE_TEXTS.size)],
        completedCount = completedCount,
        totalCount = totalCount,
    )

    /** 填充模板中的完成数量占位符（纯字符串替换，便于单测断言） */
    fun render(template: String, completedCount: Int, totalCount: Int): String =
        template
            .replace(PLACEHOLDER_COMPLETED, completedCount.toString())
            .replace(PLACEHOLDER_TOTAL, totalCount.toString())
}
