package com.assignmate.app.timer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.FakeHomeworkRepository
import com.assignmate.app.timer.data.TimerRepositoryImpl
import com.assignmate.app.timer.data.TimerStartResult
import com.assignmate.app.timer.data.TimerTickerController
import com.assignmate.app.timer.data.TimerTickerInfo
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import com.assignmate.app.timer.domain.TimerPhase
import com.assignmate.app.timer.domain.TimerSession
import java.time.Instant
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
 * 作业执行页 ViewModel 流程单测（真实 timer 仓库实现 + 内存 DAO + 可推进时钟 + 走秒服务替身）。
 *
 * 覆盖测试说明第 9 项的可执行部分与核心用户旅程：
 * - 进入页面恢复现场（进行中继续走秒 / 暂停中冻结显示并通知服务冻结）；
 * - 开始 → RUNNING（作业置进行中 + 拉起走秒服务）、暂停 → PAUSED（通知冻结）、恢复 → RUNNING（累计暂停）、
 *   完成 → FINISHED（停止服务 + 外抛完成事件 + 作业置已完成）；
 * - 失败分支：完成时作业状态同步失败不得收尾、不得停服务，只给可读提示；
 * - 超时提醒：超过预估时长后给出含完成数量的鼓励语；
 * - 会话/作业缺失分支不触达仓库。
 *
 * 说明：页面为原生 Compose（无设备时 E2E 不可执行），故以 ViewModel 状态与走秒服务调用序列验证行为契约。
 *
 * 测试框架注记：ViewModel 内存在走秒 `while(true) + delay` 长驻循环，
 * 若不在用例结束前取消 viewModelScope，runTest 收尾推进虚拟时间时会与长驻循环互相追逐而死循环，
 * 因此统一经 [timerTest] 包裹并在 finally 中取消所有被测 ViewModel 的作用域。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerExecutionViewModelTest {

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

    // ---- 进入页面 / 恢复现场 ----

    @Test
    fun `进入页面时未开始计时停留在未开始且仅可开始`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val ticker = FakeTickerController()
        val viewModel = track(viewModel(env, ticker))

        viewModel.start(routeStudentId = ROUTE_STUDENT_NONE, homeworkId = homework.id)

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(TimerPhase.IDLE, state.phase)
        assertEquals(homework.id, state.homework?.id)
        assertEquals(TimerTestEnv.STUDENT_ID, state.studentId)
        assertEquals(0L, state.elapsedMillis)
        assertTrue(state.actions.canStart)
        assertFalse(state.homeworkEditingLocked)
        assertEquals("未开始时不动前台服务", emptyList<String>(), ticker.events)
    }

    @Test
    fun `进入页面恢复进行中的会话并拉起走秒服务`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.startSession(homework.id)
        env.advance(25_000L)
        val ticker = FakeTickerController()
        val viewModel = track(viewModel(env, ticker))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertEquals(TimerPhase.RUNNING, state.phase)
        assertEquals(session.id, state.session?.id)
        assertEquals(25_000L, state.elapsedMillis)
        assertEquals(25L, state.elapsedSeconds)
        assertTrue(state.actions.canPause)
        assertTrue(state.actions.canComplete)
        assertTrue(state.homeworkEditingLocked)
        assertEquals(listOf("start"), ticker.events)
        assertFalse(ticker.lastInfo!!.isPaused)
    }

    @Test
    fun `进入页面恢复暂停中的会话并冻结已用时长同时通知服务冻结`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.startSession(homework.id)
        env.advance(30_000L)
        env.repository.pauseSession(session.id)
        env.advance(120_000L)
        val ticker = FakeTickerController()
        val viewModel = track(viewModel(env, ticker))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertEquals(TimerPhase.PAUSED, state.phase)
        assertEquals(30_000L, state.elapsedMillis)
        assertEquals(1, state.pauseCount)
        assertTrue(state.actions.canResume)
        assertTrue(state.homeworkEditingLocked)
        assertEquals(listOf("paused"), ticker.events)
        assertEquals(TimerTestEnv.FIXED_MILLIS + 30_000L, ticker.lastInfo!!.frozenAtMillis)
        assertTrue(ticker.lastInfo!!.isPaused)
    }

    @Test
    fun `重复进入页面不会重置现场或重复拉起服务`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val ticker = FakeTickerController()
        val viewModel = track(viewModel(env, ticker))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.advance(10_000L)
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        assertEquals(TimerPhase.RUNNING, viewModel.uiState.value.phase)
        assertEquals(listOf("start"), ticker.events)
        assertEquals(1, env.timerSessionDao.all().size)
    }

    // ---- 开始 / 暂停 / 恢复 / 完成 ----

    @Test
    fun `开始作业落库置进行中并拉起走秒服务`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val ticker = FakeTickerController()
        val viewModel = track(viewModel(env, ticker))
        val events = mutableListOf<TimerExecutionEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        viewModel.onStartClick()

        val state = viewModel.uiState.value
        assertEquals(TimerPhase.RUNNING, state.phase)
        assertNotNull(state.session)
        assertEquals(0, state.pauseCount)
        assertEquals(HomeworkStatus.IN_PROGRESS, env.homeworkRepository.getHomework(homework.id)?.status)
        assertEquals(listOf("start"), ticker.events)
        assertTrue("开始成功不应产生提示", events.isEmpty())
    }

    @Test
    fun `暂停与恢复累计暂停时长暂停次数并切换服务冻结状态`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val ticker = FakeTickerController()
        val viewModel = track(viewModel(env, ticker))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.advance(60_000L)

        viewModel.onPauseClick()

        var state = viewModel.uiState.value
        assertEquals(TimerPhase.PAUSED, state.phase)
        assertEquals(60_000L, state.elapsedMillis)
        assertEquals(1, state.pauseCount)
        assertEquals(listOf("start", "paused"), ticker.events)
        assertEquals(TimerTestEnv.FIXED_MILLIS + 60_000L, ticker.lastInfo!!.frozenAtMillis)

        // 暂停期间已用时长冻结（不随时间增长），且重复暂停不会新增冻结上报
        env.advance(20_000L)
        viewModel.onPauseClick()
        state = viewModel.uiState.value
        assertEquals(60_000L, state.elapsedMillis)
        assertEquals("重复暂停应仍保持服务冻结状态", listOf("start", "paused"), ticker.events)

        viewModel.onResumeClick()

        state = viewModel.uiState.value
        assertEquals(TimerPhase.RUNNING, state.phase)
        assertEquals(20_000L, state.pausedTotalMillis)
        assertEquals(1, state.pauseCount)
        assertEquals(60_000L, state.elapsedMillis)
        assertEquals(listOf("start", "paused", "running"), ticker.events)
        assertFalse(ticker.lastInfo!!.isPaused)
        assertEquals(20_000L, ticker.lastInfo!!.pausedTotalMillis)
    }

    @Test
    fun `完成作业收尾会话置作业已完成停止服务并外抛完成事件`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val ticker = FakeTickerController()
        val viewModel = track(viewModel(env, ticker))
        val events = mutableListOf<TimerExecutionEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.advance(90_000L)

        viewModel.onCompleteClick()

        val state = viewModel.uiState.value
        assertEquals(TimerPhase.FINISHED, state.phase)
        assertEquals(90_000L, state.elapsedMillis)
        assertFalse(state.overdue)
        assertFalse(state.homeworkEditingLocked)
        assertFalse(state.actions.canComplete)
        assertEquals(listOf("start", "stop"), ticker.events)
        assertEquals(HomeworkStatus.COMPLETED, env.homeworkRepository.getHomework(homework.id)?.status)
        assertEquals(
            listOf<TimerExecutionEvent>(
                TimerExecutionEvent.HomeworkCompleted(studentId = TimerTestEnv.STUDENT_ID, homeworkId = homework.id),
            ),
            events,
        )
    }

    @Test
    fun `完成时作业状态同步失败不收尾也不停止走秒服务只给可读提示`() = timerTest {
        val env = TimerTestEnv()
        val homework = timerTestHomework(id = 5L, status = HomeworkStatus.PENDING)
        val fakeHomework = FakeHomeworkRepository().apply { put(homework) }
        val repository = TimerRepositoryImpl(
            timerSessionDao = env.timerSessionDao,
            pauseRecordDao = env.pauseRecordDao,
            homeworkRepository = fakeHomework,
            authRepository = env.authRepository,
            clock = env.clock,
            transactionRunner = env.transactionRunner,
            dailyRecordRepository = env.dailyRecordRepository,
        )
        val ticker = FakeTickerController()
        val viewModel = track(
            TimerExecutionViewModel(
                timerRepository = repository,
                homeworkRepository = fakeHomework,
                authRepository = env.authRepository,
                tickerController = ticker,
                reminderCoordinator = env.reminderCoordinator,
                voiceGuide = env.voiceGuide,
                voiceSettings = env.voiceSettings,
                overduePromptStore = env.overduePromptStore,
                permissionChecker = env.permissionChecker,
                clock = env.clock,
                zoneId = TimerTestEnv.ZONE,
            ),
        )
        val events = mutableListOf<TimerExecutionEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.advance(30_000L)
        fakeHomework.completeOverride = HomeworkStatusResult.IllegalTransition(
            HomeworkStatus.IN_PROGRESS,
            HomeworkStatus.COMPLETED,
        )

        viewModel.onCompleteClick()

        val state = viewModel.uiState.value
        assertEquals("同步失败时会话不得收尾", TimerPhase.RUNNING, state.phase)
        assertNull(state.session?.finishedAt)
        val stored = env.timerSessionDao.all().single()
        assertEquals(TimerPhase.RUNNING.name, stored.status)
        assertNull(stored.finishedAt)
        assertEquals(listOf("start"), ticker.events)
        assertEquals(
            listOf("作业状态更新失败，计时未结束，请稍后再试"),
            events.filterIsInstance<TimerExecutionEvent.ShowMessage>().map { it.message },
        )
    }

    @Test
    fun `暂停中直接完成会先收尾暂停明细`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val ticker = FakeTickerController()
        val viewModel = track(viewModel(env, ticker))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.advance(20_000L)
        viewModel.onPauseClick()
        env.advance(15_000L)

        viewModel.onCompleteClick()

        val state = viewModel.uiState.value
        assertEquals(TimerPhase.FINISHED, state.phase)
        assertEquals(1, state.pauseCount)
        assertEquals(15_000L, state.pausedTotalMillis)
        assertEquals("暂停段应扣除：跨度 35s - 暂停 15s", 20_000L, state.elapsedMillis)
        assertTrue(env.pauseRecordDao.all().single().pauseEndAt != null)
    }

    // ---- 超时提醒 ----

    @Test
    fun `超过预估时长后给出含完成数量的鼓励语且模板无残留`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(
            estimatedMinutes = 10,
            startTime = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS),
        )
        env.seedHomework(status = HomeworkStatus.COMPLETED, content = "已完成的一项")
        val viewModel = track(viewModel(env, FakeTickerController()))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.advance(11 * 60_000L)

        viewModel.onPauseClick()

        val state = viewModel.uiState.value
        assertTrue("超过预估完成时刻应判定超时", state.overdue)
        val encouragement = state.encouragementText
        assertNotNull(encouragement)
        assertEquals(1, state.completedCount)
        assertEquals(2, state.totalCount)
        assertTrue("鼓励语应含已完成数量", encouragement!!.contains(state.completedCount.toString()))
        assertTrue("鼓励语应含总数", encouragement.contains(state.totalCount.toString()))
        assertFalse("鼓励语不应残留占位符", encouragement.contains("{"))
    }

    @Test
    fun `未超时不给鼓励语`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(
            estimatedMinutes = 30,
            startTime = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS),
        )
        val viewModel = track(viewModel(env, FakeTickerController()))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)
        viewModel.onStartClick()
        env.advance(60_000L)

        viewModel.onPauseClick()

        assertFalse(viewModel.uiState.value.overdue)
        assertNull(viewModel.uiState.value.encouragementText)
    }

    // ---- 缺失分支 ----

    @Test
    fun `未登录进入执行页提示会话失效且不触达仓库`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.logout()
        val viewModel = track(viewModel(env, FakeTickerController()))

        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        val state = viewModel.uiState.value
        assertTrue(state.missingSession)
        assertFalse(state.loading)
        assertNull(state.homework)
        assertEquals(TimerPhase.IDLE, state.phase)
    }

    @Test
    fun `家长会话未指定学生时提示未选定学生`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.useParentSession()
        val viewModel = track(viewModel(env, FakeTickerController()))

        viewModel.start(routeStudentId = ROUTE_STUDENT_NONE, homeworkId = homework.id)

        assertTrue(viewModel.uiState.value.missingStudent)
        assertFalse(viewModel.uiState.value.missingSession)
    }

    @Test
    fun `学生访问他人名下作业时提示作业不存在`() = timerTest {
        val env = TimerTestEnv()
        val others = env.seedHomework(studentId = 99L)
        val viewModel = track(viewModel(env, FakeTickerController()))

        viewModel.start(ROUTE_STUDENT_NONE, others.id)

        assertTrue(viewModel.uiState.value.missingHomework)
        assertNull(viewModel.uiState.value.session)
    }

    @Test
    fun `暂停或完成在无会话时不做任何动作`() = timerTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val ticker = FakeTickerController()
        val viewModel = track(viewModel(env, ticker))
        viewModel.start(ROUTE_STUDENT_NONE, homework.id)

        viewModel.onPauseClick()
        viewModel.onResumeClick()
        viewModel.onCompleteClick()

        assertEquals(TimerPhase.IDLE, viewModel.uiState.value.phase)
        assertEquals("无会话时不应触达走秒服务", emptyList<String>(), ticker.events)
        assertTrue(env.timerSessionDao.all().isEmpty())
    }

    // ---- 测试工具 ----

    /**
     * 用例包裹：`finally` 中取消所有被测 ViewModel 的 viewModelScope，
     * 避免长驻走秒循环与 runTest 的虚拟时间收尾互相追逐（详见类注释）。
     */
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

    private fun viewModel(
        env: TimerTestEnv,
        ticker: FakeTickerController,
        homeworkRepository: HomeworkRepository = env.homeworkRepository,
    ): TimerExecutionViewModel = TimerExecutionViewModel(
        timerRepository = env.repository,
        homeworkRepository = homeworkRepository,
        authRepository = env.authRepository,
        tickerController = ticker,
        reminderCoordinator = env.reminderCoordinator,
        voiceGuide = env.voiceGuide,
        voiceSettings = env.voiceSettings,
        overduePromptStore = env.overduePromptStore,
        permissionChecker = env.permissionChecker,
        clock = env.clock,
        zoneId = TimerTestEnv.ZONE,
    )

    /** 经仓库落库开始计时（学生会话本人名下作业） */
    private suspend fun TimerTestEnv.startSession(homeworkId: Long): TimerSession =
        (repository.startSession(homeworkId, Role.STUDENT) as TimerStartResult.Success).session

    /** 走秒服务替身：记录调用序列与最近一次上报基准，断言「何时冻结/解冻/停止」 */
    private class FakeTickerController : TimerTickerController {

        val events = mutableListOf<String>()
        var lastInfo: TimerTickerInfo? = null

        override fun startTicker(info: TimerTickerInfo) {
            events += "start"
            lastInfo = info
        }

        override fun markPaused(info: TimerTickerInfo) {
            events += "paused"
            lastInfo = info
        }

        override fun markRunning(info: TimerTickerInfo) {
            events += "running"
            lastInfo = info
        }

        override fun stopTicker() {
            events += "stop"
        }
    }

    private companion object {

        /** 路由参数缺省值：学生端不指定学生（按会话解析本人） */
        const val ROUTE_STUDENT_NONE = 0L
    }
}
