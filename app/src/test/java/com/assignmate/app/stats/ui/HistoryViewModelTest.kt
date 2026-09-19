package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.stats.data.StatsFailure
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.StatsCalculations
import com.assignmate.app.stats.domain.StatsConstants
import com.assignmate.app.stats.domain.StageProgress
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
 * [HistoryViewModel] 单测：单日/范围语义（缺省「今天」）、非法范围就地拦截（不打扰仓库）、
 * 范围收敛展示、逐日条目与合计文案、失败重试与导航事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

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
    fun `缺省入参按今天查单日`() = runTest {
        val stats = FakeStatsRepository().apply {
            historyResult = StatsResult.Success(listOf(daySummary()))
        }
        val viewModel = track(historyViewModel(stats))

        viewModel.start(
            routeStudentId = StatsDestination.ARG_STUDENT_ID_NONE,
            fromEpochDay = StatsDestination.ARG_EPOCH_DAY_TODAY,
            toEpochDay = StatsDestination.ARG_EPOCH_DAY_TODAY,
        )

        val state = viewModel.uiState.value
        assertEquals(HistoryPhase.READY, state.phase)
        assertEquals(DAY_EPOCH, state.query.startEpochDay)
        assertEquals(DAY_EPOCH, state.query.endEpochDay)
        assertEquals("2023-11-14", state.rangeText)
        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to HistoryQuery(DAY_EPOCH)), stats.historyCalls)
    }

    @Test
    fun `指定范围逐日返回并按日期倒序`() = runTest {
        val stats = FakeStatsRepository().apply {
            historyResult = StatsResult.Success(
                listOf(daySummary(epochDay = DAY_EPOCH), daySummary(epochDay = DAY_EPOCH - 1)),
            )
        }
        val viewModel = track(historyViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH - 1, DAY_EPOCH)

        val state = viewModel.uiState.value
        assertEquals(listOf(DAY_EPOCH, DAY_EPOCH - 1), state.rows.map { it.epochDay })
        assertEquals("2023-11-13 ~ 2023-11-14", state.rangeText)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `逐日条目文案包含完成进度与暂停情况`() = runTest {
        val stats = FakeStatsRepository().apply {
            historyResult = StatsResult.Success(listOf(daySummary()))
        }
        val viewModel = track(historyViewModel(stats))
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)

        val row = viewModel.uiState.value.rows.single()

        assertEquals("2023-11-14", row.dateText)
        assertEquals("已完成 1 / 2 项（50.0%）", row.progressText)
        assertEquals("暂停 1 次 · 2 分钟", row.pauseText)
        // 当天没有阶段作业：不渲染打卡进度行
        assertNull(row.stageText)
    }

    @Test
    fun `逐日条目展示当天阶段作业的打卡进度`() = runTest {
        val stats = FakeStatsRepository().apply {
            historyResult = StatsResult.Success(
                listOf(
                    daySummary(
                        epochDay = DAY_EPOCH,
                        stages = listOf(
                            StageProgress(homeworkId = 1L, content = "背单词", doneDays = 3, totalDays = 7),
                            StageProgress(homeworkId = 2L, content = "练字", doneDays = 7, totalDays = 7),
                        ),
                    ),
                ),
            )
        }
        val viewModel = track(historyViewModel(stats))
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)

        // 阶段作业只有一条作业项，作业项状态无法表达「某一天做没做」：
        // 历史行同时给出「当日完成情况」与该行涉及的阶段打卡进度
        assertEquals(
            "「背单词」阶段打卡 3/7 天，「练字」阶段打卡 7/7 天",
            viewModel.uiState.value.rows.single().stageText,
        )
        assertEquals("已完成 1 / 2 项（50.0%）", viewModel.uiState.value.rows.single().progressText)
    }

    @Test
    fun `合计文案累加各日指标`() = runTest {
        val stats = FakeStatsRepository().apply {
            historyResult = StatsResult.Success(
                listOf(
                    daySummary(epochDay = DAY_EPOCH, pauseCount = 2, pausedTotalMillis = 5 * MINUTE),
                    daySummary(epochDay = DAY_EPOCH - 1, pauseCount = 1, pausedTotalMillis = 2 * MINUTE),
                ),
            )
        }
        val viewModel = track(historyViewModel(stats))
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH - 1, DAY_EPOCH)

        val state = viewModel.uiState.value

        assertEquals(4, state.total?.totalCount)
        assertEquals(2, state.total?.completedCount)
        assertEquals(3, state.total?.pauseCount)
        assertEquals(7 * MINUTE, state.total?.pausedTotalMillis)
        assertEquals("合计完成 2 / 4 项 · 暂停 3 次 · 7 分钟", state.totalText)
    }

    @Test
    fun `无记录时进入空态且合计为空`() = runTest {
        val stats = FakeStatsRepository().apply {
            historyResult = StatsResult.Success(emptyList())
        }
        val viewModel = track(historyViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)

        val state = viewModel.uiState.value
        assertTrue(state.isEmpty)
        assertTrue(state.rows.isEmpty())
        assertNull(state.total)
        assertNull(state.totalText)
    }

    // ---- 反向：非法范围与失败收敛 ----

    @Test
    fun `结束日早于开始日就地提示且不改动查询与数据`() = runTest {
        val stats = FakeStatsRepository().apply {
            historyResult = StatsResult.Success(listOf(daySummary()))
        }
        val viewModel = track(historyViewModel(stats))
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)

        viewModel.setRange(DAY_EPOCH, DAY_EPOCH - 1)

        val state = viewModel.uiState.value
        assertEquals(StatsErrorMessages.INVALID_RANGE, state.rangeError)
        assertEquals(DAY_EPOCH, state.query.startEpochDay)
        assertEquals(1, stats.historyCalls.size)
        assertEquals(1, state.rows.size)
    }

    @Test
    fun `合法范围清除先前的范围错误并重新取数`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(historyViewModel(stats))
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)
        viewModel.setRange(DAY_EPOCH, DAY_EPOCH - 1)

        viewModel.setRange(DAY_EPOCH - 2, DAY_EPOCH)

        val state = viewModel.uiState.value
        assertNull(state.rangeError)
        assertEquals(DAY_EPOCH - 2, state.query.startEpochDay)
        assertEquals(2, stats.historyCalls.size)
    }

    @Test
    fun `超长范围在 ViewModel 侧即收敛使展示范围等于实际查询范围`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(historyViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH - 10, DAY_EPOCH + 400)

        // 收敛在 ViewModel 存储查询前完成：页面 rangeText 与下传给仓库的范围一致
        // （仓库层仍保留一次 normalized() 兜底，防绕过 UI 直接调用）
        val query = viewModel.uiState.value.query
        assertEquals(DAY_EPOCH - 10, query.startEpochDay)
        assertEquals(DAY_EPOCH - 10 + StatsConstants.MAX_HISTORY_DAYS - 1, query.endEpochDay)
        assertFalse(query.isTooLong)
        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to query), stats.historyCalls)
    }

    @Test
    fun `修改范围时同样收敛并展示收敛后的范围`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(historyViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)
        viewModel.setRange(DAY_EPOCH, DAY_EPOCH + 200)

        val state = viewModel.uiState.value
        assertEquals(DAY_EPOCH + StatsConstants.MAX_HISTORY_DAYS - 1, state.query.endEpochDay)
        assertEquals(
            StatsCalculations.dateText(DAY_EPOCH) + " ~ " +
                StatsCalculations.dateText(DAY_EPOCH + StatsConstants.MAX_HISTORY_DAYS - 1),
            state.rangeText,
        )
    }

    @Test
    fun `非法入参失败映射为日期范围不正确`() = runTest {
        val stats = FakeStatsRepository().apply {
            historyResult = statsFailure(StatsFailure.INVALID_QUERY)
        }
        val viewModel = track(historyViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)

        assertEquals(HistoryPhase.ERROR, viewModel.uiState.value.phase)
        assertEquals(StatsErrorMessages.INVALID_RANGE, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `读取失败后可重试`() = runTest {
        val stats = FakeStatsRepository().apply {
            historyQueue += statsFailure(StatsFailure.READ_FAILED)
            historyResult = StatsResult.Success(listOf(daySummary()))
        }
        val viewModel = track(historyViewModel(stats))
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)
        assertEquals(HistoryPhase.ERROR, viewModel.uiState.value.phase)

        viewModel.reload()

        assertEquals(HistoryPhase.READY, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(2, stats.historyCalls.size)
    }

    @Test
    fun `无会话时不取数并进入未登录阶段`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(historyViewModel(stats, FakeStatsUiAuthRepository(SessionState.NONE)))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)

        assertEquals(HistoryPhase.NO_SESSION, viewModel.uiState.value.phase)
        assertTrue(stats.historyCalls.isEmpty())
    }

    @Test
    fun `家长未选定学生时不取数并提示选择学生`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(
            historyViewModel(stats, FakeStatsUiAuthRepository(FakeStatsUiAuthRepository.PARENT_SESSION)),
        )

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)

        assertEquals(HistoryPhase.NO_STUDENT, viewModel.uiState.value.phase)
        assertTrue(stats.historyCalls.isEmpty())
    }

    @Test
    fun `重复 start 为幂等不重复取数`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(historyViewModel(stats))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)
        viewModel.start(FakeStatsUiAuthRepository.STUDENT_ID, DAY_EPOCH - 5, DAY_EPOCH)

        assertEquals(1, stats.historyCalls.size)
        assertEquals(DAY_EPOCH, viewModel.uiState.value.query.startEpochDay)
    }

    // ---- 导航事件 ----

    @Test
    fun `选中某天发出当日盘点导航事件`() = runTest {
        val viewModel = track(historyViewModel(FakeStatsRepository()))

        viewModel.selectDay(DAY_EPOCH - 1)

        assertEquals(HistoryEvent.OpenDaySummary(DAY_EPOCH - 1), viewModel.events.first())
    }

    @Test
    fun `选中某天的导航事件携带的日期可经路由往返供盘点页使用`() = runTest {
        val viewModel = track(historyViewModel(FakeStatsRepository()))
        val selected = DAY_EPOCH - 1

        viewModel.selectDay(selected)

        // 复现 framework 链路：事件日期 -> daySummaryRoute -> NavHost 解析 -> 盘点页取数日期
        val event = viewModel.events.first()
        val epochDay = (event as HistoryEvent.OpenDaySummary).epochDay
        val route = StatsDestination.daySummaryRoute(FakeStatsUiAuthRepository.STUDENT_ID, epochDay)
        val parsed = StatsDestination.epochDayOf(route.substringAfter("${StatsDestination.ARG_EPOCH_DAY}="))

        assertEquals(selected, parsed)
    }

    // ---- 「今天」换算（复用领域层统一入口） ----

    @Test
    fun `缺省今天与领域层统一换算入口同源`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(historyViewModel(stats))

        viewModel.start(
            routeStudentId = StatsDestination.ARG_STUDENT_ID_NONE,
            fromEpochDay = StatsDestination.ARG_EPOCH_DAY_TODAY,
            toEpochDay = StatsDestination.ARG_EPOCH_DAY_TODAY,
        )

        // 复用 StatsCalculations.epochDayOfToday 后行为不变：仍是「固定时钟 + 业务时区」下的今天
        val expectedToday = StatsCalculations.epochDayOfToday(
            DaySummaryViewModelTest.ZONE,
            DaySummaryViewModelTest.FIXED_MILLIS,
        )
        val query = viewModel.uiState.value.query
        assertEquals(expectedToday, query.startEpochDay)
        assertEquals(expectedToday, query.endEpochDay)
        assertEquals(
            listOf(FakeStatsUiAuthRepository.STUDENT_ID to HistoryQuery(expectedToday)),
            stats.historyCalls,
        )
    }

    @Test
    fun `缺省今天按业务时区换算（跨零点不漂移）`() = runTest {
        // 固定时钟 2023-11-13T16:09Z：UTC 口径当天仍是 11-13，业务时区（+08:00）已进入 11-14
        val utcStats = FakeStatsRepository()
        val utc = track(historyViewModel(utcStats, zone = ZoneId.of("UTC")))
        val shanghaiStats = FakeStatsRepository()
        val shanghai = track(historyViewModel(shanghaiStats))

        // 起始日传「今天」哨兵（缺省口径），结束日传固定日期，使查询范围起点即换算出的今天
        utc.start(StatsDestination.ARG_STUDENT_ID_NONE, StatsDestination.ARG_EPOCH_DAY_TODAY, DAY_EPOCH + 1)
        shanghai.start(StatsDestination.ARG_STUDENT_ID_NONE, StatsDestination.ARG_EPOCH_DAY_TODAY, DAY_EPOCH + 1)

        assertEquals(
            StatsCalculations.epochDayOfToday(ZoneId.of("UTC"), DaySummaryViewModelTest.FIXED_MILLIS),
            utcStats.historyCalls.single().second.startEpochDay,
        )
        assertEquals(
            StatsCalculations.epochDayOfToday(
                DaySummaryViewModelTest.ZONE,
                DaySummaryViewModelTest.FIXED_MILLIS,
            ),
            shanghaiStats.historyCalls.single().second.startEpochDay,
        )
        // UTC 口径落在 11-13，业务时区（+08:00）已进入 11-14：证明换算按注入时区而非固定偏移
        assertEquals(DAY_EPOCH - 1, utc.uiState.value.query.startEpochDay)
        assertEquals(DAY_EPOCH, shanghai.uiState.value.query.startEpochDay)
    }

    // ---- 测试辅助 ----

    private fun historyViewModel(
        stats: FakeStatsRepository,
        auth: FakeStatsUiAuthRepository = FakeStatsUiAuthRepository(),
        zone: ZoneId = DaySummaryViewModelTest.ZONE,
    ): HistoryViewModel = HistoryViewModel(
        statsRepository = stats,
        authRepository = auth,
        clock = StatsUiClock(DaySummaryViewModelTest.FIXED_MILLIS),
        zoneId = zone,
    )

    private fun <T : ViewModel> track(viewModel: T): T {
        createdViewModels += viewModel
        return viewModel
    }
}