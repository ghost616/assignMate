package com.assignmate.app.timer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.domain.TimerFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * 下一项提示页 / 休息页 / 完成反馈页 ViewModel 的流程单测（真实仓库实现 + 内存数据 + 可推进时钟）。
 *
 * 覆盖测试说明「下一项选取」「休息倒计时」「反馈语料」在页面层的落地：
 * 下一项按清单顺序选取与「现在开始」跳转、清单全完成进入完成反馈、休息起点与总时长、表扬语取自语料池。
 *
 * 测试框架注记：休息页存在 `while(true) + delay` 倒计时循环，经 [timerTest] 在用例结束前取消其作用域，
 * 避免与 runTest 的虚拟时间收尾互相追逐（详见 [TimerExecutionViewModelTest] 类注释）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerSecondaryViewModelTest {

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

    // ---- 下一项提示页 ----

    @Test
    fun `下一项按清单顺序选取未完成项并给出进度文案`() = timerTest {
        val env = TimerTestEnv()
        env.seedHomework(status = HomeworkStatus.COMPLETED, content = "已完成项")
        val pending = env.seedHomework(content = "下一项作业", estimatedMinutes = 20)
        val viewModel = track(nextItemViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(TimerTestEnv.STUDENT_ID, state.studentId)
        assertEquals(pending.id, state.nextItem?.id)
        assertEquals(2, state.totalCount)
        assertEquals(1, state.completedCount)
        assertEquals(1, state.remainingCount)
        assertFalse(state.allCompleted)
        assertEquals("现在开始", state.nextItemActionText)
        assertEquals("已完成 1 / 2 项", state.progressText)
    }

    @Test
    fun `进行中的项展示为继续做这一项`() = timerTest {
        val env = TimerTestEnv()
        val inProgress = env.seedHomework(status = HomeworkStatus.IN_PROGRESS)
        val viewModel = track(nextItemViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        assertEquals(inProgress.id, viewModel.uiState.value.nextItem?.id)
        assertEquals("继续做这一项", viewModel.uiState.value.nextItemActionText)
    }

    @Test
    fun `清单全部完成时判定全部完成且无下一项`() = timerTest {
        val env = TimerTestEnv()
        env.seedHomework(status = HomeworkStatus.COMPLETED)
        val viewModel = track(nextItemViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        val state = viewModel.uiState.value
        assertNull(state.nextItem)
        assertTrue(state.allCompleted)
        assertEquals(0, state.remainingCount)
    }

    @Test
    fun `现在开始会先落库推进为进行中再外抛开始事件`() = timerTest {
        val env = TimerTestEnv()
        val pending = env.seedHomework()
        val viewModel = track(nextItemViewModel(env))
        val events = mutableListOf<TimerNextItemEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }
        viewModel.start(ROUTE_STUDENT_NONE)

        viewModel.onStartNextClick()

        assertEquals(HomeworkStatus.IN_PROGRESS, env.homeworkRepository.getHomework(pending.id)?.status)
        assertEquals(1, env.timerSessionDao.all().size)
        assertEquals(
            listOf<TimerNextItemEvent>(
                TimerNextItemEvent.StartHomework(studentId = TimerTestEnv.STUDENT_ID, homeworkId = pending.id),
            ),
            events,
        )
    }

    @Test
    fun `无下一项时现在开始不做任何动作`() = timerTest {
        val env = TimerTestEnv()
        env.seedHomework(status = HomeworkStatus.COMPLETED)
        val viewModel = track(nextItemViewModel(env))
        val events = mutableListOf<TimerNextItemEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }
        viewModel.start(ROUTE_STUDENT_NONE)

        viewModel.onStartNextClick()

        assertTrue(events.isEmpty())
        assertTrue(env.timerSessionDao.all().isEmpty())
    }

    @Test
    fun `学生会话忽略路由学生 id`() = timerTest {
        val env = TimerTestEnv()
        env.seedHomework()
        val viewModel = track(nextItemViewModel(env))

        viewModel.start(routeStudentId = 99L)

        assertEquals(TimerTestEnv.STUDENT_ID, viewModel.uiState.value.studentId)
    }

    @Test
    fun `家长会话未指定学生时提示未选定学生`() = timerTest {
        val env = TimerTestEnv()
        env.seedHomework()
        env.useParentSession()
        val viewModel = track(nextItemViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        assertTrue(viewModel.uiState.value.missingStudent)
        assertFalse(viewModel.uiState.value.allCompleted)
    }

    @Test
    fun `未登录时下一项页提示会话失效`() = timerTest {
        val env = TimerTestEnv()
        env.logout()
        val viewModel = track(nextItemViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        assertTrue(viewModel.uiState.value.missingSession)
        assertFalse(viewModel.uiState.value.allCompleted)
    }

    // ---- 休息页 ----

    @Test
    fun `进入休息页按当前时刻起算十分钟倒计时`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(content = "刚完成的作业")
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(TimerTestEnv.STUDENT_ID, state.studentId)
        assertEquals("刚完成的作业", state.finishedHomeworkContent)
        assertEquals(TimerTestEnv.FIXED_MILLIS, state.restStartedAtMillis)
        assertEquals(600_000L, state.remainingMillis)
        assertEquals(600L, state.remainingSeconds)
        assertEquals(10, state.totalMinutes)
        assertFalse(state.restFinished)
    }

    @Test
    fun `重复进入休息页不重置倒计时起点`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val viewModel = track(restViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        env.advance(60_000L)

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        assertEquals(TimerTestEnv.FIXED_MILLIS, viewModel.uiState.value.restStartedAtMillis)
    }

    @Test
    fun `休息页缺失会话与未选定学生的提示分支`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.logout()
        val viewModel = track(restViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        assertTrue(viewModel.uiState.value.missingSession)
        assertFalse(viewModel.uiState.value.missingStudent)

        env.useParentSession()
        val parentView = track(restViewModel(env))
        parentView.start(ROUTE_STUDENT_NONE, homework.id)

        assertTrue(parentView.uiState.value.missingStudent)
        assertTrue(parentView.uiState.value.missingStudentHint)
    }

    // ---- 完成反馈页 ----

    @Test
    fun `完成反馈页取语料池表扬语并统计完成情况`() = timerTest {
        val env = TimerTestEnv()
        env.seedHomework(status = HomeworkStatus.COMPLETED)
        env.seedHomework(status = HomeworkStatus.COMPLETED)
        env.seedHomework(status = HomeworkStatus.PENDING)
        val viewModel = track(completionViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertTrue("表扬语应取自语料池", state.praiseText in TimerFeedback.PRAISE_TEXTS)
        assertEquals(3, state.totalCount)
        assertEquals(2, state.completedCount)
        assertEquals(1, state.remainingCount)
        assertEquals("今天完成了 2 / 3 项", state.progressText)
        assertFalse(state.allCompleted)
        assertNotNull(state.remainingText)
    }

    @Test
    fun `全部完成时完成反馈页判定全部完成且无剩余提示`() = timerTest {
        val env = TimerTestEnv()
        env.seedHomework(status = HomeworkStatus.COMPLETED)
        val viewModel = track(completionViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        assertTrue(viewModel.uiState.value.allCompleted)
        assertNull(viewModel.uiState.value.remainingText)
    }

    @Test
    fun `完成反馈页表扬语在重复进入时保持不变`() = timerTest {
        val env = TimerTestEnv()
        env.seedHomework(status = HomeworkStatus.COMPLETED)
        val viewModel = track(completionViewModel(env))
        viewModel.start(ROUTE_STUDENT_NONE)
        val first = viewModel.uiState.value.praiseText

        repeat(5) { viewModel.start(ROUTE_STUDENT_NONE) }

        assertEquals(first, viewModel.uiState.value.praiseText)
    }

    @Test
    fun `完成反馈页未登录时提示会话失效`() = timerTest {
        val env = TimerTestEnv()
        env.logout()
        val viewModel = track(completionViewModel(env))

        viewModel.start(ROUTE_STUDENT_NONE)

        assertTrue(viewModel.uiState.value.missingSession)
        assertFalse(viewModel.uiState.value.allCompleted)
    }

    // ---- 测试工具 ----

    /** 用例包裹：结束前取消所有被测 ViewModel 的作用域，避免长驻倒计时循环阻塞 runTest 收尾 */
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

    private fun nextItemViewModel(env: TimerTestEnv): TimerNextItemViewModel = TimerNextItemViewModel(
        homeworkRepository = env.homeworkRepository,
        timerRepository = env.repository,
        authRepository = env.authRepository,
        reminderCoordinator = env.reminderCoordinator,
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

    private companion object {

        /** 路由参数缺省值：学生端不指定学生（按会话解析本人） */
        const val ROUTE_STUDENT_NONE = 0L
    }
}
