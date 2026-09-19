package com.assignmate.app.stats.domain

import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「作业每天详情」口径的专项用例（阶段作业由「逐日多条作业项」压为「1 条 + 每天详情」后新增）。
 *
 * 三组口径各自独立成组，逐条锁定改造目标：
 * 1. **当天应打的分母口径**：分母 = 当天应做的作业数 = 当天作业（归属日即当天）+ 阶段作业
 *    （今天落在其阶段覆盖范围内）；分子 = 其中当天已完成的数；覆盖「只有阶段作业落在今天」
 *    「阶段不在今天」「当天无应做作业分母为 0」三类边界；
 * 2. **阶段作业按天取值**：同一条阶段作业不同天各自独立统计，不串天（状态/时长/暂停都只看当天）；
 * 3. **暂停三项取自当天详情**：暂停次数、暂停总时长、暂停最久作业都以当天应做作业的当天详情为准，
 *    不因别的日子（或别的作业）的暂停数据而失真。
 *
 * 基准：业务时区 Asia/Shanghai，基准自然日 2023-11-14（[StatsTestData.DAY_20231114_EPOCH]），
 * 「今天」即该基准日。
 */
class StatsDailyCaliberTest {

    private val zone = StatsTestData.ZONE
    private val day = StatsTestData.DAY_20231114_EPOCH

    /** 汇总某一天的盘点（「今天」固定为基准日，减少用例噪音） */
    private fun summarize(
        epochDay: Long,
        items: List<HomeworkItem>,
        recordsByHomework: Map<Long, List<HomeworkDailyRecord>>,
        todayEpochDay: Long = day,
    ): DaySummary = StatsCalculations.summarizeDay(
        epochDay = epochDay,
        todayEpochDay = todayEpochDay,
        items = items,
        recordsByHomework = recordsByHomework,
        zoneId = zone,
    )

    // ---- 一、当天应打的分母口径 ----

    @Test
    fun `只有阶段作业落在今天时分母为该阶段作业`() {
        // 覆盖 day-2 .. day+4：今天在覆盖范围内，即便今天还没有任何执行记录也应做
        val stage = StatsTestData.stageHomework(1L, content = "背单词", startEpochDay = day - 2)
        val summary = summarize(
            epochDay = day,
            items = listOf(stage),
            recordsByHomework = mapOf(
                1L to listOf(
                    StatsTestData.dayRecord(1L, day - 2, status = HomeworkDayStatus.COMPLETED),
                    StatsTestData.dayRecord(1L, day - 1, status = HomeworkDayStatus.MISSED),
                ),
            ),
        )
        assertEquals(1, summary.totalCount)
        assertEquals(0, summary.completedCount)
        assertEquals(0.0, summary.completionRate, 0.0)
        assertEquals(listOf(1L), summary.items.map { it.homeworkId })
        assertFalse(summary.isEmpty)
        // 分母不为零但当天未完成：页面展示 0% 而不是「今天还没有作业记录」
        assertEquals(HomeworkDayStatus.NOT_STARTED, summary.items.single().status)
    }

    @Test
    fun `阶段作业不在今天时分母不含它`() {
        // 覆盖 day+1 .. day+7：今天尚未进入阶段范围
        val stage = StatsTestData.stageHomework(1L, content = "背单词", startEpochDay = day + 1)
        val summary = summarize(
            epochDay = day,
            items = listOf(stage),
            recordsByHomework = emptyMap(),
        )
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.totalCount)
        assertEquals(0.0, summary.completionRate, 0.0)

        // 阶段已结束（覆盖 day-7 .. day-1）同样不含它
        val ended = StatsTestData.stageHomework(2L, content = "练字", startEpochDay = day - 7)
        assertTrue(summarize(epochDay = day, items = listOf(ended), recordsByHomework = emptyMap()).isEmpty)
    }

    @Test
    fun `当天阶段作业已完成时计入分子`() {
        val stage = StatsTestData.stageHomework(1L, content = "背单词", startEpochDay = day - 1)
        val today = StatsTestData.homework(2L, content = "语文生字", priority = 101)
        val summary = summarize(
            epochDay = day,
            items = listOf(stage, today),
            recordsByHomework = mapOf(
                1L to listOf(StatsTestData.dayRecord(1L, day, status = HomeworkDayStatus.COMPLETED)),
            ),
        )
        assertEquals(2, summary.totalCount)
        assertEquals(1, summary.completedCount)
        assertEquals(0.5, summary.completionRate, 1e-9)
        // 阶段打卡进度与「当天是否完成」是两件事：两者都要能同时看到
        assertEquals(1, summary.stages.single().doneDays)
        assertEquals(7, summary.stages.single().totalDays)
    }

    @Test
    fun `当天无应做作业时分母为零且完成率不除零`() {
        // 当天作业的归属日是 day-2；阶段作业覆盖 day+1 起 —— 今天两者都不应做
        val summary = summarize(
            epochDay = day,
            items = listOf(
                StatsTestData.homework(1L, createdAtMillis = StatsTestData.at(-2 * 24 * 60)),
                StatsTestData.stageHomework(2L, startEpochDay = day + 1),
            ),
            recordsByHomework = mapOf(
                2L to listOf(StatsTestData.dayRecord(2L, day + 1)),
            ),
        )
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.totalCount)
        assertEquals(0, summary.completedCount)
        assertEquals(0.0, summary.completionRate, 0.0)
        assertNull(summary.mostPausedItem)
        assertTrue(summary.items.isEmpty())
        assertTrue(summary.stages.isEmpty())
    }

    @Test
    fun `未开始的当天作业同样进入分母`() {
        // 「应做」不等于「动过」：没开始计时、甚至没有任何每天详情的作业也必须计入分母
        val summary = summarize(
            epochDay = day,
            items = listOf(
                StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED, priority = 100),
                StatsTestData.homework(2L, priority = 101),
                StatsTestData.homework(3L, status = HomeworkStatus.PENDING, priority = 102),
            ),
            recordsByHomework = emptyMap(),
        )
        assertEquals(3, summary.totalCount)
        assertEquals(1, summary.completedCount)
        assertEquals(1.0 / 3.0, summary.completionRate, 1e-9)
    }

    @Test
    fun `阶段作业的历史每一天都应做即使是缺卡日`() {
        // 覆盖 day-1 .. day+5：历史查询里两天都出现，缺卡那一天显示「未完成」
        val stage = StatsTestData.stageHomework(1L, content = "背单词", startEpochDay = day - 1)
        val summaries = StatsCalculations.summarizeRange(
            fromEpochDay = day - 1,
            toEpochDay = day,
            todayEpochDay = day,
            items = listOf(stage),
            recordsByHomework = emptyMap(),
            zoneId = zone,
        )
        assertEquals(listOf(day, day - 1), summaries.map { it.epochDay })
        assertEquals(HomeworkDayStatus.NOT_STARTED, summaries.first().items.single().status)
        assertEquals(HomeworkDayStatus.MISSED, summaries.last().items.single().status)
        assertEquals(0, summaries.first().completedCount)
    }

    // ---- 二、阶段作业按天取值（不串天） ----

    @Test
    fun `同一条阶段作业的不同天各自独立取值`() {
        val stage = StatsTestData.stageHomework(1L, content = "背单词", startEpochDay = day - 1)
        val records = listOf(
            StatsTestData.dayRecord(
                homeworkId = 1L,
                epochDay = day - 1,
                status = HomeworkDayStatus.COMPLETED,
                actualMinutes = 20,
                pauseCount = 1,
                pausedTotalMinutes = 3,
            ),
            StatsTestData.dayRecord(
                homeworkId = 1L,
                epochDay = day,
                status = HomeworkDayStatus.IN_PROGRESS,
                startedAtMillis = StatsTestData.at(5),
                actualMinutes = 8,
                pauseCount = 2,
                pausedTotalMinutes = 4,
            ),
        )

        val yesterday = StatsCalculations.dayDetail(stage, records, day - 1, todayEpochDay = day, zoneId = zone)
        val today = StatsCalculations.dayDetail(stage, records, day, todayEpochDay = day, zoneId = zone)

        assertEquals(HomeworkDayStatus.COMPLETED, yesterday.dayStatus)
        assertEquals(20 * StatsTestData.MINUTE, yesterday.elapsedMillis)
        assertEquals(3 * StatsTestData.MINUTE, yesterday.pausedTotalMillis)
        assertEquals(1, yesterday.pauseCount)

        assertEquals(HomeworkDayStatus.IN_PROGRESS, today.dayStatus)
        assertEquals(8 * StatsTestData.MINUTE, today.elapsedMillis)
        assertEquals(4 * StatsTestData.MINUTE, today.pausedTotalMillis)
        assertEquals(2, today.pauseCount)

        // 两天互不累加（不存在「阶段总计」口径）：任意一天都只反映自己
        assertEquals(28 * StatsTestData.MINUTE, yesterday.elapsedMillis + today.elapsedMillis)
        assertTrue(yesterday.elapsedMillis != today.elapsedMillis)
    }

    @Test
    fun `同一条阶段作业不同天的当日盘点互不串天`() {
        val stage = StatsTestData.stageHomework(1L, content = "背单词", startEpochDay = day - 1)
        val recordsByHomework = mapOf(
            1L to listOf(
                StatsTestData.dayRecord(
                    homeworkId = 1L,
                    epochDay = day - 1,
                    status = HomeworkDayStatus.COMPLETED,
                    pauseCount = 3,
                    pausedTotalMinutes = 9,
                ),
            ),
        )
        val yesterday = summarize(epochDay = day - 1, items = listOf(stage), recordsByHomework = recordsByHomework)
        val today = summarize(epochDay = day, items = listOf(stage), recordsByHomework = recordsByHomework)

        assertEquals(1, yesterday.completedCount)
        assertEquals(3, yesterday.pauseCount)
        assertEquals(9 * StatsTestData.MINUTE, yesterday.pausedTotalMillis)

        assertEquals(0, today.completedCount)
        assertEquals(0, today.pauseCount)
        assertEquals(0L, today.pausedTotalMillis)
        assertNull(today.mostPausedItem)
    }

    // ---- 三、暂停三项取自当天详情 ----

    @Test
    fun `暂停次数与总时长只取当天详情`() {
        val summary = summarize(
            epochDay = day,
            items = listOf(
                StatsTestData.homework(1L, priority = 100),
                StatsTestData.homework(2L, priority = 101),
            ),
            recordsByHomework = mapOf(
                1L to listOf(
                    StatsTestData.dayRecord(1L, day, pauseCount = 2, pausedTotalMinutes = 6),
                    StatsTestData.dayRecord(1L, day - 1, pauseCount = 5, pausedTotalMinutes = 50),
                ),
                2L to listOf(StatsTestData.dayRecord(2L, day, pauseCount = 1, pausedTotalMinutes = 4)),
            ),
        )
        // 只有落在当天的详情参与：2 + 1 = 3 次、6 + 4 = 10 分钟（昨天那 5 次/50 分钟不计入）
        assertEquals(3, summary.pauseCount)
        assertEquals(10 * StatsTestData.MINUTE, summary.pausedTotalMillis)
    }

    @Test
    fun `暂停最久项取自当天详情而非其他日子`() {
        val summary = summarize(
            epochDay = day,
            items = listOf(
                StatsTestData.homework(1L, content = "语文生字", priority = 100),
                StatsTestData.homework(2L, content = "数学口算", priority = 101),
            ),
            recordsByHomework = mapOf(
                1L to listOf(
                    StatsTestData.dayRecord(1L, day, pauseCount = 1, pausedTotalMinutes = 2),
                    // 昨天暂停更久，但今天这一项只按今天的 2 分钟参与评选
                    StatsTestData.dayRecord(1L, day - 1, pauseCount = 4, pausedTotalMinutes = 40),
                ),
                2L to listOf(StatsTestData.dayRecord(2L, day, pauseCount = 1, pausedTotalMinutes = 5)),
            ),
        )
        assertEquals(2L, summary.mostPausedItem?.homeworkId)
        assertEquals("数学口算", summary.mostPausedItem?.content)
        assertEquals(5 * StatsTestData.MINUTE, summary.mostPausedItem?.pausedMillis)
        assertEquals(1, summary.mostPausedItem?.pauseCount)
    }

    @Test
    fun `当天没有任何暂停时不产生暂停最久项`() {
        val summary = summarize(
            epochDay = day,
            items = listOf(
                StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED),
            ),
            recordsByHomework = mapOf(
                1L to listOf(
                    StatsTestData.dayRecord(1L, day, status = HomeworkDayStatus.COMPLETED),
                    StatsTestData.dayRecord(1L, day - 1, pauseCount = 3, pausedTotalMinutes = 30),
                ),
            ),
        )
        assertEquals(1, summary.completedCount)
        assertEquals(0, summary.pauseCount)
        assertEquals(0L, summary.pausedTotalMillis)
        assertNull(summary.mostPausedItem)
    }
}
