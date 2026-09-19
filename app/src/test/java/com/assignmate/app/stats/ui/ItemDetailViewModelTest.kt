package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.stats.data.StatsFailure
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.DifficultyLevel
import com.assignmate.app.stats.domain.StageProgress
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
 * [ItemDetailViewModel] 单测：单项详情按**指定某一天**的展示口径（预估/实际/暂停 + 当天状态
 * + 阶段打卡进度 + 困难度文案）、「当天无执行痕迹」正常态、日期口径文案（今天 / 历史日）、
 * 作业不存在/越权/无会话的失败收敛与重试、幂等 start。
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
    fun `有执行记录时展示当天预估实际暂停与状态`() = runTest {
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
        assertEquals("偏慢", state.difficultyLabel)
        assertEquals("比预估慢较多，中途暂停 2 次", state.assessmentHint)
        assertEquals(listOf(1L to DAY_EPOCH), stats.detailCalls)
    }

    @Test
    fun `未设定预估时长时展示未设定占位`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailResult = StatsResult.Success(itemDetailResult(estimatedMinutes = null))
        }
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)

        assertEquals(StatsErrorMessages.ESTIMATED_UNSET, viewModel.uiState.value.estimatedText)
    }

    @Test
    fun `当天无执行痕迹时仍为就绪态但展示尚未开始文案`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailResult = StatsResult.Success(
                itemDetailResult(
                    dayStatus = HomeworkDayStatus.NOT_STARTED,
                    hasExecution = false,
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
        assertEquals("还没开始", state.statusText)
        assertEquals(StatsErrorMessages.NOT_STARTED, state.elapsedText)
        assertEquals(StatsErrorMessages.NOT_STARTED, state.pausedText)
        assertEquals("暂无数据", state.difficultyLabel)
    }

    @Test
    fun `阶段作业展示整段打卡进度`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailResult = StatsResult.Success(
                itemDetailResult(
                    isStage = true,
                    stageProgress = StageProgress(homeworkId = 1L, content = "背单词", doneDays = 3, totalDays = 7),
                ),
            )
        }
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)

        assertEquals("阶段打卡 3/7 天", viewModel.uiState.value.stageProgressText)
    }

    @Test
    fun `历史日缺卡的阶段作业在详情页展示未完成文案与阶段打卡进度`() = runTest {
        // 口径联动：历史日残留的进行中详情在领域层已按缺卡呈现（StatsCalculationsTest 锁定），
        // 本用例把该口径钉到详情页出口——状态文案为「当天没做完」，但仍展示当天耗时与阶段打卡进度
        val stats = FakeStatsRepository().apply {
            detailResult = StatsResult.Success(
                itemDetailResult(
                    epochDay = DAY_EPOCH - 1,
                    isStage = true,
                    dayStatus = HomeworkDayStatus.MISSED,
                    elapsedMillis = 20 * MINUTE,
                    pausedTotalMillis = 3 * MINUTE,
                    hasExecution = true,
                    difficulty = DifficultyLevel.SLOW,
                    stageProgress = StageProgress(homeworkId = 1L, content = "背单词", doneDays = 1, totalDays = 7),
                ),
            )
        }
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, HOMEWORK_ID, DAY_EPOCH - 1)

        val state = viewModel.uiState.value
        assertEquals(ItemDetailPhase.READY, state.phase)
        assertEquals("当天没做完", state.statusText)
        assertFalse(state.isToday)
        assertEquals("2023-11-13", state.dateText)
        assertEquals("2023-11-13的用时情况", state.timeCardTitle)
        assertEquals("20 分钟", state.elapsedText)
        assertEquals("阶段打卡 1/7 天", state.stageProgressText)
        assertEquals("偏慢", state.difficultyLabel)
    }

    @Test
    fun `当天作业没有阶段打卡进度`() = runTest {
        val viewModel = track(itemDetailViewModel(FakeStatsRepository()))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L)

        assertNull(viewModel.uiState.value.stageProgressText)
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

    // ---- 日期口径（今天 / 历史日） ----

    @Test
    fun `缺省日期按今天取数并展示今天文案`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(
            StatsDestination.ARG_STUDENT_ID_NONE,
            1L,
            StatsDestination.ARG_EPOCH_DAY_TODAY,
        )

        val state = viewModel.uiState.value
        assertEquals(listOf(1L to DAY_EPOCH), stats.detailCalls)
        assertEquals(DAY_EPOCH, state.epochDay)
        assertTrue(state.isToday)
        assertEquals("正在读取今天的用时…", state.loadingText)
        assertEquals("今天的用时情况", state.timeCardTitle)
        assertEquals("只统计今天的用时（阶段作业每天分别计算）", state.noteText)
    }

    @Test
    fun `指定历史日期时按该日取数并展示日期文案`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailResult = StatsResult.Success(itemDetailResult(epochDay = DAY_EPOCH - 1))
        }
        val viewModel = track(itemDetailViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, 1L, DAY_EPOCH - 1)

        val state = viewModel.uiState.value
        assertEquals(listOf(1L to (DAY_EPOCH - 1)), stats.detailCalls)
        assertEquals(DAY_EPOCH - 1, state.epochDay)
        assertFalse(state.isToday)
        assertEquals("2023-11-13", state.dateText)
        assertEquals("正在读取2023-11-13的用时…", state.loadingText)
        assertEquals("2023-11-13的用时情况", state.timeCardTitle)
        assertEquals("只统计2023-11-13的用时（阶段作业每天分别计算）", state.noteText)
    }

    @Test
    fun `非法日期参数回落今天`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(itemDetailViewModel(stats))

        // 路由缺失/非法由 epochDayOf 统一回落 [-1] 哨兵，再按「今天」解析
        viewModel.start(
            StatsDestination.ARG_STUDENT_ID_NONE,
            1L,
            StatsDestination.epochDayOf("不是数字"),
        )

        assertEquals(listOf(1L to DAY_EPOCH), stats.detailCalls)
        assertTrue(viewModel.uiState.value.isToday)
    }

    @Test
    fun `同一条作业按不同日期分别取数且不串天`() = runTest {
        val stats = FakeStatsRepository().apply {
            detailQueue += StatsResult.Success(
                itemDetailResult(epochDay = DAY_EPOCH, elapsedMillis = 20 * MINUTE, pausedTotalMillis = 3 * MINUTE),
            )
            detailQueue += StatsResult.Success(
                itemDetailResult(epochDay = DAY_EPOCH - 1, elapsedMillis = 8 * MINUTE, pausedTotalMillis = 4 * MINUTE),
            )
        }
        // 查看日期由页面入参注入（路由不携带日期，缺省哨兵 = 今天）；阶段作业据此按天查看
        val todayViewModel = track(itemDetailViewModel(stats))
        val yesterdayViewModel = track(itemDetailViewModel(stats))

        todayViewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, HOMEWORK_ID, DAY_EPOCH)
        yesterdayViewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, HOMEWORK_ID, DAY_EPOCH - 1)

        assertEquals("20 分钟", todayViewModel.uiState.value.elapsedText)
        assertEquals("3 分钟", todayViewModel.uiState.value.pausedText)
        assertEquals("8 分钟", yesterdayViewModel.uiState.value.elapsedText)
        assertEquals("4 分钟", yesterdayViewModel.uiState.value.pausedText)
        assertEquals(listOf(HOMEWORK_ID to DAY_EPOCH, HOMEWORK_ID to (DAY_EPOCH - 1)), stats.detailCalls)
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
        assertNull(viewModel.uiState.value.stageProgressText)
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
            detailResult = StatsResult.Success(itemDetailResult())
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

        assertEquals(listOf(1L to DAY_EPOCH), stats.detailCalls)
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
    ): ItemDetailViewModel = ItemDetailViewModel(
        statsRepository = stats,
        authRepository = auth,
        clock = StatsUiClock(DaySummaryViewModelTest.FIXED_MILLIS),
        zoneId = DaySummaryViewModelTest.ZONE,
    )

    private fun <T : ViewModel> track(viewModel: T): T {
        createdViewModels += viewModel
        return viewModel
    }

    private companion object {

        /** 单项详情用例的目标作业 id */
        const val HOMEWORK_ID = 1L
    }
}
