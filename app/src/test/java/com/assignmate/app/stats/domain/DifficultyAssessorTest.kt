package com.assignmate.app.stats.domain

import com.assignmate.app.homework.domain.HomeworkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DifficultyAssessor] 单测：困难度分级规则（无会话、快/正常/慢/吃力、频繁暂停、暂停降级、
 * 缺预估时长的兜底口径），以及可读提示文案与「尚未开始」文案。
 *
 * 阈值取自 [StatsConstants]，用例显式构造「刚好等于阈值」与「略超阈值」两侧的边界值。
 */
class DifficultyAssessorTest {

    private val minute = StatsTestData.MINUTE

    // ---- 分级 ----

    @Test
    fun `无执行会话时等级为暂无数据`() {
        val level = DifficultyAssessor.assess(
            status = HomeworkStatus.PENDING,
            estimatedMinutes = 30,
            elapsedMillis = 0L,
            pauseCount = 0,
            sessionCount = 0,
        )
        assertEquals(DifficultyLevel.UNKNOWN, level)
        assertFalse(level.needsAttention)
    }

    @Test
    fun `实际耗时明显低于预估且暂停少时为很顺利`() {
        val level = DifficultyAssessor.assess(
            status = HomeworkStatus.COMPLETED,
            estimatedMinutes = 30,
            elapsedMillis = 15 * minute,
            pauseCount = 0,
            sessionCount = 1,
        )
        assertEquals(DifficultyLevel.SMOOTH, level)
        assertFalse(level.needsAttention)
    }

    @Test
    fun `耗时与预估相当时为正常`() {
        val level = DifficultyAssessor.assess(
            status = HomeworkStatus.COMPLETED,
            estimatedMinutes = 30,
            elapsedMillis = 30 * minute,
            pauseCount = 1,
            sessionCount = 1,
        )
        assertEquals(DifficultyLevel.NORMAL, level)
        assertFalse(level.needsAttention)
    }

    @Test
    fun `耗时达到预估的一点三倍时为偏慢`() {
        val level = DifficultyAssessor.assess(
            status = HomeworkStatus.COMPLETED,
            estimatedMinutes = 30,
            elapsedMillis = (30 * StatsConstants.SLOW_RATIO).toLong() * minute,
            pauseCount = 1,
            sessionCount = 1,
        )
        assertEquals(DifficultyLevel.SLOW, level)
        assertTrue(level.needsAttention)
    }

    @Test
    fun `耗时达到预估两倍时为明显吃力`() {
        val level = DifficultyAssessor.assess(
            status = HomeworkStatus.COMPLETED,
            estimatedMinutes = 30,
            elapsedMillis = 60 * minute,
            pauseCount = 1,
            sessionCount = 1,
        )
        assertEquals(DifficultyLevel.CHALLENGING, level)
        assertTrue(level.needsAttention)
    }

    @Test
    fun `暂停次数达到频繁阈值时判定明显吃力（与耗时无关）`() {
        val level = DifficultyAssessor.assess(
            status = HomeworkStatus.COMPLETED,
            estimatedMinutes = 30,
            elapsedMillis = 10 * minute,
            pauseCount = StatsConstants.VERY_FREQUENT_PAUSE_COUNT,
            sessionCount = 1,
        )
        assertEquals(DifficultyLevel.CHALLENGING, level)
    }

    @Test
    fun `比预估快但多次暂停时降级为偏慢`() {
        val level = DifficultyAssessor.assess(
            status = HomeworkStatus.COMPLETED,
            estimatedMinutes = 30,
            elapsedMillis = 10 * minute,
            pauseCount = StatsConstants.FREQUENT_PAUSE_COUNT,
            sessionCount = 1,
        )
        assertEquals(DifficultyLevel.SLOW, level)
    }

    @Test
    fun `未完成作业的耗时仍在增长故不判定为很顺利`() {
        val level = DifficultyAssessor.assess(
            status = HomeworkStatus.IN_PROGRESS,
            estimatedMinutes = 30,
            elapsedMillis = 5 * minute,
            pauseCount = 0,
            sessionCount = 1,
        )
        assertEquals(DifficultyLevel.NORMAL, level)
    }

    @Test
    fun `缺预估时长时按绝对耗时兜底判定`() {
        val shortLevel = DifficultyAssessor.assess(
            status = HomeworkStatus.COMPLETED,
            estimatedMinutes = null,
            elapsedMillis = 20 * minute,
            pauseCount = 0,
            sessionCount = 1,
        )
        val longLevel = DifficultyAssessor.assess(
            status = HomeworkStatus.COMPLETED,
            estimatedMinutes = null,
            elapsedMillis = StatsConstants.LONG_RUNNING_MINUTES * minute,
            pauseCount = 0,
            sessionCount = 1,
        )
        assertEquals(DifficultyLevel.NORMAL, shortLevel)
        assertEquals(DifficultyLevel.SLOW, longLevel)
    }

    @Test
    fun `预估时长为零或负数时按缺预估兜底`() {
        assertEquals(
            DifficultyLevel.NORMAL,
            DifficultyAssessor.assess(
                status = HomeworkStatus.COMPLETED,
                estimatedMinutes = 0,
                elapsedMillis = minute,
                pauseCount = 0,
                sessionCount = 1,
            ),
        )
        assertEquals(
            DifficultyLevel.SLOW,
            DifficultyAssessor.assess(
                status = HomeworkStatus.COMPLETED,
                estimatedMinutes = 0,
                elapsedMillis = StatsConstants.LONG_RUNNING_MINUTES * minute,
                pauseCount = 0,
                sessionCount = 1,
            ),
        )
    }

    @Test
    fun `比值计算在预估缺失或非正数时返回空`() {
        assertEquals(null, DifficultyAssessor.ratioOf(actualMinutes = 10L, estimatedMinutes = null))
        assertEquals(null, DifficultyAssessor.ratioOf(actualMinutes = 10L, estimatedMinutes = 0))
    }

    @Test
    fun `比值计算在不足一分钟时按一分钟计`() {
        // 实际耗时不足 1 分钟时按 1 分钟计，避免比值退化为 0（那样任何短耗时都会显得「很顺利」）
        assertEquals(1.0 / 30.0, DifficultyAssessor.ratioOf(actualMinutes = 0L, estimatedMinutes = 30)!!, 1e-9)
    }

    // ---- 文案 ----

    @Test
    fun `无执行记录时提示为尚未开始计时`() {
        assertEquals(
            DifficultyAssessor.HINT_NOT_STARTED,
            DifficultyAssessor.hintOf(
                level = DifficultyLevel.UNKNOWN,
                estimatedMinutes = 30,
                elapsedMillis = 0L,
                pauseCount = 0,
                sessionCount = 0,
            ),
        )
    }

    @Test
    fun `偏慢提示包含暂停次数与耗时信号`() {
        val hint = DifficultyAssessor.hintOf(
            level = DifficultyLevel.SLOW,
            estimatedMinutes = 30,
            elapsedMillis = 45 * minute,
            pauseCount = 3,
            sessionCount = 1,
        )
        assertTrue(hint.contains("比预估慢较多"))
        assertTrue(hint.contains("暂停 3 次"))
    }

    @Test
    fun `明显吃力提示建议拆小步骤`() {
        val hint = DifficultyAssessor.hintOf(
            level = DifficultyLevel.CHALLENGING,
            estimatedMinutes = 30,
            elapsedMillis = 90 * minute,
            pauseCount = 2,
            sessionCount = 1,
        )
        assertTrue(hint.contains("比预估慢很多"))
        assertTrue(hint.contains("拆小步骤"))
    }

    @Test
    fun `正常提示给出实际用时`() {
        val hint = DifficultyAssessor.hintOf(
            level = DifficultyLevel.NORMAL,
            estimatedMinutes = 30,
            elapsedMillis = 28 * minute,
            pauseCount = 0,
            sessionCount = 1,
        )
        assertTrue(hint.contains("28 分钟"))
    }

    @Test
    fun `每个等级的标签均可读`() {
        assertEquals("很顺利", DifficultyLevel.SMOOTH.label)
        assertEquals("正常", DifficultyLevel.NORMAL.label)
        assertEquals("偏慢", DifficultyLevel.SLOW.label)
        assertEquals("明显吃力", DifficultyLevel.CHALLENGING.label)
        assertEquals("暂无数据", DifficultyLevel.UNKNOWN.label)
    }
}