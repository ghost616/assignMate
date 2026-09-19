package com.assignmate.app.stats.domain

import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.HomeworkStatus
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatsCalculations] 单测：完成率边界、当天应做判定与当天状态、暂停汇总与最久暂停、按天单项详情、
 * 当日盘点与历史范围聚合、时间与展示换算。
 *
 * 全部用例为纯函数断言（显式传入业务自然日、「今天」、业务时区与每天详情，不依赖真实时钟与数据库），
 * 业务时区固定为 [StatsTestData.ZONE]（Asia/Shanghai），基准日为 2023-11-14。
 *
 * 「当天应做分母」「阶段按天取值」「暂停取自当天详情」三组口径的核心用例见 [StatsDailyCaliberTest]。
 */
class StatsCalculationsTest {

    private val zone = StatsTestData.ZONE
    private val day = StatsTestData.DAY_20231114_EPOCH

    // ---- 完成率与计数 ----

    @Test
    fun `无应做作业时完成率为 0 且不产生 NaN`() {
        assertEquals(0.0, StatsCalculations.completionRate(completed = 0, total = 0), 0.0)
    }

    @Test
    fun `全部完成时完成率为 1`() {
        assertEquals(1.0, StatsCalculations.completionRate(completed = 5, total = 5), 0.0)
    }

    @Test
    fun `部分完成时完成率为已完成除以总数`() {
        assertEquals(0.5, StatsCalculations.completionRate(completed = 3, total = 6), 1e-9)
    }

    @Test
    fun `完成数为负时按 0 计（脏数据不产生负完成率）`() {
        assertEquals(0.0, StatsCalculations.completionRate(completed = -2, total = 4), 0.0)
    }

    @Test
    fun `已完成判定只认当天状态为已完成`() {
        assertTrue(StatsCalculations.isDayCompleted(HomeworkDayStatus.COMPLETED))
        listOf(
            HomeworkDayStatus.NOT_STARTED,
            HomeworkDayStatus.IN_PROGRESS,
            HomeworkDayStatus.MISSED,
        ).forEach { status ->
            assertFalse("$status 不应算已完成", StatsCalculations.isDayCompleted(status))
        }
    }

    @Test
    fun `已完成天数只统计状态为已完成的详情`() {
        val records = listOf(
            StatsTestData.dayRecord(1L, day, status = HomeworkDayStatus.COMPLETED),
            StatsTestData.dayRecord(1L, day - 1, status = HomeworkDayStatus.MISSED),
            StatsTestData.dayRecord(1L, day - 2, status = HomeworkDayStatus.COMPLETED),
            StatsTestData.dayRecord(1L, day - 3, status = HomeworkDayStatus.IN_PROGRESS),
        )
        assertEquals(2, StatsCalculations.completedCountOf(records))
    }

    // ---- 当天应做与当天状态 ----

    @Test
    fun `当天作业的应做日即其归属日（创建时刻所在业务自然日）`() {
        val item = StatsTestData.homework(1L, createdAtMillis = StatsTestData.at(0))
        assertTrue(StatsCalculations.shouldDoOn(item, day, zone))
        assertFalse(StatsCalculations.shouldDoOn(item, day + 1, zone))
        assertFalse(StatsCalculations.shouldDoOn(item, day - 1, zone))
    }

    @Test
    fun `阶段作业的应做日落在阶段覆盖范围内`() {
        // 覆盖 day-2 .. day+4（一周）
        val stage = StatsTestData.stageHomework(1L, startEpochDay = day - 2)
        assertTrue(StatsCalculations.shouldDoOn(stage, day - 2, zone))
        assertTrue(StatsCalculations.shouldDoOn(stage, day, zone))
        assertTrue(StatsCalculations.shouldDoOn(stage, day + 4, zone))
        assertFalse(StatsCalculations.shouldDoOn(stage, day + 5, zone))
        assertFalse(StatsCalculations.shouldDoOn(stage, day - 3, zone))
    }

    @Test
    fun `当天作业的当天状态由作业状态列映射`() {
        fun statusOf(status: HomeworkStatus): HomeworkDayStatus =
            StatsCalculations.dayStatusOf(
                item = StatsTestData.homework(1L, status = status),
                records = emptyList(),
                epochDay = day,
                todayEpochDay = day,
                zoneId = zone,
            )

        assertEquals(HomeworkDayStatus.COMPLETED, statusOf(HomeworkStatus.COMPLETED))
        assertEquals(HomeworkDayStatus.IN_PROGRESS, statusOf(HomeworkStatus.IN_PROGRESS))
        assertEquals(HomeworkDayStatus.NOT_STARTED, statusOf(HomeworkStatus.PENDING))
        assertEquals(HomeworkDayStatus.NOT_STARTED, statusOf(HomeworkStatus.RECORDED))
    }

    @Test
    fun `阶段作业的当天状态按每天详情与缺卡口径推导`() {
        val stage = StatsTestData.stageHomework(1L, startEpochDay = day - 2)
        val records = listOf(
            StatsTestData.dayRecord(1L, day - 2, status = HomeworkDayStatus.COMPLETED),
        )

        // 已完成：覆盖范围内有完成记录
        assertEquals(
            HomeworkDayStatus.COMPLETED,
            StatsCalculations.dayStatusOf(stage, records, day - 2, todayEpochDay = day, zoneId = zone),
        )
        // 已过去且未完成：缺卡（不可补做）
        assertEquals(
            HomeworkDayStatus.MISSED,
            StatsCalculations.dayStatusOf(stage, records, day - 1, todayEpochDay = day, zoneId = zone),
        )
        // 今天尚未完成：未开始（仍可完成）
        assertEquals(
            HomeworkDayStatus.NOT_STARTED,
            StatsCalculations.dayStatusOf(stage, records, day, todayEpochDay = day, zoneId = zone),
        )
        // 尚未到来的日子同样按未开始呈现
        assertEquals(
            HomeworkDayStatus.NOT_STARTED,
            StatsCalculations.dayStatusOf(stage, records, day + 1, todayEpochDay = day, zoneId = zone),
        )
    }

    @Test
    fun `阶段作业当天已开工时按进行中显示（以当天详情为准）`() {
        val stage = StatsTestData.stageHomework(1L, startEpochDay = day)
        val records = listOf(
            StatsTestData.dayRecord(1L, day, status = HomeworkDayStatus.IN_PROGRESS, startedAtMillis = 1L),
        )

        assertEquals(
            HomeworkDayStatus.IN_PROGRESS,
            StatsCalculations.dayStatusOf(stage, records, day, todayEpochDay = day, zoneId = zone),
        )
    }

    @Test
    fun `历史日残留的进行中详情按缺卡呈现而非永远进行中`() {
        // 计时中途退出/跨天未收敛会在历史日留下进行中详情；那天已过去且不可补做，按缺卡口径落回 stateOf
        val stage = StatsTestData.stageHomework(1L, startEpochDay = day - 2)
        val records = listOf(
            StatsTestData.dayRecord(1L, day - 1, status = HomeworkDayStatus.IN_PROGRESS, startedAtMillis = 1L),
        )

        assertEquals(
            HomeworkDayStatus.MISSED,
            StatsCalculations.dayStatusOf(stage, records, day - 1, todayEpochDay = day, zoneId = zone),
        )
        // 同一条作业的「今天」仍按进行中显示：时间窗只影响历史日
        assertEquals(
            HomeworkDayStatus.NOT_STARTED,
            StatsCalculations.dayStatusOf(stage, records, day, todayEpochDay = day, zoneId = zone),
        )
    }

    @Test
    fun `历史日已完成的阶段作业仍按已完成呈现（不受时间窗影响）`() {
        val stage = StatsTestData.stageHomework(1L, startEpochDay = day - 2)
        val records = listOf(
            StatsTestData.dayRecord(
                homeworkId = 1L,
                epochDay = day - 1,
                status = HomeworkDayStatus.COMPLETED,
                startedAtMillis = 1L,
            ),
        )

        assertEquals(
            HomeworkDayStatus.COMPLETED,
            StatsCalculations.dayStatusOf(stage, records, day - 1, todayEpochDay = day, zoneId = zone),
        )
    }

    @Test
    fun `阶段整体结束后今天的残留进行中详情按未开始呈现`() {
        // 覆盖 day-10 .. day-4：今天已不在阶段范围内（当天清单因不应做而为空），
        // 残留的进行中详情也不该让统计详情页显示「进行中」
        val ended = StatsTestData.stageHomework(1L, startEpochDay = day - 10)
        val records = listOf(
            StatsTestData.dayRecord(1L, day, status = HomeworkDayStatus.IN_PROGRESS, startedAtMillis = 1L),
        )

        assertEquals(
            HomeworkDayStatus.NOT_STARTED,
            StatsCalculations.dayStatusOf(ended, records, day, todayEpochDay = day, zoneId = zone),
        )
        // 覆盖期内的最后一天已过去且未完成 → 缺卡（与 homework 口径一致）
        assertEquals(
            HomeworkDayStatus.MISSED,
            StatsCalculations.dayStatusOf(ended, records, day - 4, todayEpochDay = day, zoneId = zone),
        )
    }

    // ---- 暂停汇总与最久暂停 ----

    @Test
    fun `暂停最久的作业按当天详情聚合后取最大`() {
        val items = listOf(
            dayItem(1L, content = "语文生字", priority = 100),
            dayItem(2L, content = "数学口算", priority = 101),
        )
        val result = StatsCalculations.mostPausedHomeworkOf(
            items = items,
            dayRecords = listOf(
                StatsTestData.dayRecord(1L, day, pauseCount = 1, pausedTotalMinutes = 2),
                StatsTestData.dayRecord(2L, day, pauseCount = 1, pausedTotalMinutes = 10),
            ),
        )
        assertEquals(2L, result?.homeworkId)
        assertEquals("数学口算", result?.content)
        assertEquals(10 * StatsTestData.MINUTE, result?.pausedMillis)
        assertEquals(1, result?.pauseCount)
    }

    @Test
    fun `暂停时长并列时取优先级靠前的作业`() {
        val result = StatsCalculations.mostPausedHomeworkOf(
            items = listOf(
                dayItem(1L, priority = 50),
                dayItem(2L, priority = 10),
            ),
            dayRecords = listOf(
                StatsTestData.dayRecord(1L, day, pauseCount = 1, pausedTotalMinutes = 5),
                StatsTestData.dayRecord(2L, day, pauseCount = 1, pausedTotalMinutes = 5),
            ),
        )
        assertEquals(2L, result?.homeworkId)
    }

    @Test
    fun `不在当天清单内的作业其暂停不参与最久暂停评选`() {
        val result = StatsCalculations.mostPausedHomeworkOf(
            items = listOf(dayItem(1L)),
            dayRecords = listOf(StatsTestData.dayRecord(99L, day, pauseCount = 1, pausedTotalMinutes = 9)),
        )
        assertNull(result)
    }

    @Test
    fun `暂停次数或时长为零时不产生暂停最久作业`() {
        val items = listOf(dayItem(1L))
        assertNull(
            StatsCalculations.mostPausedHomeworkOf(
                items = items,
                dayRecords = listOf(StatsTestData.dayRecord(1L, day)),
            ),
        )
        assertNull(
            StatsCalculations.mostPausedHomeworkOf(
                items = items,
                dayRecords = listOf(
                    StatsTestData.dayRecord(1L, day, pauseCount = 1, pausedTotalMinutes = 0),
                ),
            ),
        )
        assertNull(
            StatsCalculations.mostPausedHomeworkOf(
                items = items,
                dayRecords = listOf(
                    StatsTestData.dayRecord(1L, day, pauseCount = 0, pausedTotalMinutes = 8),
                ),
            ),
        )
    }

    // ---- 单项按天详情 ----

    @Test
    fun `单项详情汇总当天预估实际暂停与状态`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED, estimatedMinutes = 30)
        val detail = StatsCalculations.dayDetail(
            item = item,
            records = listOf(
                StatsTestData.dayRecord(
                    homeworkId = 1L,
                    epochDay = day,
                    status = HomeworkDayStatus.COMPLETED,
                    startedAtMillis = StatsTestData.at(0),
                    estimatedMinutes = 30,
                    actualMinutes = 25,
                    pauseCount = 1,
                    pausedTotalMinutes = 5,
                    finishedAtMillis = StatsTestData.at(30),
                ),
            ),
            epochDay = day,
            todayEpochDay = day,
            zoneId = zone,
        )
        assertEquals(30, detail.estimatedMinutes)
        assertEquals(25 * StatsTestData.MINUTE, detail.elapsedMillis)
        assertEquals(5 * StatsTestData.MINUTE, detail.pausedTotalMillis)
        assertEquals(1, detail.pauseCount)
        assertEquals(HomeworkDayStatus.COMPLETED, detail.dayStatus)
        assertTrue(detail.hasExecution)
        assertEquals(day, detail.epochDay)
        assertEquals(1L, detail.homeworkId)
    }

    @Test
    fun `未设定预估时长时详情预估文案为空`() {
        val item = StatsTestData.homework(1L, estimatedMinutes = null)
        val detail = StatsCalculations.dayDetail(item, emptyList(), day, todayEpochDay = day, zoneId = zone)
        assertNull(detail.estimatedText)
        assertFalse(detail.hasExecution)
    }

    @Test
    fun `当天没有详情时按未开始呈现并回落到作业预估`() {
        val item = StatsTestData.homework(1L, estimatedMinutes = 30)
        val detail = StatsCalculations.dayDetail(
            item = item,
            records = listOf(
                StatsTestData.dayRecord(1L, day - 1, status = HomeworkDayStatus.COMPLETED, actualMinutes = 20),
            ),
            epochDay = day,
            todayEpochDay = day,
            zoneId = zone,
        )
        assertEquals(HomeworkDayStatus.NOT_STARTED, detail.dayStatus)
        assertEquals(30, detail.estimatedMinutes)
        assertEquals(0L, detail.elapsedMillis)
        assertEquals(0, detail.pauseCount)
        assertFalse(detail.hasExecution)
        assertEquals(DifficultyLevel.UNKNOWN, detail.difficulty)
        assertEquals(DifficultyAssessor.HINT_NOT_STARTED, detail.assessmentHint)
    }

    @Test
    fun `阶段作业缺卡日不给出困难度结论`() {
        val stage = StatsTestData.stageHomework(1L, startEpochDay = day - 2)
        val detail = StatsCalculations.dayDetail(
            item = stage,
            records = emptyList(),
            epochDay = day - 1,
            todayEpochDay = day,
            zoneId = zone,
        )
        assertEquals(HomeworkDayStatus.MISSED, detail.dayStatus)
        assertFalse(detail.hasExecution)
        assertEquals(DifficultyLevel.UNKNOWN, detail.difficulty)
    }

    @Test
    fun `暂停中存在未结束计时同样算当天有执行痕迹`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.IN_PROGRESS, estimatedMinutes = 30)
        val detail = StatsCalculations.dayDetail(
            item = item,
            records = listOf(
                StatsTestData.dayRecord(
                    homeworkId = 1L,
                    epochDay = day,
                    status = HomeworkDayStatus.IN_PROGRESS,
                    startedAtMillis = StatsTestData.at(5),
                ),
            ),
            epochDay = day,
            todayEpochDay = day,
            zoneId = zone,
        )
        assertTrue(detail.hasExecution)
        assertEquals(HomeworkDayStatus.IN_PROGRESS, detail.dayStatus)
        assertEquals(0L, detail.elapsedMillis)
    }

    @Test
    fun `阶段作业详情携带整段打卡进度而当天作业没有`() {
        // 覆盖 day-1 .. day+5（一周）
        val stage = StatsTestData.stageHomework(1L, content = "背单词", startEpochDay = day - 1)
        val records = listOf(
            StatsTestData.dayRecord(1L, day - 1, status = HomeworkDayStatus.COMPLETED),
            StatsTestData.dayRecord(1L, day, status = HomeworkDayStatus.IN_PROGRESS, startedAtMillis = 1L),
        )
        val stageDetail = StatsCalculations.dayDetail(stage, records, day, todayEpochDay = day, zoneId = zone)
        // 分母 M = 阶段覆盖天数（与 homework 的阶段进度同源），不是详情条数
        assertEquals(1, stageDetail.stageProgress?.doneDays)
        assertEquals(7, stageDetail.stageProgress?.totalDays)

        val todayDetail = StatsCalculations.dayDetail(
            StatsTestData.homework(1L),
            records,
            day,
            todayEpochDay = day,
            zoneId = zone,
        )
        assertNull(todayDetail.stageProgress)
    }

    @Test
    fun `非阶段作业或阶段信息缺失时不给阶段进度`() {
        assertNull(
            StatsCalculations.stageProgressOf(
                item = StatsTestData.homework(1L),
                records = emptyList(),
                todayEpochDay = day,
                zoneId = zone,
            ),
        )
        // 阶段范围存在的作业即使没有任何详情，也给出诚实的 0/M 进度（与清单页同源）
        val stage = StatsTestData.stageHomework(1L, startEpochDay = day)
        val progress = StatsCalculations.stageProgressOf(stage, emptyList(), todayEpochDay = day, zoneId = zone)
        assertEquals(0, progress?.doneDays)
        assertEquals(7, progress?.totalDays)
    }

    @Test
    fun `阶段打卡进度按覆盖区间内的完成天数统计末尾缺口`() {
        val stage = StatsTestData.stageHomework(1L, content = "背单词", startEpochDay = day)
        val progress = StatsCalculations.stageProgressOf(
            item = stage,
            records = listOf(
                StatsTestData.dayRecord(1L, day, status = HomeworkDayStatus.COMPLETED),
                StatsTestData.dayRecord(1L, day + 1, status = HomeworkDayStatus.COMPLETED),
                StatsTestData.dayRecord(1L, day + 2, status = HomeworkDayStatus.MISSED),
            ),
            todayEpochDay = day + 3,
            zoneId = zone,
        )
        assertEquals(2, progress?.doneDays)
        assertEquals(7, progress?.totalDays)
    }

    // ---- 当日盘点 ----

    @Test
    fun `当日盘点按当天应做统计完成率与暂停并过滤清单外作业`() {
        val items = listOf(
            StatsTestData.homework(1L, content = "语文生字", status = HomeworkStatus.COMPLETED, priority = 100),
            StatsTestData.homework(2L, content = "数学口算", priority = 101),
        )
        val summary = StatsCalculations.summarizeDay(
            epochDay = day,
            todayEpochDay = day,
            items = items,
            recordsByHomework = mapOf(
                1L to listOf(
                    StatsTestData.dayRecord(
                        homeworkId = 1L,
                        epochDay = day,
                        status = HomeworkDayStatus.COMPLETED,
                        pauseCount = 1,
                        pausedTotalMinutes = 2,
                    ),
                ),
                2L to listOf(StatsTestData.dayRecord(homeworkId = 2L, epochDay = day)),
                // 不在清单内的作业（已删除/他人）即使有当天详情也不进盘点
                99L to listOf(StatsTestData.dayRecord(homeworkId = 99L, epochDay = day)),
            ),
            zoneId = zone,
        )
        assertEquals(2, summary.totalCount)
        assertEquals(1, summary.completedCount)
        assertEquals(0.5, summary.completionRate, 1e-9)
        assertEquals(1, summary.pauseCount)
        assertEquals(2 * StatsTestData.MINUTE, summary.pausedTotalMillis)
        assertEquals("语文生字", summary.mostPausedItem?.content)
        assertEquals(listOf(1L, 2L), summary.items.map { it.homeworkId })
        assertFalse(summary.isEmpty)
        assertEquals(day, summary.epochDay)
    }

    @Test
    fun `当日清单按优先级升序排列`() {
        val items = listOf(
            StatsTestData.homework(1L, priority = 300),
            StatsTestData.homework(2L, priority = 100),
            StatsTestData.homework(3L, priority = 200),
        )
        val summary = StatsCalculations.summarizeDay(
            epochDay = day,
            todayEpochDay = day,
            items = items,
            recordsByHomework = emptyMap(),
            zoneId = zone,
        )
        assertEquals(listOf(2L, 3L, 1L), summary.items.map { it.homeworkId })
    }

    @Test
    fun `空数据当日盘点为空且完成率为 0`() {
        val summary = StatsCalculations.summarizeDay(
            epochDay = day,
            todayEpochDay = day,
            items = emptyList(),
            recordsByHomework = emptyMap(),
            zoneId = zone,
        )
        assertTrue(summary.isEmpty)
        assertEquals(0.0, summary.completionRate, 0.0)
        assertEquals(day, summary.epochDay)
        assertNull(summary.mostPausedItem)
        assertTrue(summary.stages.isEmpty())
    }

    @Test
    fun `当日盘点只采用落在当天的详情`() {
        val item = StatsTestData.homework(1L)
        val summary = StatsCalculations.summarizeDay(
            epochDay = day,
            todayEpochDay = day,
            items = listOf(item),
            recordsByHomework = mapOf(
                1L to listOf(
                    StatsTestData.dayRecord(
                        homeworkId = 1L,
                        epochDay = day - 1,
                        status = HomeworkDayStatus.COMPLETED,
                        pauseCount = 2,
                        pausedTotalMinutes = 30,
                    ),
                ),
            ),
            zoneId = zone,
        )
        // 应做（分母）为 1，但当天没有详情：完成数为 0、暂停指标全为 0
        assertEquals(1, summary.totalCount)
        assertEquals(0, summary.completedCount)
        assertEquals(0, summary.pauseCount)
        assertEquals(0L, summary.pausedTotalMillis)
    }

    @Test
    fun `当日盘点携带当天阶段作业的打卡进度`() {
        val stage = StatsTestData.stageHomework(1L, content = "背单词", startEpochDay = day - 1)
        val summary = StatsCalculations.summarizeDay(
            epochDay = day,
            todayEpochDay = day,
            items = listOf(stage),
            recordsByHomework = mapOf(
                1L to listOf(
                    StatsTestData.dayRecord(1L, day - 1, status = HomeworkDayStatus.COMPLETED),
                    StatsTestData.dayRecord(1L, day, status = HomeworkDayStatus.COMPLETED),
                ),
            ),
            zoneId = zone,
        )
        assertEquals(1, summary.stages.size)
        assertEquals("背单词", summary.stages.single().content)
        assertEquals(2, summary.stages.single().doneDays)
        assertEquals(7, summary.stages.single().totalDays)
        assertEquals(1, summary.completedCount)
    }

    // ---- 历史范围查询 ----

    @Test
    fun `范围查询逐日聚合且按日期倒序`() {
        // 阶段作业覆盖 day-1 与 day：两天都应做（即使某天没有任何执行记录）
        val stage = StatsTestData.stageHomework(1L, startEpochDay = day - 1)
        val summaries = StatsCalculations.summarizeRange(
            fromEpochDay = day - 1,
            toEpochDay = day,
            todayEpochDay = day,
            items = listOf(stage),
            recordsByHomework = mapOf(
                1L to listOf(StatsTestData.dayRecord(1L, day, status = HomeworkDayStatus.COMPLETED)),
            ),
            zoneId = zone,
        )
        assertEquals(listOf(day, day - 1), summaries.map { it.epochDay })
        assertEquals(1, summaries.first().completedCount)
        assertEquals(0, summaries.last().completedCount)
        assertEquals(HomeworkDayStatus.MISSED, summaries.last().items.single().status)
    }

    @Test
    fun `范围查询跳过无应做作业的日子`() {
        val summaries = StatsCalculations.summarizeRange(
            fromEpochDay = day - 3,
            toEpochDay = day,
            todayEpochDay = day,
            items = listOf(StatsTestData.homework(1L)),
            recordsByHomework = emptyMap(),
            zoneId = zone,
        )
        assertEquals(listOf(day), summaries.map { it.epochDay })
    }

    @Test
    fun `范围查询的起止日倒置时返回空列表`() {
        val summaries = StatsCalculations.summarizeRange(
            fromEpochDay = day,
            toEpochDay = day - 1,
            todayEpochDay = day,
            items = emptyList(),
            recordsByHomework = emptyMap(),
            zoneId = zone,
        )
        assertTrue(summaries.isEmpty())
    }

    @Test
    fun `范围合计累加各日指标并取暂停最久的一项`() {
        val stage = StatsTestData.stageHomework(1L, startEpochDay = day - 1)
        val summaries = StatsCalculations.summarizeRange(
            fromEpochDay = day - 1,
            toEpochDay = day,
            todayEpochDay = day,
            items = listOf(stage),
            recordsByHomework = mapOf(
                1L to listOf(
                    StatsTestData.dayRecord(
                        homeworkId = 1L,
                        epochDay = day,
                        status = HomeworkDayStatus.COMPLETED,
                        pauseCount = 1,
                        pausedTotalMinutes = 2,
                    ),
                    StatsTestData.dayRecord(
                        homeworkId = 1L,
                        epochDay = day - 1,
                        status = HomeworkDayStatus.MISSED,
                        pauseCount = 1,
                        pausedTotalMinutes = 5,
                    ),
                ),
            ),
            zoneId = zone,
        )
        val total = StatsCalculations.totalOf(summaries)
        assertEquals(2, total.totalCount)
        assertEquals(1, total.completedCount)
        assertEquals(2, total.pauseCount)
        assertEquals(7 * StatsTestData.MINUTE, total.pausedTotalMillis)
        assertEquals(day, total.epochDay)
        assertEquals(5 * StatsTestData.MINUTE, total.mostPausedItem?.pausedMillis)
        // 阶段打卡进度是「某一天」的概念，不参与跨日合计
        assertTrue(total.stages.isEmpty())
    }

    // ---- 时间与展示 ----

    @Test
    fun `纪元日换算按业务时区折算而非 UTC 折算`() {
        // 基准时刻：2023-11-13T16:09Z = 上海 2023-11-14 00:09（业务时区已进入 11-14，UTC 仍是 11-13）
        val utcInstant = StatsTestData.at(9)
        assertEquals(day, StatsCalculations.epochDayOfToday(zone, utcInstant))
        // 同一时刻按 UTC 口径仍属前一天：证明折算按注入时区而非固定偏移
        assertEquals(day - 1, StatsCalculations.epochDayOfToday(ZoneId.of("UTC"), utcInstant))
    }

    @Test
    fun `纪元日与展示文案往返一致`() {
        assertEquals("2023-11-14", StatsCalculations.dateText(day))
        assertEquals(day, StatsCalculations.epochDayOfToday(zone, StatsTestData.at(0)))
        // 次日起点（当日第 24 小时）属于下一天：半开区间语义
        assertEquals(day + 1, StatsCalculations.epochDayOfToday(zone, StatsTestData.at(24 * 60)))
    }

    @Test
    fun `分钟转毫秒时可空与负值一律按零`() {
        assertEquals(30 * StatsTestData.MINUTE, StatsCalculations.millisOfMinutes(30))
        assertEquals(0L, StatsCalculations.millisOfMinutes(null))
        assertEquals(0L, StatsCalculations.millisOfMinutes(-5))
    }

    @Test
    fun `时长文案按不足一分钟与小时分档`() {
        assertEquals("不到 1 分钟", StatsCalculations.durationText(0L))
        assertEquals("不到 1 分钟", StatsCalculations.durationText(59_000L))
        assertEquals("1 分钟", StatsCalculations.durationText(StatsTestData.MINUTE))
        assertEquals("59 分钟", StatsCalculations.durationText(59 * StatsTestData.MINUTE))
        assertEquals("1 小时", StatsCalculations.durationText(60 * StatsTestData.MINUTE))
        assertEquals("1 小时 30 分钟", StatsCalculations.durationText(90 * StatsTestData.MINUTE))
    }

    @Test
    fun `负时长收敛为不到一分钟`() {
        assertEquals("不到 1 分钟", StatsCalculations.durationText(-5_000L))
    }

    @Test
    fun `百分比文案保留一位小数并收敛到 0 到 100`() {
        assertEquals("0.0%", StatsCalculations.percentText(0.0))
        assertEquals("100.0%", StatsCalculations.percentText(1.0))
        assertEquals("62.5%", StatsCalculations.percentText(0.625))
        assertEquals("100.0%", StatsCalculations.percentText(1.5))
        assertEquals("0.0%", StatsCalculations.percentText(-0.5))
    }

    @Test
    fun `分钟文案复用时长文案口径`() {
        assertEquals("30 分钟", StatsCalculations.minutesText(30L))
        assertEquals("不到 1 分钟", StatsCalculations.minutesText(0L))
    }

    // ---- 预估时长展示口径（P1 回归看护） ----

    @Test
    fun `预估时长文案按分钟口径换算且未设定时为 null`() {
        // P1 回归看护：预估时长以「分钟」落库，展示必须走 minutesText(minutes)；
        // 若误传毫秒（再乘一次 MILLIS_PER_MINUTE）会把 30 分钟放大成 30000 小时。
        assertEquals("30 分钟", itemDetail(estimatedMinutes = 30).estimatedText)
        assertEquals("10 分钟", itemDetail(estimatedMinutes = 10).estimatedText)
        assertEquals("1 小时 30 分钟", itemDetail(estimatedMinutes = 90).estimatedText)
        assertNull(itemDetail(estimatedMinutes = null).estimatedText)
    }

    private fun itemDetail(estimatedMinutes: Int?): ItemDetail = ItemDetail(
        homeworkId = 1L,
        content = "数学口算",
        studentId = 2L,
        epochDay = day,
        isStage = false,
        dayStatus = HomeworkDayStatus.COMPLETED,
        estimatedMinutes = estimatedMinutes,
        elapsedMillis = 30 * StatsTestData.MINUTE,
        pausedTotalMillis = 5 * StatsTestData.MINUTE,
        pauseCount = 1,
        hasExecution = true,
        difficulty = DifficultyLevel.NORMAL,
        assessmentHint = "正常",
    )

    /** 构造当天清单条目 */
    private fun dayItem(
        homeworkId: Long,
        content: String = "作业$homeworkId",
        priority: Int = 100,
        status: HomeworkDayStatus = HomeworkDayStatus.NOT_STARTED,
        isStage: Boolean = false,
    ): DayItem = DayItem(
        homeworkId = homeworkId,
        content = content,
        status = status,
        isStage = isStage,
        priority = priority,
    )
}
