package com.assignmate.app.stats.domain

import com.assignmate.app.core.domain.homework.HomeworkDayStatus

/**
 * 困难度评估等级：依据「当天完成状态 + 实际耗时/预估耗时比值 + 暂停次数」给出的**侧面评估**，
 * 用于提示家长/学生「这项作业可能比较吃力」，不作为作业状态的判定依据。
 *
 * 等级语义：
 * - [SMOOTH] 很顺利：比预估快，暂停也少；
 * - [NORMAL] 正常：耗时与预估相当；
 * - [SLOW] 偏慢：比预估慢较多，或暂停多次；
 * - [CHALLENGING] 明显吃力：比预估慢很多，或频繁暂停；
 * - [UNKNOWN] 暂无数据：当天没有执行痕迹，无法评估。
 */
enum class DifficultyLevel {

    /** 很顺利 */
    SMOOTH,

    /** 正常 */
    NORMAL,

    /** 偏慢 */
    SLOW,

    /** 明显吃力 */
    CHALLENGING,

    /** 暂无数据（当天无执行痕迹） */
    UNKNOWN,
    ;

    /** 用户可读的中文标签 */
    val label: String
        get() = when (this) {
            SMOOTH -> "很顺利"
            NORMAL -> "正常"
            SLOW -> "偏慢"
            CHALLENGING -> "明显吃力"
            UNKNOWN -> "暂无数据"
        }

    /** 是否需要在 UI 上给出「可能需要帮助」的提示（偏慢与明显吃力） */
    val needsAttention: Boolean get() = this == SLOW || this == CHALLENGING
}

/**
 * 困难度评估规则（纯函数，集中可单测）。
 *
 * 评估口径（[StatsConstants] 中的阈值同源）。注意口径为**按天**：完成状态取作业每天详情里
 * 这一天的状态（阶段作业的每一天各自评估），时长与暂停同样取当天值：
 * 1. 当天没有执行痕迹（[hasExecution] 为 false）→ [DifficultyLevel.UNKNOWN]：没有耗时数据可比，
 *    不臆测困难度（页面展示「尚未开始 / 暂无数据」）；「缺卡未做」同样不臆测；
 * 2. 频繁暂停（暂停次数 ≥ [StatsConstants.VERY_FREQUENT_PAUSE_COUNT]）→ [DifficultyLevel.CHALLENGING]，
 *    暂停次数本身就是「频繁走开」的强信号，与耗时长短无关；
 * 3. 耗时比值（实际 / 预估）：
 *    - ≥ [StatsConstants.STRUGGLE_RATIO] → [DifficultyLevel.CHALLENGING]；
 *    - ≥ [StatsConstants.SLOW_RATIO] → [DifficultyLevel.SLOW]；
 *    - ≤ [StatsConstants.FAST_RATIO] → [DifficultyLevel.SMOOTH]（暂停次数 ≥
 *      [StatsConstants.FREQUENT_PAUSE_COUNT] 时降级为 [DifficultyLevel.SLOW]，快但常被打断）；
 *    - 其余 → [DifficultyLevel.NORMAL]；
 * 4. 预估时长缺失时比值不可用：改为「实际耗时 ≥ [StatsConstants.LONG_RUNNING_MINUTES] 分钟 → 偏慢」的兜底口径；
 * 5. 当天未完成的作业（进行中/未开始/缺卡）至少不下结论为「很顺利」：耗时仍在增长，
 *    按比值评估但把 [DifficultyLevel.SMOOTH] 降级为 [DifficultyLevel.NORMAL]。
 *
 * 所有方法均为纯函数：不读时钟、不访问数据库，数据由调用方传入。
 */
object DifficultyAssessor {

    /**
     * 困难度等级评估。
     *
     * @param dayStatus 作业**当天**状态（只有当天已完成才允许给出「很顺利」的正面结论）
     * @param estimatedMinutes 当天预估时长（分钟），未设定为 null
     * @param elapsedMillis 当天实际耗时（毫秒，已扣除暂停）
     * @param pauseCount 当天暂停次数
     * @param hasExecution 当天是否有执行痕迹（false 表示尚无数据，不做评估）
     */
    fun assess(
        dayStatus: HomeworkDayStatus,
        estimatedMinutes: Int?,
        elapsedMillis: Long,
        pauseCount: Int,
        hasExecution: Boolean,
    ): DifficultyLevel {
        if (!hasExecution) {
            return DifficultyLevel.UNKNOWN
        }
        if (pauseCount >= StatsConstants.VERY_FREQUENT_PAUSE_COUNT) {
            return DifficultyLevel.CHALLENGING
        }
        val actualMinutes = StatsCalculations.minutesOf(elapsedMillis)
        val ratio = ratioOf(actualMinutes, estimatedMinutes)
        val level = when {
            ratio == null -> slowByAbsoluteDuration(actualMinutes)
            ratio >= StatsConstants.STRUGGLE_RATIO -> DifficultyLevel.CHALLENGING
            ratio >= StatsConstants.SLOW_RATIO -> DifficultyLevel.SLOW
            ratio <= StatsConstants.FAST_RATIO -> fastLevel(pauseCount)
            else -> DifficultyLevel.NORMAL
        }
        // 当天未完成的作业耗时尚在增长，「很顺利」的结论暂不成立
        if (level == DifficultyLevel.SMOOTH && !StatsCalculations.isDayCompleted(dayStatus)) {
            return DifficultyLevel.NORMAL
        }
        return level
    }

    /**
     * 困难度可读提示（面向学生/家长的口语化文案）。
     *
     * 无执行痕迹时等级为 [DifficultyLevel.UNKNOWN]，返回「尚未开始」类提示而非困难度结论，
     * 避免空数据被误读为「很轻松」。
     */
    fun hintOf(
        level: DifficultyLevel,
        estimatedMinutes: Int?,
        elapsedMillis: Long,
        pauseCount: Int,
    ): String = when (level) {
        DifficultyLevel.UNKNOWN -> HINT_NOT_STARTED
        DifficultyLevel.SMOOTH -> "比预估快一些，做得挺顺利的"
        DifficultyLevel.NORMAL -> normalHint(estimatedMinutes, elapsedMillis)
        DifficultyLevel.SLOW -> slowHint(estimatedMinutes, pauseCount)
        DifficultyLevel.CHALLENGING -> challengingHint(estimatedMinutes, pauseCount)
    }

    /**
     * 耗时比值：实际分钟 / 预估分钟。预估缺失或非正数时返回 null（比值不可用，走兜底口径）。
     * 实际耗时不足 1 分钟按 1 分钟计，避免「几秒钟完成任务」被算成 0 让比值失去意义。
     */
    fun ratioOf(actualMinutes: Long, estimatedMinutes: Int?): Double? {
        val estimated = estimatedMinutes?.takeIf { it > 0 } ?: return null
        val actual = actualMinutes.coerceAtLeast(1L)
        return actual.toDouble() / estimated.toDouble()
    }

    /** 无预估时长时的兜底口径：耗时超过阈值即偏慢，否则视为正常 */
    private fun slowByAbsoluteDuration(actualMinutes: Long): DifficultyLevel =
        if (actualMinutes >= StatsConstants.LONG_RUNNING_MINUTES) {
            DifficultyLevel.SLOW
        } else {
            DifficultyLevel.NORMAL
        }

    /**
     * 「比预估快」时：暂停多次则说明反复被打断（可能只是坐不住而非不会做），
     * 不足以判定为「很顺利」，降级为偏慢提醒。
     */
    private fun fastLevel(pauseCount: Int): DifficultyLevel =
        if (pauseCount >= StatsConstants.FREQUENT_PAUSE_COUNT) {
            DifficultyLevel.SLOW
        } else {
            DifficultyLevel.SMOOTH
        }

    private fun normalHint(estimatedMinutes: Int?, elapsedMillis: Long): String =
        if (estimatedMinutes != null && estimatedMinutes > 0) {
            "用时和预估差不多（约 ${StatsCalculations.minutesOf(elapsedMillis)} 分钟）"
        } else {
            "用时正常（约 ${StatsCalculations.minutesOf(elapsedMillis)} 分钟）"
        }

    private fun slowHint(estimatedMinutes: Int?, pauseCount: Int): String = when {
        pauseCount >= StatsConstants.FREQUENT_PAUSE_COUNT && estimatedMinutes != null ->
            "比预估慢较多，暂停 $pauseCount 次，可能有点吃力，可以陪着一起看看"

        pauseCount >= StatsConstants.FREQUENT_PAUSE_COUNT ->
            "暂停 $pauseCount 次，可能有点吃力，可以陪着一起看看"

        estimatedMinutes != null ->
            "比预估慢较多，可能需要多留一点时间"

        else ->
            "花费时间偏长，可能需要多留一点时间"
    }

    private fun challengingHint(estimatedMinutes: Int?, pauseCount: Int): String = when {
        estimatedMinutes != null && pauseCount >= StatsConstants.FREQUENT_PAUSE_COUNT ->
            "比预估慢很多，且暂停 $pauseCount 次，可能比较吃力，建议拆小步骤完成"

        estimatedMinutes != null ->
            "比预估慢很多，可能比较吃力，建议拆小步骤完成"

        else ->
            "用时很长且反复暂停，可能比较吃力，建议拆小步骤完成"
    }

    /** 当天无执行痕迹时的提示文案（页面「尚未开始/暂无数据」口径的唯一出口） */
    const val HINT_NOT_STARTED = "还没有开始计时，暂时没有耗时数据"
}
