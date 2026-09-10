package com.assignmate.app.homework.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作业状态机单测：已记录 → 待完成 → 进行中 → 已完成的合法流转集合、
 * 幂等更新、终态回退（纠正误标记）与初始状态约定。
 */
class HomeworkStatusTest {

    @Test
    fun `录入后的初始状态为已记录`() {
        assertEquals(HomeworkStatus.RECORDED, HomeworkStatus.INITIAL)
    }

    @Test
    fun `已记录仅可流转到待完成`() {
        assertEquals(setOf(HomeworkStatus.PENDING), HomeworkStatus.RECORDED.allowedTransitions)
    }

    @Test
    fun `待完成可进入进行中或直接标记完成也可撤销回已记录`() {
        assertEquals(
            setOf(HomeworkStatus.RECORDED, HomeworkStatus.IN_PROGRESS, HomeworkStatus.COMPLETED),
            HomeworkStatus.PENDING.allowedTransitions,
        )
    }

    @Test
    fun `进行中可回到待完成或标记完成`() {
        assertEquals(
            setOf(HomeworkStatus.PENDING, HomeworkStatus.COMPLETED),
            HomeworkStatus.IN_PROGRESS.allowedTransitions,
        )
    }

    @Test
    fun `已完成只能回退到进行中`() {
        assertEquals(setOf(HomeworkStatus.IN_PROGRESS), HomeworkStatus.COMPLETED.allowedTransitions)
        assertTrue(HomeworkStatus.COMPLETED.isTerminal)
        assertFalse(HomeworkStatus.IN_PROGRESS.isTerminal)
    }

    @Test
    fun `非法流转被拒绝`() {
        assertFalse(HomeworkStatus.RECORDED.canTransitionTo(HomeworkStatus.IN_PROGRESS))
        assertFalse(HomeworkStatus.RECORDED.canTransitionTo(HomeworkStatus.COMPLETED))
        assertFalse(HomeworkStatus.COMPLETED.canTransitionTo(HomeworkStatus.PENDING))
        assertFalse(HomeworkStatus.COMPLETED.canTransitionTo(HomeworkStatus.RECORDED))
    }

    @Test
    fun `同状态幂等更新视为合法`() {
        HomeworkStatus.entries.forEach { status ->
            assertTrue("$status 的自更新应合法", status.canTransitionTo(status))
        }
    }

    @Test
    fun `状态标签为中文且与状态一一对应`() {
        assertEquals("已记录", HomeworkStatus.RECORDED.label)
        assertEquals("待完成", HomeworkStatus.PENDING.label)
        assertEquals("进行中", HomeworkStatus.IN_PROGRESS.label)
        assertEquals("已完成", HomeworkStatus.COMPLETED.label)
    }

    @Test
    fun `未知或空的状态名解析为 null 而非崩溃`() {
        assertNull(HomeworkStatus.fromName(null))
        assertNull(HomeworkStatus.fromName("UNKNOWN"))
        assertEquals(HomeworkStatus.PENDING, HomeworkStatus.fromName("PENDING"))
    }

    @Test
    fun `作业类型与录入者角色的解析与标签符合约定`() {
        assertEquals(HomeworkType.TODAY, HomeworkType.fromName("TODAY"))
        assertNull(HomeworkType.fromName("DAILY"))
        assertEquals("当天作业", HomeworkType.TODAY.label)
        assertEquals("阶段作业", HomeworkType.STAGE.label)

        assertEquals(CreatorRole.PARENT, CreatorRole.fromName("PARENT"))
        assertNull(CreatorRole.fromName(null))
        assertEquals("家长录入", CreatorRole.PARENT.label)
        assertEquals("学生录入", CreatorRole.STUDENT.label)
    }

    @Test
    fun `已排定与进行中标记由字段推导`() {
        val item = HomeworkItem(
            id = 1L,
            parentAccountId = 1L,
            studentId = 2L,
            content = "作业",
            type = HomeworkType.TODAY,
            stageRange = null,
            deadline = null,
            priority = 0,
            startTime = Instant.ofEpochMilli(SCHEDULED_MILLIS),
            estimatedMinutes = 30,
            status = HomeworkStatus.IN_PROGRESS,
            createdByRole = CreatorRole.PARENT,
            createdAt = Instant.ofEpochMilli(0L),
        )

        assertTrue(item.isScheduled)
        assertTrue(item.isInProgress)
        assertEquals(
            Instant.ofEpochMilli(SCHEDULED_MILLIS + 30 * 60_000L),
            item.estimatedFinishedAt,
        )
        assertFalse(item.isStage)
    }

    private companion object Constants {

        const val SCHEDULED_MILLIS = 1_700_000_000_000L
    }
}
