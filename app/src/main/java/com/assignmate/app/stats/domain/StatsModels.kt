package com.assignmate.app.stats.domain

import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus

/**
 * stats 模块领域模型集合：当日盘点、单项详情、历史查询结果，以及盘点聚合结果容器。
 *
 * 设计约定：
 * - 本层为**纯数据 + 纯计算**，不读时钟、不访问数据库；时间口径（当日区间、参考时刻）
 *   一律由调用方（仓库/ViewModel）经可注入 Clock 与业务时区计算后传入；
 * - 完成率 [DaySummary.completionRate] 为 0..1 的比例值，百分数文案由
 *   [StatsCalculations.percentText] 统一生成，避免各页面各自格式化；
 * - 失败/越权不抛异常，统一由仓库层收敛为 [StatsAccess] 与 [StatsResult]。
 */

/**
 * 当日盘点结果（stats 的核心输出，供当日盘点页展示）。
 *
 * 日期口径：本模型描述**某一天**（业务时区自然日）的完成情况；
 * [epochDay] 为 UTC 纪元日（`LocalDate.toEpochDay()`），便于历史查询逐日渲染与测试断言。
 *
 * 分母口径（与仓库 KDoc 中的「当日纳入口径」一致）：
 * [totalCount] = 当日有执行/完成记录的作业数（截至 [StatsCalculations.summarizeDay] 的参考时刻）；
 * [completedCount] = 其中状态已完成的数量；两者为 0 时 [completionRate] 为 0。
 */
data class DaySummary(
    /** 盘点所属自然日（UTC 纪元日，见 UTC 时区换算口径） */
    val epochDay: Long,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    /** 当日清单（按优先级升序）：供盘点页直接展示与进入单项详情 */
    val items: List<HomeworkItem> = emptyList(),
    /**
     * 当日暂停次数：为**落在当日窗口内**的暂停段条数（跨天暂停按窗口裁剪，
     * 未结束的那一段同样计入，与 timer 计时页「暂停中已显示 1 次」口径一致）。
     */
    val pauseCount: Int = 0,
    /** 当日暂停总时长（毫秒，含仍在进行中的暂停按参考时刻折算，同样按当日窗口裁剪） */
    val pausedTotalMillis: Long = 0L,
    /** 当日暂停最久的作业；当日无暂停时为 null */
    val mostPausedItem: PausedHomework? = null,
) {

    /** 完成率（0..1；无作业时为 0，避免除零） */
    val completionRate: Double
        get() = StatsCalculations.completionRate(completedCount, totalCount)

    /** 当日是否没有任何作业进入盘点口径（页面据此展示空态） */
    val isEmpty: Boolean get() = totalCount == 0
}

/**
 * 暂停最久的作业摘要（[DaySummary.mostPausedItem]）。
 *
 * 单独建模而不直接复用 [HomeworkItem]：盘点页只需要「作业内容 + 暂停时长」两项，
 * 避免把这件作业的完整状态（优先级/排定时间等）当作盘点口径的一部分。
 */
data class PausedHomework(
    val homeworkId: Long,
    /** 作业内容（展示用，已由 homework 录入时校验非空） */
    val content: String,
    /** 该作业当日累计暂停时长（毫秒） */
    val pausedMillis: Long,
    /** 该作业当日暂停次数 */
    val pauseCount: Int,
)

/**
 * 单项作业详情（单项详情页展示口径）。
 *
 * 语义：
 * - [estimatedMinutes] 来自作业排定（未排定时间为 null）；
 * - [elapsedMillis] / [pausedTotalMillis] 为**累计口径**：该作业**全部历史执行会话**的
 *   已用时长与暂停时长合计（不限于某一天）。因此与「当日盘点」的当日窗口口径可能不同——
 *   跨天多次计时的作业，详情页的累计时长会大于任一天的当日时长；页面文案已显式标注「累计」，
 *   避免家长误读为当日用时；
 * - [sessionCount] 为累计执行会话数；0 表示「尚无执行记录」，页面据此展示「尚未开始/暂无数据」；
 * - [difficulty] / [assessmentHint] 由 [DifficultyAssessor] 依据完成状态、耗时比值与暂停次数给出侧面评估，
 *   仅作提示，不作为作业状态的判定依据。
 */
data class ItemDetail(
    val homeworkId: Long,
    val content: String,
    /** 作业归属学生（详情页以**作业归属**为准取数，不按路由学生二次筛选） */
    val studentId: Long,
    val status: HomeworkStatus,
    val estimatedMinutes: Int?,
    /** 累计实际时长（毫秒）：全部历史执行会话的已用时长合计，已扣除暂停 */
    val elapsedMillis: Long,
    /** 累计暂停时长（毫秒）：全部历史执行会话的暂停时长合计 */
    val pausedTotalMillis: Long,
    /** 累计暂停次数：按去重后的暂停段计（口径见 [StatsCalculations.itemDetail]） */
    val pauseCount: Int,
    val sessionCount: Int,
    val difficulty: DifficultyLevel,
    val assessmentHint: String,
) {

    /** 是否存在执行记录（会话数为 0 即尚未开始计时） */
    val hasExecution: Boolean get() = sessionCount > 0

    /**
     * 预估时长文案（未排定时间为 null，页面展示「未设定」）。
     *
     * 注意：预估时长以**分钟**落库，而 [StatsCalculations.minutesText] 的入参单位也是分钟
     * （其内部再换算为毫秒），此处**不得**再乘 `MILLIS_PER_MINUTE`，
     * 否则会二次换算把 30 分钟放大为 30000 小时。
     */
    val estimatedText: String?
        get() = estimatedMinutes?.let { StatsCalculations.minutesText(it.toLong()) }
}

/**
 * 会话事实（stats 需要的会话字段投影）：只保留统计所需的最小时刻与归属信息，
 * 避免 stats 把 timer 的完整会话模型（阶段/汇总快照）当作统计口径的一部分。
 *
 * 聚合口径（当日纳入范围、完成率分母、暂停去重）集中在 [StatsCalculations] 纯函数里，
 * 可用构造数据直接单测，不需要数据库或协程；仓库只负责取数与权限收敛。
 */
data class TimerSessionFacts(
    val sessionId: Long,
    val homeworkId: Long,
    val studentId: Long,
    val startedAtMillis: Long,
    /** 结束时刻；未结束会话为 null（统计时以参考时刻折算） */
    val finishedAtMillis: Long? = null,
    val pauses: List<PauseFact> = emptyList(),
)

/**
 * 暂停事实（stats 需要的暂停字段投影）。
 * [pauseEndAtMillis] 为 null 表示该段暂停尚未结束，统计时按参考时刻折算。
 */
data class PauseFact(
    val pauseStartAtMillis: Long,
    val pauseEndAtMillis: Long? = null,
)

/**
 * 某作业在统计窗口内的暂停汇总（时长 + 次数）。
 *
 * 之所以不直接传暂停明细列表：当日盘点的暂停必须**按当日窗口裁剪后**累加，
 * 再按原始明细重算会绕开窗口裁剪、把跨天暂停整段算进当天（口径错误）。
 * 本模型使「时长与次数的口径」在类型上就与裁剪逻辑绑定，避免下游误用原始明细。
 */
data class PausedAccum(
    val pausedMillis: Long = 0L,
    val count: Int = 0,
) {

    /** 累加同一作业的若干段「窗口内暂停时长」（多条会话/多段暂停合并） */
    operator fun plus(durationsMillis: List<Long>): PausedAccum = PausedAccum(
        pausedMillis = pausedMillis + durationsMillis.sumOf { it },
        count = count + durationsMillis.size,
    )

    companion object {

        /** 空汇总（尚未累加任何暂停） */
        val EMPTY: PausedAccum = PausedAccum()
    }
}