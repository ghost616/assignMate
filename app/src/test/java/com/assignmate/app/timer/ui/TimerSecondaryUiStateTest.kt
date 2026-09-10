package com.assignmate.app.timer.ui

import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.timerTestHomework
import com.assignmate.app.timer.domain.TimerConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * timer 次级页面状态（下一项提示页 / 休息页 / 完成反馈页）的纯派生属性单测。
 *
 * 这些派生属性直接决定页面文案与跳转分支（进入完成反馈页、继续做这一项、休息结束提示），
 * 属测试说明第 9 项「UI 层可验证部分」的可单测投影。
 */
class TimerSecondaryUiStateTest {

    // ---- 下一项提示页 ----

    @Test
    fun `下一项页加载中或缺会话时不算全部完成`() {
        assertFalse(TimerNextItemUiState().allCompleted)
        assertFalse(TimerNextItemUiState(loading = false, missingSession = true).allCompleted)
        assertFalse(TimerNextItemUiState(loading = false, missingStudent = true).allCompleted)
    }

    @Test
    fun `下一项为空且数据就绪时判定清单全部完成`() {
        val state = TimerNextItemUiState(loading = false, nextItem = null, totalCount = 3, completedCount = 3)

        assertTrue(state.allCompleted)
        assertEquals("已完成 3 / 3 项", state.progressText)
    }

    @Test
    fun `仍有下一项时不算全部完成`() {
        val state = TimerNextItemUiState(
            loading = false,
            nextItem = timerTestHomework(id = 2L, status = HomeworkStatus.PENDING),
            totalCount = 3,
            completedCount = 1,
        )

        assertFalse(state.allCompleted)
        assertEquals("已完成 1 / 3 项", state.progressText)
    }

    @Test
    fun `下一项按钮文案按状态区分继续与开始`() {
        assertEquals(
            "继续做这一项",
            TimerNextItemUiState(nextItem = timerTestHomework(id = 1L, status = HomeworkStatus.IN_PROGRESS))
                .nextItemActionText,
        )
        assertEquals(
            "现在开始",
            TimerNextItemUiState(nextItem = timerTestHomework(id = 1L, status = HomeworkStatus.PENDING))
                .nextItemActionText,
        )
        assertEquals("现在开始", TimerNextItemUiState().nextItemActionText)
    }

    // ---- 休息页 ----

    @Test
    fun `休息页默认展示完整休息时长`() {
        val state = TimerRestUiState()

        assertEquals(TimerConstants.REST_DURATION_MINUTES, state.totalMinutes)
        assertEquals(600L, state.remainingSeconds)
        assertFalse(state.restFinished)
        assertTrue(state.loading)
        assertNull(state.restStartedAtMillis)
    }

    @Test
    fun `休息剩余秒数向上取整且归零时为 0`() {
        assertEquals(600L, TimerRestUiState(remainingMillis = TimerConstants.REST_DURATION_MILLIS).remainingSeconds)
        assertEquals(1L, TimerRestUiState(remainingMillis = 1L).remainingSeconds)
        assertEquals(1L, TimerRestUiState(remainingMillis = 999L).remainingSeconds)
        assertEquals(2L, TimerRestUiState(remainingMillis = 1_001L).remainingSeconds)
        assertEquals(0L, TimerRestUiState(remainingMillis = 0L, restFinished = true).remainingSeconds)
    }

    @Test
    fun `休息页家长未选定学生提示仅在数据就绪后出现`() {
        assertFalse(TimerRestUiState(missingStudent = true).missingStudentHint)
        assertTrue(TimerRestUiState(loading = false, missingStudent = true).missingStudentHint)
        assertFalse(TimerRestUiState(loading = false, missingStudent = false).missingStudentHint)
    }

    // ---- 完成反馈页 ----

    @Test
    fun `完成反馈页概览文案与全部完成判定`() {
        val allDone = TimerCompletionUiState(loading = false, totalCount = 5, completedCount = 5, remainingCount = 0)
        val partial = TimerCompletionUiState(loading = false, totalCount = 5, completedCount = 3, remainingCount = 2)

        assertEquals("今天完成了 5 / 5 项", allDone.progressText)
        assertTrue(allDone.allCompleted)
        assertNull(allDone.remainingText)

        assertEquals("今天完成了 3 / 5 项", partial.progressText)
        assertFalse(partial.allCompleted)
        assertEquals("还有 2 项没完成，休息好了再继续吧", partial.remainingText)
    }

    @Test
    fun `完成反馈页加载中或没有作业时不算全部完成`() {
        assertFalse(TimerCompletionUiState().allCompleted)
        assertNull(TimerCompletionUiState().remainingText)
        // 空清单：避免「0 项全部完成」的误判
        assertFalse(TimerCompletionUiState(loading = false, totalCount = 0, remainingCount = 0).allCompleted)
        assertFalse(TimerCompletionUiState(loading = false, missingSession = true).allCompleted)
    }

    @Test
    fun `完成反馈页默认表扬语为空串由进入页面时取定`() {
        assertEquals("", TimerCompletionUiState().praiseText)
    }
}
