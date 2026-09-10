package com.assignmate.app.timer.ui

import com.assignmate.app.timer.domain.TimerPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行页操作可用性与界面锁定推导单测（纯函数 [executionActionStateOf] 与
 * [TimerExecutionUiState] 的派生属性）：按阶段给出可用按钮、计时进行中锁定作业编辑入口、
 * 已用时长展示取整。
 */
class TimerExecutionActionStateTest {

    @Test
    fun `未开始阶段仅可开始计时`() {
        val actions = executionActionStateOf(TimerPhase.IDLE)

        assertTrue(actions.canStart)
        assertFalse(actions.canPause)
        assertFalse(actions.canResume)
        assertFalse(actions.canComplete)
    }

    @Test
    fun `进行中阶段可有事走开或完成作业`() {
        val actions = executionActionStateOf(TimerPhase.RUNNING)

        assertFalse(actions.canStart)
        assertTrue(actions.canPause)
        assertFalse(actions.canResume)
        assertTrue(actions.canComplete)
    }

    @Test
    fun `暂停阶段可我回来啦或完成作业`() {
        val actions = executionActionStateOf(TimerPhase.PAUSED)

        assertFalse(actions.canStart)
        assertFalse(actions.canPause)
        assertTrue(actions.canResume)
        assertTrue(actions.canComplete)
    }

    @Test
    fun `已完成与休息阶段不提供操作按钮`() {
        listOf(TimerPhase.FINISHED, TimerPhase.RESTING).forEach { phase ->
            val actions = executionActionStateOf(phase)
            assertFalse(actions.canStart)
            assertFalse(actions.canPause)
            assertFalse(actions.canResume)
            assertFalse(actions.canComplete)
        }
    }

    @Test
    fun `计时进行中锁定编辑作业入口`() {
        assertTrue(TimerExecutionUiState(phase = TimerPhase.RUNNING).homeworkEditingLocked)
        assertTrue(TimerExecutionUiState(phase = TimerPhase.PAUSED).homeworkEditingLocked)
        assertFalse(TimerExecutionUiState(phase = TimerPhase.IDLE).homeworkEditingLocked)
        assertFalse(TimerExecutionUiState(phase = TimerPhase.FINISHED).homeworkEditingLocked)
        assertFalse(TimerExecutionUiState(phase = TimerPhase.RESTING).homeworkEditingLocked)
    }

    @Test
    fun `状态默认值为加载中且阶段为未开始`() {
        val state = TimerExecutionUiState()

        assertTrue(state.loading)
        assertEquals(TimerPhase.IDLE, state.phase)
        assertTrue(state.actions.canStart)
        assertEquals(0L, state.elapsedSeconds)
        assertFalse(state.busy)
    }

    @Test
    fun `已用时长展示秒数向上取整`() {
        assertEquals(0L, TimerExecutionUiState(elapsedMillis = 0L).elapsedSeconds)
        assertEquals(1L, TimerExecutionUiState(elapsedMillis = 1L).elapsedSeconds)
        assertEquals(1L, TimerExecutionUiState(elapsedMillis = 999L).elapsedSeconds)
        assertEquals(61L, TimerExecutionUiState(elapsedMillis = 60_001L).elapsedSeconds)
    }

    @Test
    fun `暂停状态透传暂停次数与累计时长`() {
        val state = TimerExecutionUiState(
            phase = TimerPhase.PAUSED,
            pauseCount = 2,
            pausedTotalMillis = 125_000L,
        )

        assertEquals(2, state.pauseCount)
        assertEquals(125L, state.pausedTotalMillis / 1_000L)
        assertTrue(state.actions.canResume)
    }
}
