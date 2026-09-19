package com.assignmate.app.homework.domain

import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「作业每天详情」推导单测（纯函数，[StageDayRecords]）：
 *
 * 1) 缺卡规则边界（需求核心）：**截止时刻正点** / **刚过截止时刻** / **跨午夜**——
 *    当天到点后仍可完成（[HomeworkDayState.PENDING]），只有跨入次日才判为未完成且不可补做；
 * 2) 阶段覆盖日与进度分母（已打卡 N/M 天）、覆盖区间外记录不计入分子；
 * 3) 阶段开始前 / 结束后对学生不可见（[StageDayRecords.isVisibleOnDay]）；
 * 4) 阶段结束判定与未完成天数统计。
 */
class StageDayRecordsTest {

    // ---- 缺卡规则：三类边界 ----

    @Test
    fun `截止时刻正点当天仍可完成`() {
        val item = stage(startEpochDay = TODAY - 1, deadlineTime = LocalTime.of(21, 0))
        val records = listOf(record(TODAY - 1, HomeworkDayStatus.COMPLETED))

        assertEquals(
            "正点不算已过，当天仍为待完成",
            HomeworkDayState.PENDING,
            StageDayRecords.todayOutcome(item, records, TODAY, ZONE)?.state,
        )
        assertTrue(StageDayRecords.isTodayActionable(item, records, TODAY, ZONE))
    }

    @Test
    fun `刚过当天截止时刻仍可完成不判缺卡`() {
        // 固定时钟的本地钟面时刻（与业务时区无关地取实际值，避免测试依赖运行环境时区）
        val clockLocalTime = Instant.ofEpochMilli(CLOCK_MILLIS_MS).atZone(ZONE).toLocalTime()
        // 每日截止时刻取「当天凌晨 00:00 之后 1 分钟」——实际业务时刻必然已过该点，
        // 对应需求边界：当天过了截止时刻仍可完成（只有跨入次日才判未完成）
        val deadlineTime = LocalTime.MIDNIGHT.plusMinutes(1)
        assertTrue(
            "测试前提：实际钟面时刻应已过当日 " + deadlineTime + "（实际 " + clockLocalTime + "）",
            clockLocalTime.isAfter(deadlineTime),
        )

        val item = stage(startEpochDay = TODAY - 2, deadlineTime = deadlineTime)
        assertEquals("阶段起始日应从 deadline 编码还原", TODAY - 2, item.stageStartEpochDay)
        assertEquals(deadlineTime, item.dailyDeadlineTime)

        val records = listOf(
            record(TODAY - 2, HomeworkDayStatus.COMPLETED),
            record(TODAY - 1, HomeworkDayStatus.COMPLETED),
        )

        assertEquals(
            "当天到点后仍可完成",
            HomeworkDayState.PENDING,
            StageDayRecords.todayOutcome(item, records, TODAY, ZONE)?.state,
        )
        assertTrue(StageDayRecords.isTodayActionable(item, records, TODAY, ZONE))
        assertEquals(2, StageDayRecords.progressOf(item, records, TODAY, ZONE)?.completedDays)
    }

    @Test
    fun `跨入次日后前一天判为未完成且不可补做`() {
        val item = stage(startEpochDay = TODAY - 2, deadlineTime = LocalTime.of(8, 0))
        assertEquals("阶段起始日应从 deadline 编码还原", TODAY - 2, item.stageStartEpochDay)

        // 记录：首日已完成、昨日缺卡、今天进行中；次日视角下今天尚未过去
        val records = listOf(
            record(TODAY - 2, HomeworkDayStatus.COMPLETED),
            record(TODAY, HomeworkDayStatus.IN_PROGRESS),
        )

        assertEquals(
            "同一自然日（昨天）在次日视角下判为未完成",
            HomeworkDayState.MISSED,
            StageDayRecords.stateOf(
                epochDay = TODAY - 1,
                startEpochDay = item.stageStartEpochDay!!,
                coveredDays = item.stageCoveredDays,
                todayEpochDay = TODAY,
                isCompleted = false,
            ),
        )
        assertFalse(
            "缺卡的天不可补做",
            StageDayRecords.stateOf(
                epochDay = TODAY - 1,
                startEpochDay = item.stageStartEpochDay!!,
                coveredDays = item.stageCoveredDays,
                todayEpochDay = TODAY,
                isCompleted = false,
            ).isActionable,
        )
        assertEquals(
            "仅今天（尚未过去）不缺卡",
            1,
            StageDayRecords.progressOf(item, records, TODAY, ZONE)?.missedDays,
        )
        assertEquals(1, StageDayRecords.progressOf(item, records, TODAY, ZONE)?.completedDays)
        assertTrue(
            "今天（进行中、未完成）仍可完成",
            StageDayRecords.isTodayActionable(item, records, TODAY, ZONE),
        )
    }

    @Test
    fun `跨午夜边界下一个自然日才判未完成`() {
        // 同一天：只要还是「今天」就不判缺卡（与钟点无关）
        assertEquals(
            HomeworkDayState.PENDING,
            StageDayRecords.stateOf(
                epochDay = TODAY,
                startEpochDay = TODAY - 1,
                coveredDays = 7,
                todayEpochDay = TODAY,
                isCompleted = false,
            ),
        )
        // 业务自然日推进到次日：立刻判为未完成（只与自然日有关，不做 UTC 毫秒折算）
        assertEquals(
            HomeworkDayState.MISSED,
            StageDayRecords.stateOf(
                epochDay = TODAY,
                startEpochDay = TODAY - 1,
                coveredDays = 7,
                todayEpochDay = TODAY + 1,
                isCompleted = false,
            ),
        )
    }

    @Test
    fun `阶段覆盖范围外的日子视为未到来`() {
        assertEquals(
            HomeworkDayState.NOT_ARRIVED,
            StageDayRecords.stateOf(
                epochDay = TODAY + 7,
                startEpochDay = TODAY,
                coveredDays = 7,
                todayEpochDay = TODAY,
                isCompleted = false,
            ),
        )
        assertTrue(StageDayRecords.isWithinCoverage(TODAY, TODAY, 7))
        assertTrue(StageDayRecords.isWithinCoverage(TODAY + 6, TODAY, 7))
        assertFalse(StageDayRecords.isWithinCoverage(TODAY + 7, TODAY, 7))
        assertFalse(StageDayRecords.isWithinCoverage(TODAY - 1, TODAY, 7))
    }

    @Test
    fun `已完成优先于缺卡判定`() {
        assertEquals(
            HomeworkDayState.COMPLETED,
            StageDayRecords.stateOf(
                epochDay = TODAY - 3,
                startEpochDay = TODAY - 3,
                coveredDays = 7,
                todayEpochDay = TODAY,
                isCompleted = true,
            ),
        )
    }

    // ---- 覆盖日与进度分母 ----

    @Test
    fun `阶段进度分母为覆盖天数且只统计覆盖区间内的完成记录`() {
        val item = stage(startEpochDay = TODAY - 6, deadlineTime = LocalTime.of(21, 0))
        assertEquals("阶段起始日应从 deadline 编码还原", TODAY - 6, item.stageStartEpochDay)

        val records = listOf(
            record(TODAY - 6, HomeworkDayStatus.COMPLETED),
            record(TODAY - 5, HomeworkDayStatus.COMPLETED),
            record(TODAY - 4, HomeworkDayStatus.COMPLETED),
            record(TODAY - 10, HomeworkDayStatus.COMPLETED), // 覆盖区间外：不计入分子
            record(TODAY, HomeworkDayStatus.IN_PROGRESS), // 未完成：不计入分子
        )

        val progress = StageDayRecords.progressOf(item, records, TODAY, ZONE)!!
        assertEquals(StageRange.ONE_WEEK.days, progress.coveredDays)
        assertEquals(3, progress.completedDays)
        assertEquals("已过去且未完成的天（含被跳过的 TODAY-3/-2/-1）", 3, progress.missedDays)
        assertEquals("已过去的天数含当天", 7, progress.elapsedDays)
        assertEquals(0, progress.remainingDays)
        assertEquals("阶段进度：已打卡 3/7 天", progress.progressText)
        assertFalse(progress.isEnded)
    }

    @Test
    fun `阶段最后一天是今天时阶段尚未结束`() {
        val item = stage(startEpochDay = TODAY - 6, deadlineTime = LocalTime.of(21, 0))
        val allDone = (0L until 7L).map { offset ->
            record(TODAY - 6 + offset, HomeworkDayStatus.COMPLETED)
        }

        val progress = StageDayRecords.progressOf(item, allDone, TODAY, ZONE)!!
        assertFalse("最后一天当天不算结束", progress.isEnded)
        assertTrue(progress.isAllCompleted)
        assertFalse(progress.isEndedWithMissedDays)
    }

    @Test
    fun `阶段结束后统计未完成天数`() {
        val item = stage(startEpochDay = TODAY - 8, deadlineTime = LocalTime.of(21, 0))
        val records = (0L until 5L).map { offset ->
            record(TODAY - 8 + offset, HomeworkDayStatus.COMPLETED)
        }

        val progress = StageDayRecords.progressOf(item, records, TODAY, ZONE)!!
        assertTrue("阶段已整体结束", progress.isEnded)
        assertEquals(5, progress.completedDays)
        assertEquals(2, progress.missedDays)
        assertTrue(progress.isEndedWithMissedDays)
        assertFalse(progress.isAllCompleted)
    }

    @Test
    fun `阶段覆盖区间内全部未完成时缺卡天数等于覆盖天数`() {
        val item = stage(startEpochDay = TODAY - 8, deadlineTime = LocalTime.of(21, 0))
        val progress = StageDayRecords.progressOf(item, emptyList(), TODAY, ZONE)!!

        assertEquals(StageRange.ONE_WEEK.days, progress.coveredDays)
        assertEquals(7, progress.missedDays)
        assertEquals(0, progress.completedDays)
        assertTrue(progress.isEndedWithMissedDays)
    }

    // ---- 学生可见性（阶段开始前不显示、结束后移出） ----

    @Test
    fun `阶段开始前与结束后对学生不可见`() {
        assertTrue(StageDayRecords.isVisibleOnDay(stage(startEpochDay = TODAY), TODAY, ZONE))
        assertTrue(StageDayRecords.isVisibleOnDay(stage(startEpochDay = TODAY - 6), TODAY, ZONE))
        assertFalse(
            "阶段开始前不显示",
            StageDayRecords.isVisibleOnDay(stage(startEpochDay = TODAY + 1), TODAY, ZONE),
        )
        assertFalse(
            "阶段结束后移出",
            StageDayRecords.isVisibleOnDay(stage(startEpochDay = TODAY - 7), TODAY, ZONE),
        )
    }

    @Test
    fun `当天作业始终可见`() {
        val today = todayItem()
        assertTrue(StageDayRecords.isVisibleOnDay(today, TODAY, ZONE))
        assertTrue(StageDayRecords.isVisibleOnDay(today, TODAY + 30, ZONE))
    }

    @Test
    fun `每日截止时刻文案与覆盖区间文案`() {
        val item = stage(startEpochDay = TODAY, deadlineTime = LocalTime.of(7, 5))

        assertEquals("每天 07:05 截止", StageDayRecords.dailyDeadlineText(item))
        assertNull(StageDayRecords.dailyDeadlineText(todayItem()))
        assertEquals(
            "覆盖：${LocalDate.ofEpochDay(TODAY)} 至 ${LocalDate.ofEpochDay(TODAY + 6)}（每天到点截止）",
            StageDayRecords.coverageText(TODAY, 7),
        )
        assertEquals(
            HomeworkDailyDeadlineCodec.instantAt(TODAY, LocalTime.of(7, 5), ZONE).toEpochMilli(),
            StageDayRecords.dailyDeadlineOf(item, TODAY, ZONE)?.toEpochMilli(),
        )
    }

    @Test
    fun `非阶段作业不产生阶段进度与今日状态`() {
        val today = todayItem()
        assertNull(StageDayRecords.progressOf(today, emptyList(), TODAY, ZONE))
        assertNull(StageDayRecords.todayOutcome(today, emptyList(), TODAY, ZONE))
        assertFalse(StageDayRecords.isTodayActionable(today, emptyList(), TODAY, ZONE))
    }

    // ---- 测试工具 ----

    private fun stage(
        id: Long = 1L,
        startEpochDay: Long,
        deadlineTime: LocalTime = LocalTime.of(21, 0),
    ): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, deadlineTime),
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.RECORDED,
        createdByRole = CreatorRole.PARENT,
        createdAt = HomeworkDailyDeadlineCodec.instantAt(startEpochDay, LocalTime.of(12, 0), ZONE),
    )

    private fun todayItem(id: Long = 1L): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "数学练习册",
        type = HomeworkType.TODAY,
        stageRange = null,
        deadline = null,
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.RECORDED,
        createdByRole = CreatorRole.PARENT,
        createdAt = HomeworkDailyDeadlineCodec.instantAt(TODAY, LocalTime.of(9, 0), ZONE),
    )

    private fun record(epochDay: Long, status: HomeworkDayStatus): HomeworkDailyRecord =
        HomeworkDailyRecord(
            id = epochDay,
            homeworkId = 1L,
            studentId = STUDENT_ID,
            epochDay = epochDay,
            status = status,
            createdAtMillis = CLOCK_MILLIS_MS,
        )

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 10L

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 与仓库测试固定时钟一致：2023-11-15T09:13:20+08:00（epoch 毫秒） */
        const val CLOCK_MILLIS_MS: Long = 1_700_000_000_000L

        /** 业务自然日口径的「今天」 */
        val TODAY: Long = LocalDate.of(2023, 11, 15).toEpochDay()
    }
}
