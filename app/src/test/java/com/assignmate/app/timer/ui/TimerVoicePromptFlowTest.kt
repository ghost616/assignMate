package com.assignmate.app.timer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.timer.data.TimerPermissionKind
import com.assignmate.app.timer.data.TimerPermissionStatus
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.TimerTickerController
import com.assignmate.app.timer.data.TimerTickerInfo
import com.assignmate.app.timer.domain.TimerConstants
import com.assignmate.app.timer.domain.TimerPhase
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 到点提醒与语音引导的端到端（ViewModel 层）单测：验证「超时判定 → 鼓励语去重 → 播报」
 * 「休息/完成的语音链路所需状态」「完成作业取消到点提醒」「权限引导与拒绝不阻断计时」。
 *
 * 依赖装配：[TimerTestEnv] 提供真实仓库/协调器 + 替身闹钟（[FakeAlarmScheduler] 由 TimerTestEnv 持有）
 * 与替身 TTS 引擎，故可断言播报文案序列、闹钟调用与去重记录。
 *
 * 测试框架注记：执行页有走秒 `while(true) + delay` 长驻循环，必须经 [timerTest] 包裹并在 finally
 * 取消 viewModelScope，否则 runTest 收尾推进虚拟时间时会与长驻循环互相追逐而死循环。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerVoicePromptFlowTest {

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
    fun `超时后播报鼓励语并记录去重时刻`() = timerTest {
        val env = TimerTestEnv()
        val homework = overdueHomework(env)
        val viewModel = track(executionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.ttsPlayer.spoken.clear()

        env.clock.advance(11 * MINUTE)
        tick()

        val state = viewModel.uiState.value
        assertTrue("超过预估时长应判定超时", state.overdue)
        assertTrue("应标记本次已提醒", state.overduePrompted)
        assertNotNull("应生成含完成数量的鼓励语", state.encouragementText)
        assertEquals("只播报一次鼓励语", 1, env.ttsPlayer.spoken.size)
        assertEquals(
            env.clock.currentTimeMillis(),
            env.overduePromptStore.lastPromptedAtMillis(homework.id),
        )
    }

    @Test
    fun `同一页面会话内不重复播报超时鼓励`() = timerTest {
        val env = TimerTestEnv()
        val homework = overdueHomework(env)
        val viewModel = track(executionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.ttsPlayer.spoken.clear()
        env.clock.advance(11 * MINUTE)
        tick()

        tick(5)

        assertEquals("走秒刷新不应重复播报", 1, env.ttsPlayer.spoken.size)
    }

    @Test
    fun `跨页面会话在去重间隔内不重复播报`() = timerTest {
        val env = TimerTestEnv()
        val homework = overdueHomework(env)
        val first = track(executionViewModel(env))
        first.start(ROUTE_STUDENT_NONE, homework.id)
        first.onStartClick()
        env.ttsPlayer.spoken.clear()
        env.clock.advance(11 * MINUTE)
        tick()
        assertEquals(1, env.ttsPlayer.spoken.size)

        // 新会话（重新进入页面）：距上次提醒仅 5 分钟，未达间隔 → 不播报
        env.ttsPlayer.spoken.clear()
        env.clock.advance(5 * MINUTE)
        val second = track(executionViewModel(env))
        second.start(ROUTE_STUDENT_NONE, homework.id)
        tick()

        assertTrue(env.ttsPlayer.spoken.isEmpty())
        assertFalse(second.uiState.value.overduePrompted)
        assertNotNull("跨会话仍复用上次记录", env.overduePromptStore.lastPromptedAtMillis(homework.id))
    }

    @Test
    fun `语音开关关闭时不播报但仍更新超时提示状态`() = timerTest {
        val env = TimerTestEnv()
        env.voiceSettings.setEnabled(false)
        val homework = overdueHomework(env)
        val viewModel = track(executionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        assertFalse("开关关闭应反映到界面状态", viewModel.uiState.value.voiceEnabled)
        viewModel.onStartClick()
        env.clock.advance(11 * MINUTE)
        tick()

        assertTrue("不应有任何播报", env.ttsPlayer.spoken.isEmpty())
        assertTrue(viewModel.uiState.value.overduePrompted)
        assertNotNull(viewModel.uiState.value.encouragementText)
    }

    @Test
    fun `未超时不播报鼓励语`() = timerTest {
        val env = TimerTestEnv()
        val homework = overdueHomework(env)
        val viewModel = track(executionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.ttsPlayer.spoken.clear()

        env.clock.advance(5 * MINUTE)
        tick()

        assertFalse(viewModel.uiState.value.overdue)
        assertTrue(env.ttsPlayer.spoken.isEmpty())
    }

    @Test
    fun `开始计时播报开始语且进入页面即同步到点提醒`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(
            startTime = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS + 30 * MINUTE),
        )
        val viewModel = track(executionViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        // 待完成 + 开始时间未过期 → 应设置到点提醒（触发时刻 = 开始时间 - 提前量）
        val scheduled = env.alarmScheduler.lastScheduledFor(homework.id)
        assertNotNull("进入页面应同步到点提醒", scheduled)
        assertEquals(TimerTestEnv.FIXED_MILLIS + 30 * MINUTE, scheduled?.triggerAtMillis)

        viewModel.onStartClick()

        assertEquals(TimerPhase.RUNNING, viewModel.uiState.value.phase)
        assertEquals(1, env.ttsPlayer.spoken.size)
        assertTrue(env.ttsPlayer.spoken.single().contains(homework.content))
    }

    @Test
    fun `完成作业播报完成语并取消到点提醒`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(
            startTime = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS + 30 * MINUTE),
        )
        val viewModel = track(executionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.ttsPlayer.spoken.clear()
        env.clock.advance(3 * MINUTE)

        viewModel.onCompleteClick()

        assertEquals(TimerPhase.FINISHED, viewModel.uiState.value.phase)
        assertEquals(1, env.ttsPlayer.spoken.size)
        assertTrue(env.ttsPlayer.spoken.single().contains(homework.content))
        assertTrue("作业已完成应取消其到点提醒", env.alarmScheduler.cancelled.contains(homework.id))
    }

    @Test
    fun `权限未授权时给出引导且拒绝不阻断计时`() = timerTest {
        val env = TimerTestEnv()
        env.permissionStatus = TimerPermissionStatus(
            notificationsGranted = false,
            exactAlarmGranted = false,
        )
        val homework = env.seedHomework()
        val viewModel = track(executionViewModel(env))
        val events = mutableListOf<TimerExecutionEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val hints = viewModel.uiState.value.permissionHints
        assertEquals(2, hints.size)
        assertEquals(TimerPermissionKind.NOTIFICATIONS, hints.first().permission)

        // 拒绝授权：只给可读提示，计时能力不受影响
        viewModel.onPermissionResult(granted = false)
        assertEquals(1, events.filterIsInstance<TimerExecutionEvent.ShowMessage>().size)

        viewModel.onStartClick()
        assertEquals(TimerPhase.RUNNING, viewModel.uiState.value.phase)
    }

    @Test
    fun `权限已授权时无引导且授权成功给出提示`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val viewModel = track(executionViewModel(env))
        val events = mutableListOf<TimerExecutionEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        assertTrue(viewModel.uiState.value.permissionHints.isEmpty())

        viewModel.onPermissionResult(granted = true)
        assertEquals(1, events.filterIsInstance<TimerExecutionEvent.ShowMessage>().size)
    }

    @Test
    fun `低版本点通知权限引导时只刷新真实状态不谎报成功`() = timerTest {
        val env = TimerTestEnv()
        env.permissionStatus = TimerPermissionStatus(
            notificationsGranted = false,
            exactAlarmGranted = true,
        )
        val homework = env.seedHomework()
        val viewModel = track(executionViewModel(env))
        val events = mutableListOf<TimerExecutionEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        // 权限仍为未授权时点击引导：按系统真实状态刷新，不谎报「已开启」
        viewModel.onPermissionRecheck()

        assertEquals(1, viewModel.uiState.value.permissionHints.size)
        assertEquals(TimerPermissionKind.NOTIFICATIONS, viewModel.uiState.value.permissionHints.first().permission)
        val message = events.filterIsInstance<TimerExecutionEvent.ShowMessage>().single().message
        assertTrue("提示应为中性口径：$message", message.contains("系统"))
        assertFalse(message.contains("已开启"))
    }

    @Test
    fun `离开页面时停止未播完的语音且不停计时`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val viewModel = track(executionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()

        viewModel.onLeavingPage()

        assertEquals("离开页面应打断未播完的播报", 1, env.ttsPlayer.stopCount)
        assertEquals("计时不受离开页面影响", TimerPhase.RUNNING, viewModel.uiState.value.phase)
    }

    // ---- 测试工具 ----

    /**
     * 推进虚拟时间以触发下一轮走秒：`advanceTimeBy` 不执行恰好落在边界时刻的任务，
     * 故在刷新间隔基础上 +1ms，保证走秒循环真的跑了一轮。
     */
    private fun TestScope.tick(times: Int = 1) {
        advanceTimeBy(times * TICK + 1L)
    }

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

    /** 构造「已排定开始时间且预估 10 分钟」的待完成作业，便于构造超时场景 */
    private suspend fun overdueHomework(env: TimerTestEnv) = env.seedHomework(
        estimatedMinutes = 10,
        startTime = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS),
    )

    private fun executionViewModel(env: TimerTestEnv): TimerExecutionViewModel = TimerExecutionViewModel(
        timerRepository = env.repository,
        homeworkRepository = env.homeworkRepository,
        authRepository = env.authRepository,
        tickerController = NoopTickerController(),
        reminderCoordinator = env.reminderCoordinator,
        voiceGuide = env.voiceGuide,
        voiceSettings = env.voiceSettings,
        overduePromptStore = env.overduePromptStore,
        permissionChecker = env.permissionChecker,
        clock = env.clock,
        zoneId = TimerTestEnv.ZONE,
    )

    /** 走秒服务替身（本套用例只关心语音与提醒，不校验服务调用序列） */
    private class NoopTickerController : TimerTickerController {

        override fun startTicker(info: TimerTickerInfo) = Unit

        override fun markPaused(info: TimerTickerInfo) = Unit

        override fun markRunning(info: TimerTickerInfo) = Unit

        override fun stopTicker() = Unit
    }

    private companion object {

        /** 学生端不指定学生（按会话解析本人） */
        const val ROUTE_STUDENT_NONE = 0L

        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE

        const val TICK = TimerConstants.TICK_INTERVAL_MILLIS
    }
}
