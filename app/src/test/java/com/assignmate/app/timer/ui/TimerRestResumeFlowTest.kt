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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 休息页「起点持久化 + 幂等重建」与「离开页面停止播报」的流程单测。
 *
 * 覆盖评审补充项：休息倒计时起点原先只在内存中，进程回收后重新进入会从 10:00 重新起算，
 * 现在起点落盘于 TimerRestStartStore（学生 + 作业维度），窗口内可接着原起点倒计时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerRestResumeFlowTest {

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
    fun `无持久化起点时从完整休息时长起算并写入起点`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertFalse("首次休息不可能是「重建」", state.restRebuilt)
        assertEquals(TimerConstants.REST_DURATION_MILLIS, state.remainingMillis)
        assertEquals(
            "进入页面应写入起点供进程回收后重建",
            TimerTestEnv.FIXED_MILLIS,
            env.restStartStore.restStartedAtMillis(TimerTestEnv.STUDENT_ID, homework.id),
        )
    }

    @Test
    fun `命中窗口内的持久化起点时接着原起点倒计时`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val fiveMinutesAgo = TimerTestEnv.FIXED_MILLIS - 5 * MINUTE
        env.restStartStore.markRestStarted(TimerTestEnv.STUDENT_ID, homework.id, fiveMinutesAgo)
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertTrue("应从持久化起点重建", state.restRebuilt)
        assertEquals(fiveMinutesAgo, state.restStartedAtMillis)
        assertEquals("剩余应为 5 分钟而非整段 10 分钟", 5 * MINUTE, state.remainingMillis)
    }

    @Test
    fun `起点已超出休息窗口时以当前时刻重新起算`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val longAgo = TimerTestEnv.FIXED_MILLIS - TimerConstants.REST_RESUME_WINDOW_MILLIS - MINUTE
        env.restStartStore.markRestStarted(TimerTestEnv.STUDENT_ID, homework.id, longAgo)
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertFalse(state.restRebuilt)
        assertEquals(TimerTestEnv.FIXED_MILLIS, state.restStartedAtMillis)
        assertEquals(TimerConstants.REST_DURATION_MILLIS, state.remainingMillis)
        assertEquals(
            "过期起点应被新的起点覆盖",
            TimerTestEnv.FIXED_MILLIS,
            env.restStartStore.restStartedAtMillis(TimerTestEnv.STUDENT_ID, homework.id),
        )
    }

    @Test
    fun `起点按学生与作业隔离互不覆盖`() = timerTest {
        val env = TimerTestEnv()
        val first = env.seedHomework(content = "第一项")
        val second = env.seedHomework(content = "第二项")
        env.restStartStore.markRestStarted(TimerTestEnv.STUDENT_ID, first.id, TimerTestEnv.FIXED_MILLIS - MINUTE)
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, second.id)

        assertEquals(
            "第一项（上一项）的起点不应被覆盖",
            TimerTestEnv.FIXED_MILLIS - MINUTE,
            env.restStartStore.restStartedAtMillis(TimerTestEnv.STUDENT_ID, first.id),
        )
        assertEquals(
            "第二项应写入自己的新起点",
            TimerTestEnv.FIXED_MILLIS,
            env.restStartStore.restStartedAtMillis(TimerTestEnv.STUDENT_ID, second.id),
        )
    }

    @Test
    fun `离开页面时打断未播完的语音`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val viewModel = track(restViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        viewModel.onLeavingPage()

        assertEquals(1, env.ttsPlayer.stopCount)
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

        /** 学生端不指定学生（按会话解析本人） */
        const val ROUTE_STUDENT_NONE = 0L

        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE
    }
}
