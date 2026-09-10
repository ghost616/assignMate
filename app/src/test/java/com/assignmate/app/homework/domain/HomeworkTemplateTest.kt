package com.assignmate.app.homework.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录入模板展开规则单测：当天作业 1 条、阶段作业按阶段范围逐日展开，
 * 以及优先级步长、初始状态、未排定时间、deadline 覆盖约束等口径。
 */
class HomeworkTemplateTest {

    @Test
    fun `当天作业仅展开一天`() {
        val template = template(type = HomeworkType.TODAY)

        assertEquals(listOf(EPOCH_DAY), template.scheduleDays())
        assertEquals(EPOCH_DAY, template.lastEpochDay())
    }

    @Test
    fun `阶段作业按阶段范围逐日展开且升序`() {
        val template = template(type = HomeworkType.STAGE, stageRange = StageRange.ONE_WEEK)

        assertEquals((0L until 7L).map { EPOCH_DAY + it }, template.scheduleDays())
        assertEquals(EPOCH_DAY + 6, template.lastEpochDay())
    }

    @Test
    fun `两周与三周范围按自然天数展开`() {
        assertEquals(
            14,
            template(type = HomeworkType.STAGE, stageRange = StageRange.TWO_WEEKS).scheduleDays().size,
        )
        assertEquals(
            21,
            template(type = HomeworkType.STAGE, stageRange = StageRange.THREE_WEEKS).scheduleDays().size,
        )
    }

    @Test
    fun `一个月按固定三十天折算`() {
        val template = template(type = HomeworkType.STAGE, stageRange = StageRange.ONE_MONTH)

        assertEquals(HomeworkConstants.DAYS_PER_MONTH, template.scheduleDays().size)
        assertEquals(StageRange.ONE_MONTH.days, template.scheduleDays().size)
    }

    @Test
    fun `阶段覆盖最后一天正是截止日时满足约束`() {
        val lastDay = LocalDate.ofEpochDay(EPOCH_DAY + 6)
        val template = template(
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = lastDay.atTime(21, 0).atZone(ZONE).toInstant(),
        )

        assertTrue(template.fitsWithinDeadline())
    }

    @Test
    fun `截止日早于覆盖最后一天时不满足约束`() {
        val lastDay = LocalDate.ofEpochDay(EPOCH_DAY + 6)
        val template = template(
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = lastDay.minusDays(1).atTime(21, 0).atZone(ZONE).toInstant(),
        )

        assertTrue(!template.fitsWithinDeadline())
    }

    @Test
    fun `无截止时间视为满足约束`() {
        val template = template(type = HomeworkType.STAGE, stageRange = StageRange.ONE_MONTH)

        assertTrue(template.fitsWithinDeadline())
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
    fun `展开的作业项优先级按步长递增`() {
        val items = template(type = HomeworkType.STAGE, stageRange = StageRange.THREE_WEEKS).toItems(
            parentAccountId = PARENT_ID,
            studentId = STUDENT_ID,
            createdAt = CREATED_AT,
            firstPriority = FIRST_PRIORITY,
        )

        items.forEachIndexed { index, item ->
            assertEquals(
                FIRST_PRIORITY + index * HomeworkConstants.PRIORITY_STEP,
                item.priority,
            )
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
    fun `阶段作业展开时携带阶段范围与截止时间`() {
        val deadline = LocalDate.ofEpochDay(EPOCH_DAY + 6).atTime(21, 0).atZone(ZONE).toInstant()
        val items = template(
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = deadline,
        ).toItems(
            parentAccountId = PARENT_ID,
            studentId = STUDENT_ID,
            createdAt = CREATED_AT,
            firstPriority = FIRST_PRIORITY,
        )

        items.forEach { item ->
            assertEquals(StageRange.ONE_WEEK, item.stageRange)
            assertEquals(deadline, item.deadline)
        }
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
