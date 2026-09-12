package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.stats.data.StatsFailure
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.DifficultyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * [ItemDetailViewModel] 单测：单项详情的展示口径（预估/实际/暂停/会话数 + 困难度文案）、
 * 「无执行记录」正常态、作业不存在/越权/无会话的失败收敛与重试、幂等 start。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemDetailViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val createdViewModels = mutableListOf<ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    // ---- 正向 ----

    @Test
    fun `有执行记录时展示预估实际暂停与会话数`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)

        val state = viewModel.uiState.value
        assertEquals(ItemDetailPhase.READY, state.phase)
        assertEquals(FakeStatsUiAuthRepository.STUDENT_ID, state.studentId)
        assertTrue(state.hasExecution)
        assertEquals("数学口算", state.contentText)
        assertEquals("已完成", state.statusText)
        // P1 回归看护：预估时长以「分钟」落库，展示走 minutesText(minutes)，
        // 一旦再被乘一次 MILLIS_PER_MINUTE 就会放大 60000 倍（曾出现「30000 小时」）。
        assertEquals("30 分钟", state.estimatedText)
        assertEquals("45 分钟", state.elapsedText)
        assertEquals("10 分钟", state.pausedText)
        assertEquals("1 次", state.sessionCountText)
        assertEquals("偏慢", state.difficultyLabel)
        assertEquals("比预估慢较多，中途暂停 2 次", state.assessmentHint)
        assertEquals(listOf(1L), stats.detailCalls)
    }

    @Test
    fun `未设定预估时长时展示未设定占位`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailResult = StatsResult.Success(itemDetail(estimatedMinutes = null))
        }
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)

        assertEquals(StatsErrorMessages.ESTIMATED_UNSET, viewModel.uiState.value.estimatedText)
    }

    @Test
    fun `无执行记录时仍为就绪态但展示尚未开始文案`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailResult = StatsResult.Success(
                itemDetail(
                    status = HomeworkStatus.PENDING,
                    sessionCount = 0,
                    elapsedMillis = 0L,
                    pausedTotalMillis = 0L,
                    pauseCount = 0,
                    difficulty = DifficultyLevel.UNKNOWN,
                    assessmentHint = "还没有开始计时",
                ),
            )
        }
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)

        val state = viewModel.uiState.value
        assertEquals(ItemDetailPhase.READY, state.phase)
        assertFalse(state.hasExecution)
        assertEquals("待完成", state.statusText)
        assertEquals(StatsErrorMessages.NOT_STARTED, state.elapsedText)
        assertEquals(StatsErrorMessages.NOT_STARTED, state.pausedText)
        assertEquals("0 次", state.sessionCountText)
        assertEquals("暂无数据", state.difficultyLabel)
    }

    @Test
    fun `家长会话按路由学生进入就绪态`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(
            itemDetailViewModel(stats, FakeStatsUiAuthRepository(FakeStatsUiAuthRepository.PARENT_SESSION)),
        )

        viewModel.start(FakeStatsUiAuthRepository.STUDENT_ID, 1L)

        assertEquals(ItemDetailPhase.READY, viewModel.uiState.value.phase)
    }

    // ---- 反向 ----

    @Test
    fun `作业不存在时展示不存在文案`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailResult = statsFailure(StatsFailure.HOMEWORK_NOT_FOUND)
        }
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 999L)

        assertEquals(ItemDetailPhase.ERROR, viewModel.uiState.value.phase)
        assertEquals(StatsErrorMessages.HOMEWORK_NOT_FOUND, viewModel.uiState.value.errorMessage)
        // 无数据时展示口径回退到占位文案，不得渲染空串或崩溃
        assertEquals("作业详情", viewModel.uiState.value.contentText)
        assertEquals(StatsErrorMessages.ESTIMATED_UNSET, viewModel.uiState.value.estimatedText)
        assertEquals(StatsErrorMessages.NOT_STARTED, viewModel.uiState.value.elapsedText)
        assertEquals("", viewModel.uiState.value.difficultyLabel)
    }

    @Test
    fun `越权查看他人作业展示越权文案`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailResult = statsFailure(StatsFailure.ACCESS_DENIED)
        }
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)

        assertEquals(StatsErrorMessages.ACCESS_DENIED, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `读取失败后可重试成功`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailQueue += statsFailure(StatsFailure.READ_FAILED)
            detailResult = StatsResult.Success(itemDetail())
        }
        val viewModel = track(itemDetailViewModel(stats))
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)
        assertEquals(ItemDetailPhase.ERROR, viewModel.uiState.value.phase)

        viewModel.reload()

        assertEquals(ItemDetailPhase.READY, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(2, stats.detailCalls.size)
    }

    @Test
    fun `无会话时不取数并进入未登录阶段`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(itemDetailViewModel(stats, FakeStatsUiAuthRepository(SessionState.NONE)))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)

        assertEquals(ItemDetailPhase.NO_SESSION, viewModel.uiState.value.phase)
        assertTrue(stats.detailCalls.isEmpty())
    }

    @Test
    fun `家长未选定学生时不取数并提示选择学生`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(
            itemDetailViewModel(stats, FakeStatsUiAuthRepository(FakeStatsUiAuthRepository.PARENT_SESSION)),
        )

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)

        assertEquals(ItemDetailPhase.NO_STUDENT, viewModel.uiState.value.phase)
        assertTrue(stats.detailCalls.isEmpty())
    }

    @Test
    fun `重复 start 为幂等不重复取数`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 2L)

        assertEquals(listOf(1L), stats.detailCalls)
    }

    @Test
    fun `未 start 时 reload 不触发取数`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.reload()

        assertTrue(stats.detailCalls.isEmpty())
        assertFalse(viewModel.uiState.value.started)
    }

    // ---- 测试辅助 ----

    private fun itemDetailViewModel(
        stats: FakeStatsRepository,
        auth: FakeStatsUiAuthRepository = FakeStatsUiAuthRepository(),
    ): ItemDetailViewModel = ItemDetailViewModel(statsRepository = stats, authRepository = auth)

    private fun <T : ViewModel> track(viewModel: T): T {
        createdViewModels += viewModel
        return viewModel
    }
}