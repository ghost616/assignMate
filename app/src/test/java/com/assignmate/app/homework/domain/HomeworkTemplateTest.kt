package com.assignmate.app.homework.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录入模板落库规则单测（**本轮按需求重写**）：
 * - 阶段作业不再逐日展开，**固定产出 1 条**作业项；
 * - 阶段范围只决定「覆盖起止日」（[HomeworkTemplate.coveredEpochDays]/[lastEpochDay]）与进度分母 M；
 * - 阶段截止时间是「每日时刻」（time-of-day），不再有「阶段覆盖末日 ≤ deadline 所在日」约束；
 * - 当天作业保持「日期 + 时刻」的绝对 deadline 语义。
 */
class HomeworkTemplateTest {

    @Test
    fun `当天作业覆盖一天`() {
        val template = template(type = HomeworkType.TODAY)

        assertEquals(listOf(EPOCH_DAY), template.coveredEpochDays())
        assertEquals(EPOCH_DAY, template.lastEpochDay())
        assertEquals(1, template.coveredDays)
    }

    @Test
    fun `阶段作业覆盖日按阶段范围升序且长度等于天数`() {
        val template = template(type = HomeworkType.STAGE, stageRange = StageRange.ONE_WEEK)

        assertEquals((0L until 7L).map { EPOCH_DAY + it }, template.coveredEpochDays())
        assertEquals(EPOCH_DAY + 6, template.lastEpochDay())
        assertEquals(StageRange.ONE_WEEK.days, template.coveredDays)
    }

    @Test
    fun `两周与三周范围覆盖天数正确`() {
        assertEquals(
            14,
            template(type = HomeworkType.STAGE, stageRange = StageRange.TWO_WEEKS).coveredDays,
        )
        assertEquals(
            21,
            template(type = HomeworkType.STAGE, stageRange = StageRange.THREE_WEEKS).coveredDays,
        )
    }

    @Test
    fun `一个月按固定三十天折算`() {
        val template = template(type = HomeworkType.STAGE, stageRange = StageRange.ONE_MONTH)

        assertEquals(HomeworkConstants.DAYS_PER_MONTH, template.coveredDays)
        assertEquals(StageRange.ONE_MONTH.days, template.coveredDays)
    }

    @Test
    fun `阶段作业固定产出一条且优先级不递增`() {
        val items = template(type = HomeworkType.STAGE, stageRange = StageRange.THREE_WEEKS).toItems(
            parentAccountId = PARENT_ID,
            studentId = STUDENT_ID,
            createdAt = CREATED_AT,
            firstPriority = FIRST_PRIORITY,
        )

        // 阶段作业 = 一个在阶段内需完成的作业项：恰好 1 条，优先级即首条优先级
        assertEquals(1, items.size)
        assertEquals(FIRST_PRIORITY, items.single().priority)
    }

    @Test
    fun `展开的作业项均为已记录且未排定时间`() {
        val items = template(type = HomeworkType.STAGE, stageRange = StageRange.ONE_WEEK).toItems(
            parentAccountId = PARENT_ID,
            studentId = STUDENT_ID,
            createdAt = CREATED_AT,
            firstPriority = FIRST_PRIORITY,
        )

        assertTrue(items.isNotEmpty())
        items.forEach { item ->
            assertEquals(HomeworkStatus.RECORDED, item.status)
            assertNull(item.startTime)
            assertNull(item.estimatedMinutes)
            assertEquals(PARENT_ID, item.parentAccountId)
            assertEquals(STUDENT_ID, item.studentId)
            assertEquals(CREATED_AT, item.createdAt)
            assertEquals(CreatorRole.PARENT, item.createdByRole)
            assertEquals(HomeworkTemplate.NEW_ITEM_ID, item.id)
        }
    }

    @Test
    fun `当天作业展开时清空阶段范围`() {
        val items = template(type = HomeworkType.TODAY, stageRange = StageRange.ONE_WEEK).toItems(
            parentAccountId = PARENT_ID,
            studentId = STUDENT_ID,
            createdAt = CREATED_AT,
            firstPriority = FIRST_PRIORITY,
        )

        assertEquals(1, items.size)
        assertNull(items.single().stageRange)
    }

    @Test
    fun `阶段作业展开时携带阶段范围与每日截止时刻`() {
        val items = template(
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 30)),
        ).toItems(
            parentAccountId = PARENT_ID,
            studentId = STUDENT_ID,
            createdAt = CREATED_AT,
            firstPriority = FIRST_PRIORITY,
        )

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(StageRange.ONE_WEEK, item.stageRange)
        assertEquals(LocalTime.of(21, 30), item.dailyDeadlineTime)
        assertEquals(EPOCH_DAY, item.stageStartEpochDay)
        assertEquals(EPOCH_DAY + 6L, item.stageLastEpochDay)
        assertEquals(StageRange.ONE_WEEK.days, item.stageCoveredDays)
    }

    @Test
    fun `阶段每日截止时刻可编码解码往返`() {
        val encoded = HomeworkDailyDeadlineCodec.encodeStageDaily(EPOCH_DAY, LocalTime.of(7, 5))

        assertEquals(LocalTime.of(7, 5), HomeworkDailyDeadlineCodec.decodeStageDaily(encoded.toEpochMilli()))
        assertEquals(
            EPOCH_DAY,
            HomeworkDailyDeadlineCodec.stageStartEpochDay(encoded.toEpochMilli()),
        )
        assertEquals(
            HomeworkDeadline.StageDaily(LocalTime.of(7, 5)),
            HomeworkDailyDeadlineCodec.decode(HomeworkType.STAGE, encoded.toEpochMilli()),
        )
    }

    @Test
    fun `当天作业 deadline 仍为绝对时刻`() {
        val deadline = LocalDate.ofEpochDay(EPOCH_DAY).atTime(20, 15).atZone(ZONE).toInstant()
        val item = template(type = HomeworkType.TODAY, deadline = deadline).toItems(
            parentAccountId = PARENT_ID,
            studentId = STUDENT_ID,
            createdAt = CREATED_AT,
            firstPriority = FIRST_PRIORITY,
        ).single()

        assertEquals(deadline, item.deadline)
        assertNull("当天作业没有每日时刻语义", item.dailyDeadlineTime)
        assertEquals(
            HomeworkDeadline.Absolute(deadline),
            HomeworkDailyDeadlineCodec.decode(HomeworkType.TODAY, deadline.toEpochMilli()),
        )
    }

    @Test
    fun `展开时去除内容首尾空白`() {
        val item = template(content = "  背古诗  ").toItems(
            parentAccountId = PARENT_ID,
            studentId = STUDENT_ID,
            createdAt = CREATED_AT,
            firstPriority = FIRST_PRIORITY,
        ).single()

        assertEquals("背古诗", item.content)
    }

    @Test
    fun `结束日与覆盖最后一天口径一致`() {
        val template = template(type = HomeworkType.STAGE, stageRange = StageRange.TWO_WEEKS)

        assertEquals(LocalDate.ofEpochDay(EPOCH_DAY + 13), template.endDate)
        assertEquals(LocalDate.ofEpochDay(EPOCH_DAY), template.startDate)
    }

    @Test
    fun `阶段范围枚举的标签与天数符合约定`() {
        assertEquals(7, StageRange.ONE_WEEK.days)
        assertEquals("一周", StageRange.ONE_WEEK.label)
        assertEquals("两周", StageRange.TWO_WEEKS.label)
        assertEquals("三周", StageRange.THREE_WEEKS.label)
        assertEquals("一个月", StageRange.ONE_MONTH.label)
        assertEquals(StageRange.ONE_WEEK, StageRange.fromName("ONE_WEEK"))
        assertNull(StageRange.fromName("NOT_EXIST"))
    }

    private fun template(
        content: String = "数学练习册 P12",
        type: HomeworkType = HomeworkType.TODAY,
        stageRange: StageRange? = null,
        deadline: Instant? = null,
        creatorRole: CreatorRole = CreatorRole.PARENT,
    ): HomeworkTemplate = HomeworkTemplate(
        content = content,
        type = type,
        stageRange = stageRange,
        deadline = deadline,
        creatorRole = creatorRole,
        startEpochDay = EPOCH_DAY,
        zoneId = ZONE,
    )

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L
        const val FIRST_PRIORITY = 3

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val EPOCH_DAY: Long = LocalDate.of(2026, 9, 10).toEpochDay()
        val CREATED_AT: Instant = Instant.ofEpochMilli(1_700_000_000_000L)
    }
}
