package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.stats.data.StatsFailure
import com.assignmate.app.stats.data.StatsRepository
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.DaySummary
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.ItemDetail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * 复审批次补充边界用例（离朱）：日期口径的四文案一致性不变量、空态两条文案的互斥性、
 * 清单卡标题的零值边界、以及「加载态是否真的在取数期间就按目标日期收敛」。
 *
 * 与既有用例的分工：既有用例逐条锁定文案字面值，本文件锁定**跨文案的结构性不变量**
 * （同一 isToday 分支、今天/历史日措辞互不串台）与**取数中途的真实状态**，
 * 防止「投影改对了但加载路径仍写死今天」这类只在真实时序下暴露的回归。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsDateScopeUiEdgeTest {

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

    // ---- 空态两条文案：互斥且各自带正确口径关键词 ----

    @Test
    fun `空态两条文案互不串台`() {
        // 历史日文案不得出现「今天」，今天文案不得出现「这一天」：
        // 这是「今天视图不出现『这一天』、历史日视图不出现『今天』」的直接可判定形式
        assertFalse(StatsErrorMessages.NO_DATA.contains("今天"))
        assertFalse(StatsErrorMessages.NO_DATA_TODAY.contains("这一天"))
        assertTrue(StatsErrorMessages.NO_DATA_TODAY != StatsErrorMessages.NO_DATA)
        assertEquals(StatsErrorMessages.NO_DATA_TODAY, StatsErrorMessages.noDataText(isToday = true))
        assertEquals(StatsErrorMessages.NO_DATA, StatsErrorMessages.noDataText(isToday = false))
    }

    // ---- 四处文案共用同一 isToday 分支（结构性不变量） ----

    @Test
    fun `历史日四处文案均不出现今天措辞`() {
        val history = DaySummaryUiState(
            epochDay = DAY_EPOCH - 1,
            todayEpochDay = DAY_EPOCH,
            summary = daySummary(epochDay = DAY_EPOCH - 1),
        )

        assertFalse(history.isToday)
        listOf(history.titleText, history.loadingText, history.itemListTitle, history.emptyText)
            .forEach { text -> assertFalse("历史日文案不应出现「今天」措辞：$text", text.contains("今天")) }
        // 标题与加载态必须携带具体日期，否则历史日与今天无法区分
        assertTrue(history.titleText.startsWith(history.dateText))
        assertTrue(history.loadingText.contains(history.dateText))
    }

    @Test
    fun `今天四处文案均采用今天口径`() {
        val today = DaySummaryUiState(
            epochDay = DAY_EPOCH,
            todayEpochDay = DAY_EPOCH,
            summary = daySummary(epochDay = DAY_EPOCH),
        )

        assertTrue(today.isToday)
        listOf(today.titleText, today.loadingText, today.itemListTitle, today.emptyText)
            .forEach { text -> assertTrue("今天文案应采用今天口径：$text", text.contains("今天")) }
        assertEquals(StatsErrorMessages.NO_DATA_TODAY, today.emptyText)
    }

    @Test
    fun `明天等未来日不按今天口径渲染`() {
        // 边界：epochDay 与今日不同即为历史日口径，未来日同样展示具体日期，避免与「今天」混淆
        val tomorrow = DaySummaryUiState(
            epochDay = DAY_EPOCH + 1,
            todayEpochDay = DAY_EPOCH,
            summary = daySummary(epochDay = DAY_EPOCH + 1),
        )

        assertFalse(tomorrow.isToday)
        assertEquals("2023-11-15", tomorrow.dateText)
        assertEquals("2023-11-15 的作业盘点", tomorrow.titleText)
        assertEquals("正在盘点 2023-11-15 的作业…", tomorrow.loadingText)
        assertEquals("这一天动过的作业（2 项）", tomorrow.itemListTitle)
        assertEquals(StatsErrorMessages.NO_DATA, tomorrow.emptyText)
    }

    // ---- 清单卡标题零值边界 ----

    @Test
    fun `清单卡标题在无盘点结果时按零项与日期口径渲染`() {
        val todayNoData = DaySummaryUiState(epochDay = DAY_EPOCH, todayEpochDay = DAY_EPOCH)
        assertEquals("今天动过的作业（0 项）", todayNoData.itemListTitle)

        val historyNoData = DaySummaryUiState(epochDay = DAY_EPOCH - 1, todayEpochDay = DAY_EPOCH)
        assertEquals("这一天动过的作业（0 项）", historyNoData.itemListTitle)

        // 首帧默认态（日期尚未解析，哨兵 -1）：与既有行为一致按今天口径渲染，不抛异常
        val initial = DaySummaryUiState()
        assertTrue(initial.isToday)
        assertEquals("正在盘点今天的作业…", initial.loadingText)
        assertEquals("今天动过的作业（0 项）", initial.itemListTitle)
        assertEquals("", initial.dateText)
    }

    // ---- 加载态在取数期间即按目标日期收敛（真实时序，非仅投影） ----

    @Test
    fun `取数未返回时加载态已按历史日期渲染`() = runTest {
        val stats = GatedStatsRepository()
        val viewModel = track(dayViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH - 1)

        // 仓库尚未返回：阶段为取数中，且文案已是所选历史日的口径（不再硬编码「今天」）
        val loading = viewModel.uiState.value
        assertEquals(DaySummaryPhase.LOADING, loading.phase)
        assertEquals(DAY_EPOCH - 1, loading.epochDay)
        // 「今天」仍是注入时钟 + 业务时区解析出的当日，与被盘点的历史日不同
        assertEquals(DAY_EPOCH, loading.todayEpochDay)
        assertFalse(loading.isToday)
        assertEquals("正在盘点 2023-11-13 的作业…", loading.loadingText)
        assertEquals("2023-11-13 的作业盘点", loading.titleText)

        stats.release()
        advanceUntilIdle()

        assertEquals(DaySummaryPhase.READY, viewModel.uiState.value.phase)
    }

    @Test
    fun `取数未返回时加载态仍按今天口径渲染`() = runTest {
        val stats = GatedStatsRepository()
        val viewModel = track(dayViewModel(stats))

        viewModel.start(
            StatsDestination.ARG_STUDENT_ID_NONE,
            StatsDestination.ARG_EPOCH_DAY_TODAY,
        )

        val loading = viewModel.uiState.value
        assertEquals(DaySummaryPhase.LOADING, loading.phase)
        assertEquals(DAY_EPOCH, loading.epochDay)
        assertTrue(loading.isToday)
        assertEquals("正在盘点今天的作业…", loading.loadingText)

        stats.release()
        advanceUntilIdle()

        assertEquals(DaySummaryPhase.READY, viewModel.uiState.value.phase)
    }

    // ---- 死代码清理的结构性校验 ----

    @Test
    fun `盘点页仍保留单项详情入口而历史页不保留`() {
        // framework（AssignMateNavHost）依赖盘点页的 openItemDetail，清理不得波及
        assertTrue(
            "盘点页应保留 openItemDetail 接口（framework 已接线到单项详情页）",
            DaySummaryViewModel::class.java.declaredMethods.any { it.name == "openItemDetail" },
        )
        assertTrue(
            "盘点页应保留 OpenItemDetail 事件",
            DaySummaryEvent::class.java.declaredClasses.any { it.simpleName == "OpenItemDetail" },
        )
        assertFalse(
            "历史页不应保留未接线的 openItemDetail 接口",
            HistoryViewModel::class.java.declaredMethods.any { it.name == "openItemDetail" },
        )
        // 自建换算所用的私有常量应随代码一并删除（反射层面确认，避免只删方法留常量）
        assertFalse(
            "历史页不应残留自建换算常量 MILLIS_PER_SECOND",
            HistoryViewModel::class.java.declaredFields.any { it.name.contains("MILLIS_PER_SECOND") },
        )
    }

    // ---- 测试辅助 ----

    private fun dayViewModel(stats: StatsRepository): DaySummaryViewModel = DaySummaryViewModel(
        statsRepository = stats,
        authRepository = FakeStatsUiAuthRepository(),
        clock = StatsUiClock(DaySummaryViewModelTest.FIXED_MILLIS),
        zoneId = DaySummaryViewModelTest.ZONE,
    )

    private fun <T : ViewModel> track(viewModel: T): T {
        createdViewModels += viewModel
        return viewModel
    }

    companion object {

        /** 2023-11-14 的 UTC 纪元日（与既有 UI 层用例一致） */
        const val DAY_EPOCH = 19_675L
    }
}

/**
 * 可闸门统计仓库：`summarizeDay` 挂起直到 [release]，用于观察**取数中途**的加载态文案。
 */
private class GatedStatsRepository : StatsRepository {

    private val gate = CompletableDeferred<Unit>()

    /** 放行取数 */
    fun release() {
        gate.complete(Unit)
    }

    override suspend fun summarizeDay(studentId: Long, epochDay: Long): StatsResult<DaySummary> {
        gate.await()
        return StatsResult.Success(daySummary(epochDay = epochDay))
    }

    override suspend fun itemDetail(homeworkId: Long): StatsResult<ItemDetail> =
        StatsResult.Failure(StatsFailure.HOMEWORK_NOT_FOUND)

    override suspend fun history(
        studentId: Long,
        query: HistoryQuery,
    ): StatsResult<List<DaySummary>> = StatsResult.Success(emptyList())
}