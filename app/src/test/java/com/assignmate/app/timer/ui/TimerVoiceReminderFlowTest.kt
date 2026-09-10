package com.assignmate.app.timer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.DataStoreTimerVoiceSettings
import com.assignmate.app.timer.data.TimerPermissionKind
import com.assignmate.app.timer.data.TimerPermissionStatus
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.TimerTickerController
import com.assignmate.app.timer.data.TimerTickerInfo
import com.assignmate.app.timer.domain.TimerConstants
import com.assignmate.app.timer.domain.TimerFeedback
import com.assignmate.app.timer.domain.TimerPhase
import com.assignmate.app.timer.domain.TimerSpeechTexts
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 语音引导与到点提醒在页面层的补充流程单测（在 TimerVoicePromptFlowTest 之外）：
 * - 休息页倒计时到点播报一次（下一项引导 / 全部完成表扬语）、走秒多轮不重复播报、开关关闭不播报但状态更新；
 * - 完成反馈页进入即播报表扬语 + 完成数量、开关关闭时静默；
 * - 下一项页进入即同步该学生全部提醒（应设的设、已完成的取消、不影响他人作业）；
 * - 执行页暂停/恢复播报、语音开关即时生效并落盘、权限状态刷新使引导消失、拒绝授权只提示不阻断。
 *
 * 测试框架注记：执行页/休息页存在 `while(true) + delay` 长驻循环，必须经 [timerTest] 包裹并在 finally
 * 取消 viewModelScope；推进走秒用 [tick]（`advanceTimeBy` 不执行恰好落在边界时刻的任务，故 +1ms）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerVoiceReminderFlowTest {

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

    // ---- 休息页：倒计时到点的语音引导 ----

    @Test
    fun `休息结束播报下一项引导且只播报一次`() = timerTest {
        val env = TimerTestEnv()
        val finished = env.seedHomework(status = HomeworkStatus.COMPLETED, content = "刚完成的作业")
        env.seedHomework(content = "下一项作业", estimatedMinutes = 20)
        val viewModel = track(restViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, finished.id)
        assertEquals("刚完成的作业", viewModel.uiState.value.finishedHomeworkContent)

        env.clock.advance(REST_MILLIS)
        tick()

        val state = viewModel.uiState.value
        assertTrue(state.restFinished)
        assertTrue(state.restAnnounced)
        assertEquals("下一项作业", state.nextItemContent)
        val announced = requireNotNull(state.announcedText)
        assertTrue(announced.contains("休息结束"))
        assertTrue(announced.contains("下一项作业"))
        assertEquals(listOf(announced), env.ttsPlayer.spoken)

        // 后续走秒不再重复播报
        env.clock.advance(30 * 1_000L)
        tick(3)

        assertEquals("休息结束引导只播报一次", 1, env.ttsPlayer.spoken.size)
    }

    @Test
    fun `休息结束且清单全部完成时播报表扬语与完成数量`() = timerTest {
        val env = TimerTestEnv()
        val finished = env.seedHomework(status = HomeworkStatus.COMPLETED, content = "唯一一项作业")
        val viewModel = track(restViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, finished.id)

        env.clock.advance(REST_MILLIS)
        tick()

        val state = viewModel.uiState.value
        assertNull(state.nextItemContent)
        val announced = requireNotNull(state.announcedText)
        assertTrue("应包含完成数量", announced.contains("今天完成了 1/1 项作业。"))
        assertTrue("应以语料池表扬语开头", TimerFeedback.PRAISE_TEXTS.any { announced.startsWith(it) })
        assertEquals(listOf(announced), env.ttsPlayer.spoken)
    }

    @Test
    fun `语音开关关闭时休息结束不播报但状态仍更新`() = timerTest {
        val env = TimerTestEnv()
        env.voiceSettings.setEnabled(false)
        val finished = env.seedHomework(status = HomeworkStatus.COMPLETED)
        env.seedHomework(content = "下一项作业")
        val viewModel = track(restViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, finished.id)

        env.clock.advance(REST_MILLIS)
        tick()

        val state = viewModel.uiState.value
        assertFalse(state.voiceEnabled)
        assertTrue(state.restFinished)
        assertTrue(state.restAnnounced)
        assertNotNull("文案仍应生成供页面展示", state.announcedText)
        assertTrue("开关关闭不应有任何播报", env.ttsPlayer.spoken.isEmpty())
    }

    // ---- 完成反馈页：表扬语播报 ----

    @Test
    fun `完成反馈页播报表扬语与完成数量`() = timerTest {
        val env = TimerTestEnv()
        env.seedHomework(status = HomeworkStatus.COMPLETED)
        env.seedHomework(status = HomeworkStatus.COMPLETED)
        env.seedHomework(status = HomeworkStatus.PENDING)
        val viewModel = track(completionViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        val state = viewModel.uiState.value
        assertTrue(state.voiceEnabled)
        assertTrue(state.announced)
        val announced = state.announcedText
        assertNotNull(announced)
        assertTrue(announced!!.contains("今天完成了 2/3 项作业。"))
        assertTrue(announced.startsWith(state.praiseText))
        assertEquals(listOf(announced), env.ttsPlayer.spoken)
    }

    @Test
    fun `语音开关关闭时完成反馈页静默但状态更新`() = timerTest {
        val env = TimerTestEnv()
        env.voiceSettings.setEnabled(false)
        env.seedHomework(status = HomeworkStatus.COMPLETED)
        val viewModel = track(completionViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        val state = viewModel.uiState.value
        assertFalse(state.voiceEnabled)
        assertTrue(state.announced)
        assertNotNull(state.announcedText)
        assertTrue(env.ttsPlayer.spoken.isEmpty())
        assertTrue(state.praiseText in TimerFeedback.PRAISE_TEXTS)
    }

    // ---- 下一项页：批量同步到点提醒 ----

    @Test
    fun `进入下一项页同步该学生全部提醒且不影响他人作业`() = timerTest {
        val env = TimerTestEnv()
        val pending = env.seedHomework(
            content = "待完成作业",
            startTime = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS + 30 * MINUTE),
        )
        val completed = env.seedHomework(
            status = HomeworkStatus.COMPLETED,
            content = "已完成作业",
            startTime = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS + 30 * MINUTE),
        )
        val othersHomework = env.seedHomework(
            studentId = 99L,
            content = "他人作业",
            startTime = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS + 30 * MINUTE),
        )
        val viewModel = track(nextItemViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        assertEquals(listOf(pending.id), env.alarmScheduler.scheduled.map { it.homeworkId })
        assertEquals(
            TimerTestEnv.FIXED_MILLIS + 30 * MINUTE,
            env.alarmScheduler.scheduled.single().triggerAtMillis,
        )
        assertEquals("已完成作业应取消提醒", listOf(completed.id), env.alarmScheduler.cancelled)
        assertFalse("他人作业不受本学生同步影响", env.alarmScheduler.cancelled.contains(othersHomework.id))
        assertFalse(env.alarmScheduler.scheduled.any { it.homeworkId == othersHomework.id })
    }

    // ---- 执行页：语音、开关与权限 ----

    @Test
    fun `执行页开始暂停恢复各播报一次`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(content = "语文生字")
        val viewModel = track(executionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        assertTrue("进入页面不播报", env.ttsPlayer.spoken.isEmpty())

        viewModel.onStartClick()
        assertEquals(1, env.ttsPlayer.spoken.size)
        assertTrue(env.ttsPlayer.spoken.single().contains("语文生字"))

        env.ttsPlayer.spoken.clear()
        viewModel.onPauseClick()
        assertEquals(listOf(TimerSpeechTexts.paused()), env.ttsPlayer.spoken)

        env.ttsPlayer.spoken.clear()
        viewModel.onResumeClick()
        assertEquals(listOf(TimerSpeechTexts.resumed()), env.ttsPlayer.spoken)
    }

    @Test
    fun `执行页切换语音开关即时生效并落盘`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val viewModel = track(executionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        assertTrue(viewModel.uiState.value.voiceEnabled)

        viewModel.onVoiceToggle(false)

        assertFalse(viewModel.uiState.value.voiceEnabled)
        assertFalse("开关应落盘", env.voiceSettings.isEnabled())
        assertFalse(
            env.keyValueStore.getBoolean(DataStoreTimerVoiceSettings.KEY_VOICE_ENABLED, true),
        )

        viewModel.onStartClick()
        assertEquals(TimerPhase.RUNNING, viewModel.uiState.value.phase)
        assertTrue("开关关闭后开始计时不应播报", env.ttsPlayer.spoken.isEmpty())

        viewModel.onVoiceToggle(true)
        assertTrue(viewModel.uiState.value.voiceEnabled)
        assertTrue(env.voiceSettings.isEnabled())
    }

    @Test
    fun `执行页权限状态刷新后引导随系统设置变化`() = timerTest {
        val env = TimerTestEnv()
        env.permissionStatus = TimerPermissionStatus(notificationsGranted = false, exactAlarmGranted = false)
        val homework = env.seedHomework()
        val viewModel = track(executionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        assertEquals(2, viewModel.uiState.value.permissionHints.size)

        // 模拟用户从系统设置返回并已授权：页面恢复时刷新 → 引导消失
        env.permissionStatus = TimerPermissionStatus(notificationsGranted = true, exactAlarmGranted = true)
        viewModel.refreshPermissionStatus()
        assertTrue(viewModel.uiState.value.permissionHints.isEmpty())

        // 仅精确闹钟未授权：引导只给精确闹钟一项
        env.permissionStatus = TimerPermissionStatus(notificationsGranted = true, exactAlarmGranted = false)
        viewModel.refreshPermissionStatus()
        assertEquals(
            listOf(TimerPermissionKind.EXACT_ALARM),
            viewModel.uiState.value.permissionHints.map { it.permission },
        )
    }

    @Test
    fun `权限申请结果提示不影响计时且拒绝仅提示`() = timerTest {
        val env = TimerTestEnv()
        env.permissionStatus = TimerPermissionStatus(notificationsGranted = false, exactAlarmGranted = true)
        val homework = env.seedHomework()
        val viewModel = track(executionViewModel(env))
        val events = mutableListOf<TimerExecutionEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        viewModel.onPermissionResult(granted = false)

        val denied = events.filterIsInstance<TimerExecutionEvent.ShowMessage>().map { it.message }
        assertEquals(1, denied.size)
        assertTrue("拒绝文案应说明不影响计时：${denied.single()}", denied.single().contains("不影响计时"))

        // 拒绝授权后依然可以开始计时
        viewModel.onStartClick()
        assertEquals(TimerPhase.RUNNING, viewModel.uiState.value.phase)
    }

    // ---- 测试工具 ----

    /** 推进虚拟时间以触发下一轮走秒/倒计时：边界时刻 +1ms 保证任务真的执行 */
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
    )

    private fun restViewModel(env: TimerTestEnv): TimerRestViewModel = TimerRestViewModel(
        homeworkRepository = env.homeworkRepository,
        authRepository = env.authRepository,
        voiceGuide = env.voiceGuide,
        voiceSettings = env.voiceSettings,
        restStartStore = env.restStartStore,
        clock = env.clock,
    )

    private fun completionViewModel(env: TimerTestEnv): TimerCompletionViewModel = TimerCompletionViewModel(
        homeworkRepository = env.homeworkRepository,
        authRepository = env.authRepository,
        voiceGuide = env.voiceGuide,
        voiceSettings = env.voiceSettings,
    )

    private fun nextItemViewModel(env: TimerTestEnv): TimerNextItemViewModel = TimerNextItemViewModel(
        homeworkRepository = env.homeworkRepository,
        timerRepository = env.repository,
        authRepository = env.authRepository,
        reminderCoordinator = env.reminderCoordinator,
    )

    /** 走秒服务替身（本套用例只关心语音与提醒，不校验服务调用序列） */
    private class NoopTickerController : TimerTickerController {

        override fun startTicker(info: TimerTickerInfo) = Unit

        override fun markPaused(info: TimerTickerInfo) = Unit

        override fun markRunning(info: TimerTickerInfo) = Unit

        override fun stopTicker() = Unit
    }

    private companion object {

        const val ROUTE_STUDENT_NONE = 0L
        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE
        const val TICK = TimerConstants.TICK_INTERVAL_MILLIS
        const val REST_MILLIS = TimerConstants.REST_DURATION_MILLIS
    }
}
