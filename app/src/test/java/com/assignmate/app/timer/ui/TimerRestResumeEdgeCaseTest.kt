package com.assignmate.app.timer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.domain.TimerConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 休息页「起点持久化 + 幂等重建」的边界补充单测（在 TimerRestResumeFlowTest 之外）：
 * 窗口恰好边界、起点晚于当前时刻（时钟回拨）、未指定作业时不读不写、显式清除后按新起点重建。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerRestResumeEdgeCaseTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val createdViewModels = mutableListOf<ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        cancelCreatedViewModels()
        Dispatchers.resetMain()
    }

    @Test
    fun `起点恰好落在复用窗口边界时仍接着原起点`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.restStartStore.markRestStarted(
            TimerTestEnv.STUDENT_ID,
            homework.id,
            TimerTestEnv.FIXED_MILLIS - TimerConstants.REST_RESUME_WINDOW_MILLIS,
        )
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertTrue("窗口为闭区间（<=），边界上应复用原起点", state.restRebuilt)
        assertEquals(TimerTestEnv.FIXED_MILLIS - TimerConstants.REST_RESUME_WINDOW_MILLIS, state.restStartedAtMillis)
        assertEquals("恰好走满整段休息：剩余为 0", 0L, state.remainingMillis)
    }

    @Test
    fun `起点晚于当前时刻时不复用而是重新起算`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        // 时钟回拨：持久化起点落在未来
        env.restStartStore.markRestStarted(TimerTestEnv.STUDENT_ID, homework.id, TimerTestEnv.FIXED_MILLIS + MINUTE)
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertFalse("未来起点不可复用", state.restRebuilt)
        assertEquals(TimerTestEnv.FIXED_MILLIS, state.restStartedAtMillis)
        assertEquals(TimerConstants.REST_DURATION_MILLIS, state.remainingMillis)
        assertEquals(
            "未来起点应被当前时刻覆盖",
            TimerTestEnv.FIXED_MILLIS,
            env.restStartStore.restStartedAtMillis(TimerTestEnv.STUDENT_ID, homework.id),
        )
    }

    @Test
    fun `未指定作业时不读取也不写入起点`() = timerTest {
        val env = TimerTestEnv()
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, ROUTE_HOMEWORK_NONE)

        val state = viewModel.uiState.value
        assertFalse(state.restRebuilt)
        assertEquals(TimerTestEnv.FIXED_MILLIS, state.restStartedAtMillis)
        assertEquals(TimerConstants.REST_DURATION_MILLIS, state.remainingMillis)
        assertNull("未指定作业时无「已完成作业」内容", state.finishedHomeworkContent)
        assertNull(
            "未指定作业时不应写入任何起点（否则会污染 -1 号键）",
            env.restStartStore.restStartedAtMillis(TimerTestEnv.STUDENT_ID, ROUTE_HOMEWORK_NONE),
        )
    }

    @Test
    fun `清除起点后重新进入按新起点起算`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.restStartStore.markRestStarted(TimerTestEnv.STUDENT_ID, homework.id, TimerTestEnv.FIXED_MILLIS - 5 * MINUTE)
        env.restStartStore.clear(TimerTestEnv.STUDENT_ID, homework.id)
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertFalse("起点已清除：不可能是重建", state.restRebuilt)
        assertEquals(TimerConstants.REST_DURATION_MILLIS, state.remainingMillis)
        assertEquals(
            TimerTestEnv.FIXED_MILLIS,
            env.restStartStore.restStartedAtMillis(TimerTestEnv.STUDENT_ID, homework.id),
        )
    }

    @Test
    fun `重复进入不覆盖已持久化的起点`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val viewModel = track(restViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        val firstStart = requireNotNull(viewModel.uiState.value.restStartedAtMillis)
        env.clock.advance(MINUTE)

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        assertEquals(firstStart, viewModel.uiState.value.restStartedAtMillis)
        assertEquals(
            "重复进入不得把起点推进（否则休息会无限续期）",
            firstStart,
            env.restStartStore.restStartedAtMillis(TimerTestEnv.STUDENT_ID, homework.id),
        )
    }

    // ---- 测试工具 ----

    private fun timerTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            cancelCreatedViewModels()
        }
    }

    private fun <T : ViewModel> track(viewModel: T): T {
        createdViewModels += viewModel
        return viewModel
    }

    private fun cancelCreatedViewModels() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
    }

    private fun restViewModel(env: TimerTestEnv): TimerRestViewModel = TimerRestViewModel(
        homeworkRepository = env.homeworkRepository,
        authRepository = env.authRepository,
        voiceGuide = env.voiceGuide,
        voiceSettings = env.voiceSettings,
        restStartStore = env.restStartStore,
        clock = env.clock,
    )

    private companion object {

        const val ROUTE_STUDENT_NONE = 0L

        /** 未指定作业（与 TimerDestination.ARG_HOMEWORK_ID_NONE 一致） */
        const val ROUTE_HOMEWORK_NONE = -1L

        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE
    }
}
