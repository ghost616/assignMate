package com.assignmate.app.stats.domain

import com.assignmate.app.core.domain.homework.HomeworkDayStatus

/**
 * stats 模块领域模型集合：当日盘点、单项详情、阶段打卡进度与历史查询结果。
 *
 * 数据来源口径（阶段作业「一条作业项 + 每天详情」改造后的统一定义）：
 * - **一条作业项**（[com.assignmate.app.homework.domain.HomeworkItem]）描述作业本身（内容/类型/优先级/预估）
 *   以及**当天是否应做**（当天作业看归属日、阶段作业看阶段覆盖范围）；
 * - 「某一天做没做、用了多久」落在**作业每天详情**
 *   （[com.assignmate.app.core.domain.homework.HomeworkDailyRecord]）上，由 timer / homework 按需写入；
 * - 因此本模块的「当天」「某一天」口径一律**按自然日取每天详情**，不再按作业项聚合状态或计时会话裁剪，
 *   阶段作业的每一天彼此独立（同一条阶段作业不同天各自统计，绝不串天）。
 *
 * 设计约定：
 * - 本层为**纯数据 + 纯计算**，不读时钟、不访问数据库；
 * - 完成率 [DaySummary.completionRate] 为 0..1 的比例值，百分数文案由
 *   [StatsCalculations.percentText] 统一生成，避免各页面各自格式化；
 * - 失败/越权不抛异常，统一由仓库层收敛为 [com.assignmate.app.stats.data.StatsResult]。
 */

/**
 * 当日作业条目（**按自然日**的作业视图，供盘点页当日清单与历史条目展示）。
 *
 * 与作业项的区别：本模型描述「这条作业在**这一天**的状态」，阶段作业的每一天各自一条；
 * 作业项自身的状态无法表达「某一天做没做」（一条阶段作业只有一个作业项）。
 */
data class DayItem(
    val homeworkId: Long,
    /** 作业内容（展示用，已由 homework 录入时校验非空） */
    val content: String,
    /** 这一天的状态（未开始 / 进行中 / 已完成 / 未完成【缺卡】） */
    val status: HomeworkDayStatus,
    /** 是否阶段作业（页面据此标注阶段与打卡进度） */
    val isStage: Boolean,
    /** 作业优先级（数值越小越靠前）：用于「暂停最久」并列时的稳定取舍 */
    val priority: Int,
) {

    /** 这一天是否已完成（完成率分子口径的唯一判定） */
    val isCompleted: Boolean get() = StatsCalculations.isDayCompleted(status)
}

/**
 * 阶段作业的打卡进度（[DaySummary.stages] / [ItemDetail.stageProgress]）。
 *
 * 口径：[doneDays] = 该阶段作业**已打卡的自然日天数**（覆盖区间内已有完成记录的天数），
 * [totalDays] = 阶段**覆盖天数**（分母 M = 阶段范围天数，与 homework 的阶段进度同源）。
 */
data class StageProgress(
    val homeworkId: Long,
    /** 阶段作业内容（展示用） */
    val content: String,
    /** 已完成天数 */
    val doneDays: Int,
    /** 应打卡总天数（= 阶段**覆盖天数** `HomeworkItem.stageCoveredDays`，与 homework 的阶段进度分母同源） */
    val totalDays: Int,
)

/**
 * 当日盘点结果（stats 的核心输出，供当日盘点页与历史条目展示）。
 *
 * 日期口径：本模型描述**某一天**（业务时区自然日）的完成情况；
 * [epochDay] 为业务自然日的纪元日（`LocalDate.toEpochDay()`），便于历史查询逐日渲染与测试断言。
 *
 * 分母口径（「当天应做」）：
 * [totalCount] = 该学生这一天的**应做作业数**，即「当天作业（归属日即当天）+ 阶段作业（今天落在其阶段覆盖范围内）」；
 * 判定由作业项推导（见 [StatsCalculations.shouldDoOn]），**不是**「当天有没有每天详情」——
 * 每天详情是按需写入的，没动过的作业没有详情。
 * [completedCount] = 其中当天状态已完成的数量；两者为 0 时 [completionRate] 为 0（不除零）。
 */
data class DaySummary(
    /** 盘点所属自然日（业务时区纪元日） */
    val epochDay: Long,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    /** 当天应做的作业清单（按优先级升序）：供盘点页直接展示与进入单项详情 */
    val items: List<DayItem> = emptyList(),
    /**
     * 当天暂停次数：取自**当天详情**的暂停次数合计
     * （跨天计时按开始日归属，由 timer 在写入每天详情时确定，与计时口径一致）。
     */
    val pauseCount: Int = 0,
    /** 当天暂停总时长（毫秒，由当天详情的分钟值换算） */
    val pausedTotalMillis: Long = 0L,
    /** 当天暂停最久的作业；当天无暂停时为 null */
    val mostPausedItem: PausedHomework? = null,
    /**
     * 当天涉及的阶段作业打卡进度（当天作业不产生条目）。
     *
     * 阶段作业在历史里需要同时体现「当日是否完成」与「整段打卡进度」：
     * 前者由 [items] 的当天状态表达，后者由本字段表达。
     */
    val stages: List<StageProgress> = emptyList(),
) {

    /** 完成率（0..1；无应做作业时为 0，避免除零） */
    val completionRate: Double
        get() = StatsCalculations.completionRate(completedCount, totalCount)

    /** 当天是否没有任何应做作业（页面据此展示儿童友好的空态） */
    val isEmpty: Boolean get() = totalCount == 0
}

/**
 * 暂停最久的作业摘要（[DaySummary.mostPausedItem]）。
 *
 * 单独建模而不直接复用 [DayItem]：盘点页只需要「作业内容 + 暂停时长/次数」三项，
 * 避免把这件作业的完整状态（优先级/是否阶段等）当作盘点口径的一部分。
 */
data class PausedHomework(
    val homeworkId: Long,
    /** 作业内容（展示用，已由 homework 录入时校验非空） */
    val content: String,
    /** 该作业当天累计暂停时长（毫秒） */
    val pausedMillis: Long,
    /** 该作业当天暂停次数 */
    val pauseCount: Int,
)

/**
 * 单项作业详情（单项详情页展示口径）——**按指定某一天**取值。
 *
 * 语义（与「一条作业项 + 每天详情」模型一致）：
 * - [epochDay] 为详情的所属自然日；页面据它与「今天」比较选择文案口径；
 * - [dayStatus] 为该作业**这一天的状态**（取自每天详情；当天无详情时视为未开始）；
 * - [estimatedMinutes] 优先取当天详情里的预估，当天未单独设定时回落到作业本身的预估；
 * - [elapsedMillis] / [pausedTotalMillis] 为**当天口径**（由当天详情的分钟值换算），
 *   阶段作业不再给「阶段总计」——要看别的日子请切换日期；
 * - [hasExecution] 表示**这一天**是否留下执行痕迹（开始过计时 / 有时长 / 有暂停）；
 *   为 false 时页面展示「尚未开始/暂无数据」，属正常态而非错误态；
 * - [stageProgress] 为阶段作业的整段打卡进度（当天作业为 null），与当天的 [dayStatus] 互为补充；
 * - [difficulty] / [assessmentHint] 由 [DifficultyAssessor] 依据**当天状态**、耗时比值与暂停次数给出侧面评估，
 *   仅作提示，不作为作业状态的判定依据。
 */
data class ItemDetail(
    val homeworkId: Long,
    val content: String,
    /** 作业归属学生（详情页以**作业归属**为准取数，不按路由学生二次筛选） */
    val studentId: Long,
    /** 详情所属自然日（业务时区纪元日） */
    val epochDay: Long,
    /** 是否阶段作业 */
    val isStage: Boolean,
    /** 这一天的作业状态 */
    val dayStatus: HomeworkDayStatus,
    val estimatedMinutes: Int?,
    /** 当天实际时长（毫秒，由当天详情的分钟值换算） */
    val elapsedMillis: Long,
    /** 当天暂停时长（毫秒，由当天详情的分钟值换算） */
    val pausedTotalMillis: Long,
    /** 当天暂停次数 */
    val pauseCount: Int,
    /** 这一天是否有执行痕迹（false 即「尚未开始」） */
    val hasExecution: Boolean,
    val difficulty: DifficultyLevel,
    val assessmentHint: String,
    /** 阶段作业打卡进度（当天作业为 null；阶段作业无任何天详情时同样为 null） */
    val stageProgress: StageProgress? = null,
) {

    /**
     * 预估时长文案（未设定时为 null，页面展示「未设定」）。
     *
     * 注意：预估时长以**分钟**落库，而 [StatsCalculations.minutesText] 的入参单位也是分钟
     * （其内部再换算为毫秒），此处**不得**再乘 `MILLIS_PER_MINUTE`，
     * 否则会二次换算把 30 分钟放大为 30000 小时。
     */
    val estimatedText: String?
        get() = estimatedMinutes?.let { StatsCalculations.minutesText(it.toLong()) }
}
