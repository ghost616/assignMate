package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.stats.data.StatsFailure
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.StatsCalculations
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DaySummaryViewModel] 单测：路由参数 + 会话 -> 目标学生解析（防越权）、阶段流转
 * （LOADING/READY/NO_SESSION/NO_STUDENT/ERROR）、文案投影、幂等 start 与重试、导航事件。
 *
 * 测试口径：固定时钟 + 业务时区 Asia/Shanghai（与仓库当日窗口同源），
 * 时钟取 2023-11-14 00:09:00 +08:00，对应当日纪元日 [DAY_EPOCH]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DaySummaryViewModelTest {

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

    // ---- 正向：学生会话固定本人 ----

    @Test
    fun `学生会话加载当日盘点并投影为展示文案`() = runTest {
        val stats = FakeStatsRepository()
        val auth = FakeStatsUiAuthRepository().apply {
            addStudentProfile(FakeStatsUiAuthRepository.STUDENT_ID, PARENT_ID, "小明")
        }
        val viewModel = track(daySummaryViewModel(stats, auth))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(DaySummaryPhase.READY, state.phase)
        assertEquals(FakeStatsUiAuthRepository.STUDENT_ID, state.studentId)
        assertEquals("小明", state.studentName)
        assertEquals("已完成 1 / 2 项", state.progressText)
        assertEquals("50.0%", state.percentText)
        assertEquals(0.5f, state.progress, 1e-6f)
        assertEquals("暂停 1 次", state.pauseCountText)
        assertEquals("2 分钟", state.pausedTotalText)
        assertEquals("「语文生字」暂停最久：2 分钟（1 次）", state.mostPausedText)
        assertFalse(state.isEmpty)
        assertNull(state.errorMessage)
        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to DAY_EPOCH), stats.dayCalls)
    }

    @Test
    fun `学生会话忽略路由传入的他人学生（防越权）`() = runTest {
        val stats = FakeStatsRepository()
        val auth = FakeStatsUiAuthRepository()

        val viewModel = track(daySummaryViewModel(stats, auth))
        viewModel.start(FakeStatsUiAuthRepository.OTHER_STUDENT_ID)

        assertEquals(FakeStatsUiAuthRepository.STUDENT_ID, viewModel.uiState.value.studentId)
        assertEquals(FakeStatsUiAuthRepository.STUDENT_ID, stats.dayCalls.single().first)
    }

    @Test
    fun `家长会话按路由加载名下学生`() = runTest {
        val stats = FakeStatsRepository()
        val auth = FakeStatsUiAuthRepository(FakeStatsUiAuthRepository.PARENT_SESSION)
        val viewModel = track(daySummaryViewModel(stats, auth))

        viewModel.start(FakeStatsUiAuthRepository.STUDENT_ID)

        assertEquals(DaySummaryPhase.READY, viewModel.uiState.value.phase)
        assertEquals(FakeStatsUiAuthRepository.STUDENT_ID, stats.dayCalls.single().first)
    }

    @Test
    fun `当日无记录时进入空态且文案为零值`() = runTest {
        val stats = FakeStatsRepository().apply {
            dayResult = StatsResult.Success(
                daySummary(
                    totalCount = 0,
                    completedCount = 0,
                    pauseCount = 0,
                    pausedTotalMillis = 0L,
                    mostPausedItem = null,
                ),
            )
        }
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        val state = viewModel.uiState.value
        assertTrue(state.isEmpty)
        assertEquals(DaySummaryPhase.READY, state.phase)
        assertEquals("已完成 0 / 0 项", state.progressText)
        assertEquals("0.0%", state.percentText)
        assertEquals(0f, state.progress, 0f)
        assertNull(state.mostPausedText)
    }

    // ---- 反向：会话/学生缺失与仓库失败 ----

    @Test
    fun `无会话时进入未登录阶段且不取数`() = runTest {
        val stats = FakeStatsRepository()
        val auth = FakeStatsUiAuthRepository(SessionState.NONE)
        val viewModel = track(daySummaryViewModel(stats, auth))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        assertEquals(DaySummaryPhase.NO_SESSION, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.studentId)
        assertTrue(stats.dayCalls.isEmpty())
    }

    @Test
    fun `家长未选定学生时提示先选择学生且不取数`() = runTest {
        val stats = FakeStatsRepository()
        val auth = FakeStatsUiAuthRepository(FakeStatsUiAuthRepository.PARENT_SESSION)
        val viewModel = track(daySummaryViewModel(stats, auth))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        assertEquals(DaySummaryPhase.NO_STUDENT, viewModel.uiState.value.phase)
        assertTrue(stats.dayCalls.isEmpty())
    }

    @Test
    fun `越权失败映射为可读文案并可重试`() = runTest {
        val stats = FakeStatsRepository().apply {
            dayQueue += statsFailure(StatsFailure.ACCESS_DENIED)
            dayResult = StatsResult.Success(daySummary())
        }
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)
        assertEquals(DaySummaryPhase.ERROR, viewModel.uiState.value.phase)
        assertEquals(StatsErrorMessages.ACCESS_DENIED, viewModel.uiState.value.errorMessage)

        viewModel.reload()
        assertEquals(DaySummaryPhase.READY, viewModel.uiState.value.phase)
        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(2, stats.dayCalls.size)
    }

    @Test
    fun `读取失败映射为稍后重试文案`() = runTest {
        val stats = FakeStatsRepository().apply {
            dayResult = statsFailure(StatsFailure.READ_FAILED)
        }
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        assertEquals(StatsErrorMessages.READ_FAILED, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `未 start 时 reload 不触发取数`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        viewModel.reload()

        assertTrue(stats.dayCalls.isEmpty())
        assertFalse(viewModel.uiState.value.started)
    }

    @Test
    fun `重复 start 为幂等不重复取数`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)
        viewModel.start(FakeStatsUiAuthRepository.STUDENT_ID)

        assertEquals(1, stats.dayCalls.size)
    }

    // ---- 导航事件 ----

    @Test
    fun `查看详情发出携带作业 id 的导航事件`() = runTest {
        val viewModel = track(daySummaryViewModel(FakeStatsRepository(), FakeStatsUiAuthRepository()))

        viewModel.openItemDetail(7L)

        assertEquals(DaySummaryEvent.OpenItemDetail(7L), viewModel.events.first())
    }

    @Test
    fun `非法作业 id 不发出导航事件`() = runTest {
        val viewModel = track(daySummaryViewModel(FakeStatsRepository(), FakeStatsUiAuthRepository()))

        viewModel.openItemDetail(0L)
        viewModel.openItemDetail(-3L)

        assertNull(withTimeoutOrNull(EVENT_WAIT_MILLIS) { viewModel.events.first() })
    }

    @Test
    fun `查看历史在已解析学生后发出事件`() = runTest {
        val viewModel = track(daySummaryViewModel(FakeStatsRepository(), FakeStatsUiAuthRepository()))
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        viewModel.openHistory()

        assertEquals(
            DaySummaryEvent.OpenHistory(FakeStatsUiAuthRepository.STUDENT_ID),
            viewModel.events.first(),
        )
    }

    @Test
    fun `未解析出学生时查看历史不发出事件`() = runTest {
        val viewModel = track(
            daySummaryViewModel(FakeStatsRepository(), FakeStatsUiAuthRepository(SessionState.NONE)),
        )
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        viewModel.openHistory()

        assertNull(withTimeoutOrNull(EVENT_WAIT_MILLIS) { viewModel.events.first() })
    }

    // ---- 盘点日期（epochDay 路由参数） ----

    @Test
    fun `缺省日期（哨兵）按下今天取数`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to DAY_EPOCH), stats.dayCalls)
        assertEquals(DAY_EPOCH, viewModel.uiState.value.epochDay)
        assertTrue(viewModel.uiState.value.isToday)
        assertEquals("今天的作业盘点", viewModel.uiState.value.titleText)
    }

    @Test
    fun `指定历史日期时按该日取数并展示日期文案`() = runTest {
        val stats = FakeStatsRepository().apply {
            dayResult = StatsResult.Success(daySummary(epochDay = DAY_EPOCH - 1))
        }
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH - 1)

        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to (DAY_EPOCH - 1)), stats.dayCalls)
        val state = viewModel.uiState.value
        assertEquals(DAY_EPOCH - 1, state.epochDay)
        assertFalse(state.isToday)
        assertEquals(StatsCalculations.dateText(DAY_EPOCH - 1), state.dateText)
        assertEquals("2023-11-13 的作业盘点", state.titleText)
    }

    @Test
    fun `文案口径随盘点日期收敛（今天与历史日）`() = runTest {
        val todayViewModel = track(daySummaryViewModel(FakeStatsRepository(), FakeStatsUiAuthRepository()))
        todayViewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        // 状态默认态（首帧加载态，日期尚未解析）按「今天」口径渲染，与无历史参数的既有行为一致
        assertEquals("正在盘点今天的作业…", DaySummaryUiState().loadingText)
        val todayState = todayViewModel.uiState.value
        assertTrue(todayState.isToday)
        assertEquals("正在盘点今天的作业…", todayState.loadingText)
        assertEquals("今天的作业盘点", todayState.titleText)
        assertEquals("今天要做的作业（2 项）", todayState.itemListTitle)
        assertEquals(StatsErrorMessages.NO_DATA_TODAY, todayState.emptyText)

        val historyStats = FakeStatsRepository().apply {
            dayResult = StatsResult.Success(daySummary(epochDay = DAY_EPOCH - 1))
        }
        val historyViewModel = track(daySummaryViewModel(historyStats, FakeStatsUiAuthRepository()))
        historyViewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH - 1)

        val historyState = historyViewModel.uiState.value
        assertFalse(historyState.isToday)
        assertEquals("2023-11-13", historyState.dateText)
        assertEquals("正在盘点 2023-11-13 的作业…", historyState.loadingText)
        assertEquals("2023-11-13 的作业盘点", historyState.titleText)
        assertEquals("这一天要做的作业（2 项）", historyState.itemListTitle)
        assertEquals(StatsErrorMessages.NO_DATA, historyState.emptyText)
    }

    @Test
    fun `非法日期参数回落今天`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        // 路由缺失/非法由 epochDayOf 统一回落 [-1] 哨兵，再按「今天」解析
        viewModel.start(
            StatsDestination.ARG_STUDENT_ID_NONE,
            StatsDestination.epochDayOf("不是数字"),
        )

        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to DAY_EPOCH), stats.dayCalls)
        assertEquals(DAY_EPOCH, viewModel.uiState.value.epochDay)
        assertTrue(viewModel.uiState.value.isToday)
    }

    @Test
    fun `路由日期经 daySummaryRoute 往返后仍按该日取数`() = runTest {
        val stats = FakeStatsRepository().apply {
            dayResult = StatsResult.Success(daySummary(epochDay = DAY_EPOCH - 2))
        }
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        // 复现 framework 链路：历史页拼装路由 -> NavHost 解析 epochDay -> 传入页面
        val route = StatsDestination.daySummaryRoute(FakeStatsUiAuthRepository.STUDENT_ID, DAY_EPOCH - 2)
        val raw = route.substringAfter("${StatsDestination.ARG_EPOCH_DAY}=")
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, StatsDestination.epochDayOf(raw))

        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to (DAY_EPOCH - 2)), stats.dayCalls)
        assertEquals(DAY_EPOCH - 2, viewModel.uiState.value.epochDay)
    }

    @Test
    fun `无会话时仍记录所选日期供页面展示`() = runTest {
        val stats = FakeStatsRepository()
        val viewModel = track(
            daySummaryViewModel(stats, FakeStatsUiAuthRepository(SessionState.NONE)),
        )

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH - 3)

        val state = viewModel.uiState.value
        assertEquals(DaySummaryPhase.NO_SESSION, state.phase)
        assertEquals(DAY_EPOCH - 3, state.epochDay)
        assertTrue(stats.dayCalls.isEmpty())
    }

    // ---- 当天应做清单行（作业每天详情口径的展示投影） ----

    @Test
    fun `清单行按当天状态与阶段打卡进度投影`() = runTest {
        val stats = FakeStatsRepository().apply {
            dayResult = StatsResult.Success(
                daySummary(
                    totalCount = 2,
                    completedCount = 1,
                    items = listOf(
                        dayItem(homeworkId = 1L, content = "语文生字", status = HomeworkDayStatus.COMPLETED),
                        dayItem(
                            homeworkId = 2L,
                            content = "背单词",
                            status = HomeworkDayStatus.IN_PROGRESS,
                            isStage = true,
                            priority = 101,
                        ),
                    ),
                    stages = listOf(
                        StageProgress(homeworkId = 2L, content = "背单词", doneDays = 3, totalDays = 7),
                    ),
                ),
            )
        }
        val viewModel = track(daySummaryViewModel(stats, FakeStatsUiAuthRepository()))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        val rows = viewModel.uiState.value.itemRows
        assertEquals(listOf(1L, 2L), rows.map { it.homeworkId })
        assertEquals("语文生字", rows.first().content)
        assertEquals("已完成", rows.first().statusText)
        // 当天作业没有阶段打卡进度
        assertNull(rows.first().stageProgressText)
        assertEquals("进行中", rows.last().statusText)
        assertEquals("阶段打卡 3/7 天", rows.last().stageProgressText)
    }

    @Test
    fun `无盘点结果或清单为空时清单行为空而不抛异常`() = runTest {
        val viewModel = track(daySummaryViewModel(FakeStatsRepository(), FakeStatsUiAuthRepository()))

        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE)

        // 缺省结果没有清单条目：投影为空列表而非崩溃
        assertTrue(viewModel.uiState.value.itemRows.isEmpty())
        assertTrue(DaySummaryUiState().itemRows.isEmpty())
    }

    // ---- 事件丢弃路径的不可达性证据（同型隐患核实的用例证据） ----

    /**
     * 当日盘点页事件接线的「丢弃分支不可达」证据（对应一轮风后计划第二项的同型隐患核实）。
     *
     * 背景：`DaySummaryRoute` 消费 `OpenItemDetail` 时用 `uiState.studentId?.let { … }`——学生未解析时
     * 既不导航也无任何提示，形态上是一处**静默丢弃**（历史页同类分支已改接可感知兜底）。本用例逐阶段
     * 证明它**不可达**：清单行（也就是那个唯一的可点击按钮）只在 `summary != null` 时渲染，而
     * `studentId` 与 `summary` 由**同一次** `_uiState.update` 写入、此后不会被重置为 null，
     * 故「按钮可点」恒蕴含「学生已解析」；反过来学生未解析的两个阶段（NO_SESSION / NO_STUDENT）
     * 清单行必为空、页面根本不渲染按钮。因此本页不需要像历史页那样补丢弃兜底。
     */
    @Test
    fun `清单可点击时学生必已解析（事件丢弃分支不可达）`() = runTest {
        // ① 就绪（有清单）：按钮可点 -> 学生必已解析
        val ready = track(
            daySummaryViewModel(
                FakeStatsRepository().apply {
                    dayResult = StatsResult.Success(
                        daySummary(
                            totalCount = 2,
                            completedCount = 1,
                            items = listOf(
                                dayItem(homeworkId = 1L, content = "语文生字", status = HomeworkDayStatus.COMPLETED),
                            ),
                        ),
                    )
                },
                FakeStatsUiAuthRepository(),
            ),
        )
        ready.start(StatsDestination.ARG_STUDENT_ID_NONE)
        val readyState = ready.uiState.value
        assertEquals(DaySummaryPhase.READY, readyState.phase)
        assertTrue("本阶段必须有可点击的清单行，否则本用例失去意义", readyState.itemRows.isNotEmpty())
        assertNotNull("按钮可点时学生必须已解析（OpenItemDetail 丢弃分支因此不可达）", readyState.studentId)

        // ② 就绪（空清单）：即便点了也不存在按钮；学生同样已解析
        val readyEmpty = track(
            daySummaryViewModel(
                FakeStatsRepository().apply {
                    dayResult = StatsResult.Success(daySummary(totalCount = 0, completedCount = 0))
                },
                FakeStatsUiAuthRepository(),
            ),
        )
        readyEmpty.start(StatsDestination.ARG_STUDENT_ID_NONE)
        assertNotNull("就绪态（含空清单）学生必已解析", readyEmpty.uiState.value.studentId)
        assertTrue(readyEmpty.uiState.value.itemRows.isEmpty())

        // ③ 取数失败：学生同样在失败分支写入（页面此时是错误态，无按钮）
        val failed = track(
            daySummaryViewModel(
                FakeStatsRepository().apply { dayResult = statsFailure(StatsFailure.ACCESS_DENIED) },
                FakeStatsUiAuthRepository(),
            ),
        )
        failed.start(StatsDestination.ARG_STUDENT_ID_NONE)
        assertEquals(DaySummaryPhase.ERROR, failed.uiState.value.phase)
        assertNotNull("失败态学生 id 同样已解析（失败提示与重试仍按该学生）", failed.uiState.value.studentId)
        assertTrue(failed.uiState.value.itemRows.isEmpty())

        // ④⑤ 无会话 / 家长未选学生：学生未解析，但清单行必为空 —— 没有任何可点击入口
        val noSession = track(
            daySummaryViewModel(FakeStatsRepository(), FakeStatsUiAuthRepository(SessionState.NONE)),
        )
        noSession.start(StatsDestination.ARG_STUDENT_ID_NONE)
        assertEquals(DaySummaryPhase.NO_SESSION, noSession.uiState.value.phase)
        assertNull(noSession.uiState.value.studentId)
        assertTrue(noSession.uiState.value.itemRows.isEmpty())

        val noStudent = track(
            daySummaryViewModel(
                FakeStatsRepository(),
                FakeStatsUiAuthRepository(FakeStatsUiAuthRepository.PARENT_SESSION),
            ),
        )
        noStudent.start(StatsDestination.ARG_STUDENT_ID_NONE)
        assertEquals(DaySummaryPhase.NO_STUDENT, noStudent.uiState.value.phase)
        assertNull(noStudent.uiState.value.studentId)
        assertTrue(noStudent.uiState.value.itemRows.isEmpty())

        // 全阶段不变量：清单行非空 => 学生已解析（等价于「丢弃分支不可达」）
        listOf(
            readyState,
            readyEmpty.uiState.value,
            failed.uiState.value,
            noSession.uiState.value,
            noStudent.uiState.value,
        ).forEach { state ->
                if (state.itemRows.isNotEmpty()) {
                    assertNotNull("阶段 ${state.phase} 出现可点击清单行却未解析学生：丢弃分支会被触达", state.studentId)
                }
            }
    }

    // ---- 测试辅助 ----

    private fun daySummaryViewModel(
        stats: FakeStatsRepository,
        auth: FakeStatsUiAuthRepository,
    ): DaySummaryViewModel = DaySummaryViewModel(
        statsRepository = stats,
        authRepository = auth,
        clock = StatsUiClock(FIXED_MILLIS),
        zoneId = ZONE,
    )

    private fun <T : ViewModel> track(viewModel: T): T {
        createdViewModels += viewModel
        return viewModel
    }

    companion object {

        const val PARENT_ID = FakeStatsUiAuthRepository.PARENT_ID

        /** 未发出事件时的等待上限（毫秒） */
        const val EVENT_WAIT_MILLIS = 50L

        /** 固定时钟：2023-11-14 00:09:00 +08:00 */
        const val FIXED_MILLIS = 1_699_891_740_000L

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

/** 固定时钟（UI 层替身；不需要数据层那种可推进时钟） */
internal class StatsUiClock(private val millis: Long) : Clock {
    override fun currentTimeMillis(): Long = millis
}