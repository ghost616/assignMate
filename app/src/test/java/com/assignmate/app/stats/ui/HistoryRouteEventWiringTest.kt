package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.stats.data.StatsResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * 历史页「查看这一天的盘点」接线的护栏用例（对应代码审查 error：`HistoryRoute` 从未消费
 * [HistoryViewModel.events]，导致按钮点击无反应、framework 侧 `onOpenDaySummary` 成为死参数）。
 *
 * **覆盖清单（共 14 例，用例数必须与本清单一致）**
 * 1. **行为面（7 例）**：驱动**真实的 [HistoryViewModel.selectDay]**（事件经 Channel 投递）与真实
 *    `uiState` 状态流，断言「学生 + 所选日期」被透传到导航回调；连续多天按顺序逐条透传；学生未解析
 *    时不误导航且走**可感知兜底**；状态稍后才解析出来时事件仍按其导航；状态在两次事件之间更新则第二次
 *    按新状态导航（不缓存首帧）；事件流结束（页面销毁）后消费协程退出；兜底文案
 *    [StatsErrorMessages.daySummaryDroppedText] 是纯函数、携带目标日期与下一步。
 * 2. **接线面（7 例）**：**配对断言**（消费调用**恰好**落在唯一一个 `LaunchedEffect` 的 lambda 体内、
 *    该 `LaunchedEffect` 的 key 为 `viewModel`、体内以引用形式传 `uiState = viewModel.uiState`、接上
 *    `onOpenDaySummary` 与 `onEventDropped`）+ **次序断言**（调用点下标必须大于形参右括号）+
 *    **位置断言**（lambda 体之外不得出现调用点）；**可达性断言**（消费点**最近的未闭合 `{`** 必须是
 *    该 `LaunchedEffect` 的 lambda 体开括号）；**同型接线看护**（当日盘点页 `DaySummaryRoute` 同口径）；
 *    丢弃分支必须接用户可见提示（`showSnackbar(` + `StatsErrorMessages.`）且 Route 挂载 `SnackbarHost`；
 *    Route 不得提前求值学生状态；消费点签名与事件分支覆盖；**可重放的变异自检**。
 *
 * **为什么必须做配对断言（皐陶复审 plan_34106520 实测的假绿）**：原断言只做「函数体文本包含性」检查，
 * 于是把 `consumeHistoryEvents(...)` 搬进一个**永不执行的局部 lambda**、同时保留真实
 * `LaunchedEffect` 时，本文件 8/8 全绿（调用点根本不在任何 `LaunchedEffect` 体内也能通过）；
 * 反之删掉整段 `LaunchedEffect(Unit){...}` 时只有 1 例变红（6 例行为用例直调消费点、完全绕开路由
 * 接线）。收紧后：调用点必须落在**唯一**一个 `LaunchedEffect` 的 lambda 体内，且该 `LaunchedEffect`
 * 以 `viewModel` 为 key；变异 M-a（lambda 声明在 `LaunchedEffect` **之外**）/M-b/M-c/M-e 均变红。
 *
 * **为什么还要再做可达性断言（本轮封口，皐陶第二轮独立实测的假绿）**：配对断言只校验「调用**文本**
 * 落在 `LaunchedEffect` 的 lambda 体**范围内**」，并不排除 lambda 体**内部更深一层**的不可达
 * lambda——把整段消费包进「**声明在 `LaunchedEffect(viewModel)` 体内、但从未被调用的局部 suspend
 * lambda**」后，本文件 11 例仍全绿（`body.contains(...)` 依旧为真）。故本轮补**可达性断言**：用词法
 * 扫描（跳过字符串/字符字面量与注释）向前配平，定位消费点**最近的未闭合 `{`**，要求它**恰好就是**
 * 该 `LaunchedEffect` 的 lambda 体开括号——消费点只在**直接**位于 `LaunchedEffect` 的 lambda 体内时
 * 才真正可达，嵌套在体内任何更深一层 lambda（普通 lambda 或 suspend lambda）中都必须变红。
 *
 * 本仓库无 Robolectric、无 `androidTest` 源集，`HistoryRoute` / `DaySummaryRoute` 的组合体无法在 JVM
 * 上执行（离朱 R6 以「把生产接线换成会抛异常的实现后用例仍全绿」的编译产物实验证实），故「Route 传
 * 什么、有没有提前求值、调用点在不在 lambda 体内、是否嵌套在不可达 lambda 里」只能用源码结构断言看护。
 *
 * **变异自检（可重放）**：本文件自带用例
 * `变异自检（可重放）：接线判定必须杀掉四类变异且对照组复绿`——在**内存中**对原始源码施加
 * M-1（搬进体内嵌套 suspend lambda）/ M-2（删掉整块 `LaunchedEffect`）/ M-3（搬到 lambda 之外）/
 * M-4（key 改回 `Unit`）四种变异，逐一断言判定变红、并断言还原后复绿。重放命令：
 * `gradlew.bat :app:testDebugUnitTest --tests "com.assignmate.app.stats.ui.HistoryRouteEventWiringTest" --rerun-tasks`。
 * 隔离副本内的**真实编译 + 真跑**变异矩阵（逐次按字节还原并按 SHA256 校验与原件一致、工作区无残留）
 * 见 `.lizhu_env/stats_r10/run_mutations_r10.ps1`（脚本内部先跑原始码控制组）。
 *
 * 三重防呆：消费点入参为 `StateFlow`（lambda 形式的「启动时快照」在类型上不可表达，见生产 KDoc）、
 * 结构断言在 `codeOnly` 去注释后的代码上执行（整行/块注释停用接线即变红）、行为用例锁定
 * 「按事件到达时的最新状态读取」与「未解析学生不静默丢弃」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRouteEventWiringTest {

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

    // ---- 行为面：真实 ViewModel 的事件被消费并透传 ----

    @Test
    fun `历史页事件被消费并透传已解析学生与所选日期`() = runTest {
        val viewModel = historyViewModel()
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)
        assertEquals(FakeStatsUiAuthRepository.STUDENT_ID, viewModel.uiState.value.studentId)

        val received = mutableListOf<Pair<Long, Long>>()
        val dropped = mutableListOf<Long>()
        backgroundScope.consumeEvents(
            events = viewModel.events,
            uiState = viewModel.uiState,
            onOpenDaySummary = { studentId, epochDay -> received += studentId to epochDay },
            onEventDropped = { dropped += it },
        )

        viewModel.selectDay(DAY_EPOCH - 3)
        advanceUntilIdle()

        // 完整链路：HistoryViewModel.selectDay -> Channel -> 消费点 -> onOpenDaySummary
        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to (DAY_EPOCH - 3)), received)
        assertTrue("学生已解析时不得走丢弃分支", dropped.isEmpty())
    }

    @Test
    fun `连续选中多天按顺序逐条透传`() = runTest {
        val viewModel = historyViewModel()
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)

        val received = mutableListOf<Long>()
        backgroundScope.consumeEvents(
            events = viewModel.events,
            uiState = viewModel.uiState,
            onOpenDaySummary = { _, epochDay -> received += epochDay },
            onEventDropped = { error("学生已解析，不应走丢弃分支") },
        )

        viewModel.selectDay(DAY_EPOCH - 1)
        viewModel.selectDay(DAY_EPOCH - 2)
        advanceUntilIdle()

        assertEquals(listOf(DAY_EPOCH - 1, DAY_EPOCH - 2), received)
    }

    @Test
    fun `学生未解析时不导航但给出可感知兜底`() = runTest {
        val viewModel = historyViewModel(auth = FakeStatsUiAuthRepository(SessionState.NONE))
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)
        assertNull(viewModel.uiState.value.studentId)

        val received = mutableListOf<Pair<Long, Long>>()
        val dropped = mutableListOf<Long>()
        backgroundScope.consumeEvents(
            events = viewModel.events,
            uiState = viewModel.uiState,
            onOpenDaySummary = { studentId, epochDay -> received += studentId to epochDay },
            onEventDropped = { dropped += it },
        )

        viewModel.selectDay(DAY_EPOCH - 1)
        advanceUntilIdle()

        assertTrue("会话失效/未选学生时不应误导航", received.isEmpty())
        // 复审信息项：原实现对未解析学生的事件静默丢弃 —— 用户点了按钮既不跳转也无提示（可感知死路）
        assertEquals("未解析学生的事件必须走可感知兜底（不得静默丢弃）", listOf(DAY_EPOCH - 1), dropped)
    }

    @Test
    fun `学生稍后解析出来时事件仍按其导航（延迟读取而非启动时快照）`() = runTest {
        // 消费协程是在学生「尚未解析」时启动的：若消费点在启动时把状态快照成 val，本用例必然失败。
        val auth = FakeStatsUiAuthRepository(SessionState.NONE)
        val viewModel = historyViewModel(auth = auth)
        viewModel.start(StatsDestination.ARG_STUDENT_ID_NONE, DAY_EPOCH, DAY_EPOCH)
        assertNull(viewModel.uiState.value.studentId)

        val received = mutableListOf<Pair<Long, Long>>()
        backgroundScope.consumeEvents(
            events = viewModel.events,
            uiState = viewModel.uiState,
            onOpenDaySummary = { studentId, epochDay -> received += studentId to epochDay },
            onEventDropped = { error("本用例的事件必须在学生解析之后到达，不应走丢弃分支") },
        )

        // 会话恢复后刷新，学生解析出来；此后的事件必须能携带真实学生导航
        auth.setSession(FakeStatsUiAuthRepository.STUDENT_SESSION)
        viewModel.reload()
        assertEquals(FakeStatsUiAuthRepository.STUDENT_ID, viewModel.uiState.value.studentId)

        viewModel.selectDay(DAY_EPOCH - 2)
        advanceUntilIdle()

        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to (DAY_EPOCH - 2)), received)
    }

    @Test
    fun `消费点按事件到达时的最新状态读取学生（不缓存首帧）`() = runTest {
        // 状态流转发生在两次事件之间：第一次不导航（走兜底）、第二次必须按新状态导航。
        val channel = Channel<HistoryEvent>(Channel.BUFFERED)
        val uiState = MutableStateFlow(HistoryUiState())
        val received = mutableListOf<Pair<Long, Long>>()
        val dropped = mutableListOf<Long>()
        backgroundScope.consumeEvents(
            events = channel.receiveAsFlow(),
            uiState = uiState,
            onOpenDaySummary = { studentId, epochDay -> received += studentId to epochDay },
            onEventDropped = { dropped += it },
        )

        channel.send(HistoryEvent.OpenDaySummary(DAY_EPOCH - 1))
        advanceUntilIdle()
        assertTrue("学生尚未解析时不应导航", received.isEmpty())
        assertEquals("未解析学生的事件必须走兜底出口", listOf(DAY_EPOCH - 1), dropped)

        uiState.value = HistoryUiState(studentId = FakeStatsUiAuthRepository.STUDENT_ID)
        channel.send(HistoryEvent.OpenDaySummary(DAY_EPOCH - 2))
        advanceUntilIdle()

        assertEquals(listOf(FakeStatsUiAuthRepository.STUDENT_ID to (DAY_EPOCH - 2)), received)
        assertEquals("解析成功后不再有事件走兜底出口", listOf(DAY_EPOCH - 1), dropped)
    }

    @Test
    fun `事件流结束后消费协程退出而不残留`() = runTest {
        val channel = Channel<HistoryEvent>(Channel.BUFFERED)
        val uiState = MutableStateFlow(HistoryUiState(studentId = FakeStatsUiAuthRepository.STUDENT_ID))
        var collected = 0
        val consumer = backgroundScope.consumeEvents(
            events = channel.receiveAsFlow(),
            uiState = uiState,
            onOpenDaySummary = { _, _ -> collected++ },
            onEventDropped = { error("学生已解析，不应走丢弃分支") },
        )

        channel.send(HistoryEvent.OpenDaySummary(DAY_EPOCH))
        advanceUntilIdle()
        assertEquals(1, collected)

        channel.close()
        advanceUntilIdle()
        assertFalse("事件流结束（页面销毁）后消费协程应退出，避免 Channel 事件堆积", consumer.isActive)
    }

    @Test
    fun `丢弃事件的可感知兜底文案携带目标日期并给出下一步`() {
        // 兜底文案是纯函数：即使无法在 JVM 上跑 Compose，也能锁定「用户看到什么」
        val text = StatsErrorMessages.daySummaryDroppedText(DAY_EPOCH)

        assertTrue("兜底文案必须点明是哪一天没反应：$text", text.contains("2023-11-14"))
        assertTrue("兜底文案必须给出下一步动作：$text", text.contains("返回"))
        assertFalse("兜底文案不得是空串/空白", text.isBlank())
    }

    // ---- 接线面：HistoryRoute 真的在 LaunchedEffect 的 lambda 体内消费事件（配对断言） ----

    @Test
    fun `接线断言：事件消费必须写在 LaunchedEffect(viewModel) 的 lambda 体内`() {
        val route = functionBodyOf(codeOnly(readSource(HISTORY_SCREEN_SOURCE)), "fun HistoryRoute(")
        val blocks = launchedEffectBlocks(route)
        assertTrue(
            "未扫描到足够的 LaunchedEffect 接线块，结构断言可能失效（括号配平或路径解析出错）：$blocks",
            blocks.size >= 2,
        )

        // 配对断言：先括号配平取出每个 LaunchedEffect 的 lambda 体，再要求消费点**恰好**落在其中一个体内
        val consumerBlocks = blocks.filter { it.body.contains("consumeHistoryEvents(") }
        assertEquals(
            "事件消费必须写在（且只能写在一个）LaunchedEffect 的 lambda 体内：" +
                "把调用搬进永不执行的局部 lambda、搬到 lambda 之外、或把整块 LaunchedEffect 删掉都必须变红",
            1,
            consumerBlocks.size,
        )
        val block = consumerBlocks.single()
        assertEquals(
            "事件消费的 LaunchedEffect 必须以 viewModel 为 key（宿主替换实例时旧协程随之取消、" +
                "新实例事件仍有人消费）",
            "viewModel",
            block.key,
        )
        assertTrue(
            "消费点必须把状态流本体交给消费点（读取时机归消费点所有）：${block.body}",
            block.body.contains("uiState = viewModel.uiState"),
        )
        assertTrue(
            "消费点必须接到 onOpenDaySummary：${block.body}",
            block.body.contains("onOpenDaySummary = onOpenDaySummary"),
        )
        assertTrue(
            "消费点必须接上丢弃兜底出口（否则未解析学生时仍是静默死路）：${block.body}",
            block.body.contains("onEventDropped ="),
        )

        // 次序断言：调用点必须位于 LaunchedEffect 的形参之后
        val callIndex = route.indexOf("consumeHistoryEvents(")
        assertTrue("未找到消费调用点", callIndex >= 0)
        assertTrue(
            "消费调用必须位于 LaunchedEffect(...) 形参之后（callIndex=$callIndex, paramsEnd=${block.paramsEnd}）",
            callIndex > block.paramsEnd,
        )
        // 位置断言：LaunchedEffect 的 lambda 体之外不得出现任何消费调用点
        val outside = Regex("consumeHistoryEvents\\(").findAll(route)
            .map { it.range.first }
            .filterNot { it in block.bodyStart..block.bodyEnd }
            .toList()
        assertTrue("LaunchedEffect 的 lambda 体之外不得出现消费调用点（下标）：$outside", outside.isEmpty())
    }

    /**
     * 可达性断言（本轮封口）：配对断言只要求消费调用**文本**落在某个 `LaunchedEffect` 的 lambda 体
     * **范围内**，于是「把整段消费包进一个**声明在该 lambda 体内部、却从未被调用**的局部 suspend
     * lambda」照样全绿（皐陶第二轮独立实测复现，11/11 绿）。本用例改用词法扫描向前配平，要求消费点
     * **最近的未闭合 `{`** 恰好就是该 `LaunchedEffect` 的 lambda 体开括号：只有**直接**写在体内时才可达。
     */
    @Test
    fun `接线断言：消费点必须直接位于 LaunchedEffect(viewModel) 的 lambda 体内（可达性封口）`() {
        val route = functionBodyOf(codeOnly(readSource(HISTORY_SCREEN_SOURCE)), "fun HistoryRoute(")

        val findings = wiringFindings(route, CONSUME_CALL, EXPECTED_KEY)

        assertTrue(
            "消费点必须直接位于 LaunchedEffect(viewModel) 的 lambda 体内，实得违例：$findings",
            findings.isEmpty(),
        )
    }

    /**
     * 同型接线看护：当日盘点页 `DaySummaryRoute` 与历史页属同一模式（`LaunchedEffect` + Channel 一次性
     * 事件），此前它用 `LaunchedEffect(Unit)` 消费事件——宿主替换 ViewModel 实例时旧协程会挂在废弃实例
     * 的 Channel 上、新实例的「查看这一项详情 / 查看历史完成情况」无人消费，界面表现为点击无反应。
     * 本用例把历史页的判据原样套到该屏：消费点必须**直接**位于 `LaunchedEffect(viewModel)` 的 lambda 体内。
     */
    @Test
    fun `同型接线看护：当日盘点页事件消费的 LaunchedEffect 必须以 viewModel 为 key 且消费点直接位于体内`() {
        val route = functionBodyOf(codeOnly(readSource(DAY_SUMMARY_SCREEN_SOURCE)), "fun DaySummaryRoute(")

        val findings = wiringFindings(route, DAY_SUMMARY_CONSUME_ANCHOR, EXPECTED_KEY)
        assertTrue(
            "当日盘点页的事件消费接线不合格，实得违例：$findings",
            findings.isEmpty(),
        )
        // 反向断言：直接钉死同型隐患本身（key 一旦退回 Unit 即变红，不必依赖上面的通用判据）
        assertFalse(
            "当日盘点页不得再用 LaunchedEffect(Unit) 消费事件（宿主替换 ViewModel 实例时旧协程会挂在废弃 Channel 上）",
            route.contains("LaunchedEffect(Unit)"),
        )
    }

    /**
     * 变异自检（**可重放**）：把「接线判定」本身当成被测对象——在内存中对原始源码施加四类变异，
     * 断言判定全部变红，并在每次变异后断言原始码复绿（对照组）。
     *
     * 为什么必须可重放：此前变异自检只写在注释里、靠人工在隔离副本里手跑脚本，无法随代码库回归。
     * 本用例把同一判据（[wiringFindings]，与护栏用例共用实现，避免两处判据漂移）跑在四种变异源码上，
     * 一条命令即可复现：
     * `gradlew.bat :app:testDebugUnitTest --tests "com.assignmate.app.stats.ui.HistoryRouteEventWiringTest" --rerun-tasks`。
     * 其中 M-1 同时断言「旧的纯文本包含性判据在该变异下仍为真」，把上一轮的假绿钉在用例里。
     */
    @Test
    fun `变异自检（可重放）：接线判定必须杀掉四类变异且对照组复绿`() {
        val pristine = readSource(HISTORY_SCREEN_SOURCE)
        val routeOf: (String) -> String = { source -> functionBodyOf(codeOnly(source), "fun HistoryRoute(") }

        // 先跑原始码控制组：判定本身若在原始码上就报违例，变异自检没有意义
        val controlFindings = wiringFindings(routeOf(pristine), CONSUME_CALL, EXPECTED_KEY)
        assertTrue("对照组失败：原始接线未通过判定，变异自检无意义：$controlFindings", controlFindings.isEmpty())

        MUTATIONS.forEach { mutation ->
            val mutated = mutatedSource(pristine, mutation.id)
            assertFalse(
                "变异未生效：${mutation.description}（锚点改写失败，自检本身失效）",
                mutated == pristine,
            )

            val findings = wiringFindings(routeOf(mutated), CONSUME_CALL, EXPECTED_KEY)
            assertTrue(
                "变异存活（假绿）：${mutation.description} —— 接线判定必须使其变红，实得违例清单：$findings",
                findings.isNotEmpty(),
            )

            // 还原（等价于逐字节还原原件）后必须复绿
            assertTrue(
                "变异还原后未复绿：${mutation.description}",
                wiringFindings(routeOf(pristine), CONSUME_CALL, EXPECTED_KEY).isEmpty(),
            )
        }

        // M-1 是上一轮的假绿来源：配对断言的「文本包含性」子判据在该变异下**依然为真**，
        // 说明单靠包含性判据无法收口，必须叠加可达性断言（本用例的价值所在）。
        val nestedLambda = mutatedSource(pristine, MUTATION_NESTED_LAMBDA)
        assertTrue(
            "M-1 必须复现上一轮的假绿形态：被包进体内嵌套 lambda 后，消费调用文本仍在 LaunchedEffect 的 " +
                "lambda 体范围内（故旧的「范围内包含」判据为真、无法杀掉它）",
            routeOf(nestedLambda).contains(CONSUME_CALL),
        )
    }

    @Test
    fun `接线断言：被丢弃的事件必须接到用户可见提示（不静默丢弃）`() {
        val route = functionBodyOf(codeOnly(readSource(HISTORY_SCREEN_SOURCE)), "fun HistoryRoute(")
        val dropBody = lambdaBodyAfter(route, "onEventDropped =")

        assertNotNull("消费点必须把 onEventDropped 接上，否则未解析学生的事件仍是静默丢弃", dropBody)
        assertTrue(
            "丢弃分支必须给出用户可见的一次性提示（本页取 Snackbar）：$dropBody",
            dropBody!!.contains("showSnackbar("),
        )
        assertTrue(
            "丢弃分支的提示文案必须走 StatsErrorMessages 统一文案出口：$dropBody",
            dropBody.contains("StatsErrorMessages."),
        )
        assertTrue("Route 必须挂载提示宿主（SnackbarHost），否则提示不可见", route.contains("SnackbarHost("))
    }

    @Test
    fun `接线断言：Route 不得提前求值学生状态`() {
        val route = functionBodyOf(codeOnly(readSource(HISTORY_SCREEN_SOURCE)), "fun HistoryRoute(")

        assertTrue("HistoryRoute 必须消费 viewModel.events（缺失该接线时历史条目点击无反应）", route.contains("viewModel.events"))
        assertFalse(
            "Route 不得提前求值状态（如 viewModel.uiState.value / val snapshot = …）",
            route.contains("viewModel.uiState.value"),
        )
        assertFalse(
            "Route 不得把学生 id 提前取出（如 val snapshot = uiState.studentId）",
            Regex("val\\s+\\w+\\s*=\\s*uiState\\.studentId").containsMatchIn(route),
        )
    }

    @Test
    fun `消费点以状态流取学生并覆盖全部一次性事件分支`() {
        val code = codeOnly(readSource(HISTORY_SCREEN_SOURCE))
        val consumer = functionBodyOf(code, "fun consumeHistoryEvents(")

        // 签名断言取「声明到右括号」的参数列表（functionBodyOf 返回的是函数体，不含形参）
        val signatureStart = code.indexOf("fun consumeHistoryEvents(")
        assertTrue("未找到消费点声明", signatureStart >= 0)
        val signature = code.substring(signatureStart, code.indexOf(")", signatureStart))
        assertTrue(
            "消费点必须直接接收 StateFlow（lambda 形式允许调用方提前快照，见生产 KDoc）：$signature",
            signature.contains("uiState: StateFlow<HistoryUiState>"),
        )
        // HistoryEvent 当前只有「进入某日盘点」一种；新增事件分支时本断言提示同步消费逻辑
        HistoryEvent::class.java.declaredClasses.map { it.simpleName }.forEach { name ->
            assertTrue("事件 $name 未在消费点处理", consumer.contains(name))
        }
    }

    // ---- 测试辅助 ----

    /** 启动消费协程：与 [HistoryRoute] 同构，消费点的四个入参全部显式传入（不给默认值以免掩盖接线） */
    private fun CoroutineScope.consumeEvents(
        events: Flow<HistoryEvent>,
        uiState: StateFlow<HistoryUiState>,
        onOpenDaySummary: (studentId: Long, epochDay: Long) -> Unit,
        onEventDropped: (epochDay: Long) -> Unit,
    ): Job = launch(dispatcher) {
        consumeHistoryEvents(
            events = events,
            uiState = uiState,
            onOpenDaySummary = onOpenDaySummary,
            onEventDropped = onEventDropped,
        )
    }

    private fun historyViewModel(
        auth: FakeStatsUiAuthRepository = FakeStatsUiAuthRepository(),
    ): HistoryViewModel = track(
        HistoryViewModel(
            statsRepository = FakeStatsRepository().apply {
                historyResult = StatsResult.Success(listOf(daySummary()))
            },
            authRepository = auth,
            clock = StatsUiClock(DaySummaryViewModelTest.FIXED_MILLIS),
            zoneId = DaySummaryViewModelTest.ZONE,
        ),
    )

    /** 登记 ViewModel 以便用例结束时取消其 scope（与模块内其它 UI 用例一致） */
    private fun <T : ViewModel> track(viewModel: T): T {
        createdViewModels += viewModel
        return viewModel
    }

    /** 按相对 `app/` 的路径读取生产源码（Gradle 单测工作目录为 app/，同时兼容从仓库根运行） */
    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"), File("../app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative，尝试路径：${candidates.map { it.absolutePath }}", file)
        return file!!.readText()
    }

    /**
     * 去掉注释后的源码（接线结构断言必须建立在**有效代码**上）。
     *
     * 为什么必须去注释：断言判据是「某段接线代码是否存在/位于哪个 lambda 体内」，而**被整行注释掉的接线**
     * 同样包含该文本，会出现「把接线整段停用也能通过」的假绿（离朱 R5 变异 M1 实测：注释掉
     * `LaunchedEffect(viewModel) { consumeHistoryEvents(...) }` 后原本 6 例全绿）。去掉注释后该形态即变红。
     */
    private fun codeOnly(source: String): String {
        val withoutBlockComments = Regex("/\\*[\\s\\S]*?\\*/").replace(source, "")
        return withoutBlockComments.lineSequence()
            .filterNot { line -> line.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    /** 以括号配平提取函数体（跳过字符串字面量），供接线结构断言 */
    private fun functionBodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("未找到 $signature", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("未找到 $signature 的函数体", open > 0)
        val close = matchingDelimiter(source, open, '{', '}')
        return source.substring(open, close + 1)
    }

    /**
     * 提取源码中**所有** `LaunchedEffect(<形参>) { <lambda 体> }`（括号配平，跳过字符串字面量）。
     *
     * 这是「配对断言」的核心工具：只看函数体文本是否包含某个调用点会被「搬进永不执行的局部 lambda」
     * 之类的形态骗过（见类注释里皐陶复现的假绿），必须先把 lambda 体**配平切出来**再断言其内容。
     */
    private fun launchedEffectBlocks(source: String): List<EffectBlock> {
        val blocks = mutableListOf<EffectBlock>()
        var searchFrom = 0
        while (true) {
            val start = source.indexOf(LAUNCHED_EFFECT, searchFrom)
            if (start < 0) break
            val paramsOpen = start + LAUNCHED_EFFECT.length - 1
            val paramsEnd = matchingDelimiter(source, paramsOpen, '(', ')')
            val key = source.substring(paramsOpen + 1, paramsEnd).trim()
            val bodyOpen = source.indexOf('{', paramsEnd)
            assertTrue("$LAUNCHED_EFFECT$key 缺少尾随 lambda", bodyOpen > paramsEnd)
            val bodyEnd = matchingDelimiter(source, bodyOpen, '{', '}')
            blocks += EffectBlock(
                key = key,
                paramsEnd = paramsEnd,
                body = source.substring(bodyOpen + 1, bodyEnd),
                bodyOpen = bodyOpen,
                bodyStart = bodyOpen + 1,
                bodyEnd = bodyEnd - 1,
            )
            searchFrom = paramsEnd
        }
        return blocks
    }

    /** 取 `anchor`（如 `onEventDropped =`）之后的第一个 lambda 体文本；找不到锚点或 lambda 时返回 null */
    private fun lambdaBodyAfter(source: String, anchor: String): String? {
        val anchorIndex = source.indexOf(anchor)
        if (anchorIndex < 0) return null
        val bodyOpen = source.indexOf('{', anchorIndex)
        if (bodyOpen < 0) return null
        val bodyEnd = matchingDelimiter(source, bodyOpen, '{', '}')
        return source.substring(bodyOpen + 1, bodyEnd)
    }

    /** 从 `openIndex` 处的开括号出发配平到对应闭括号，返回闭括号下标（跳过字符串字面量） */
    private fun matchingDelimiter(source: String, openIndex: Int, openChar: Char, closeChar: Char): Int {
        var depth = 0
        var index = openIndex
        while (index < source.length) {
            when (source[index]) {
                '"' -> index = closingQuote(source, index)
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) {
                        return index
                    }
                }
            }
            index++
        }
        throw AssertionError("括号未配平：'$openChar' ... '$closeChar'（起始下标 $openIndex）")
    }

    /** 跳过字符串字面量（含转义），返回收尾引号的下标 */
    private fun closingQuote(source: String, openQuote: Int): Int {
        var index = openQuote + 1
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index++
                '"' -> return index
            }
            index++
        }
        return source.length - 1
    }

    // ---- 接线判定（护栏用例与变异自检共用同一实现，避免两处判据漂移） ----

    /**
     * 接线判定：一份函数体源码在外观上是否满足「事件消费接线」的全部结构要求。
     *
     * 判据（任一不满足即产出一条违例文案，空清单 = 通过）：
     * 1. 必须能配平切出 `LaunchedEffect(...)` 接线块，且至少存在两个（取数块 + 消费块）；
     * 2. 消费锚点文本必须**恰好**出现在**一个** `LaunchedEffect` 的 lambda 体范围内；
     * 3. 承载它的 `LaunchedEffect` 的 key 必须等于 [expectedKey]；
     * 4. **可达性**：消费锚点**最近的未闭合 `{`** 必须恰好是该 `LaunchedEffect` 的 lambda 体开括号
     *    ——即消费点必须**直接**写在体内，而非嵌套在体内更深一层的 lambda（普通 lambda / suspend
     *    lambda）里；后者永不执行，属「假绿」形态（皐陶第二轮实测复现）；
     * 5. 消费锚点在 lambda 体之外不得出现任何调用点。
     *
     * @param functionBody 已由 [functionBodyOf] 切出的函数体源码（下标均相对于该字符串）
     * @param consumerAnchor 消费点锚点文本（历史页为 `consumeHistoryEvents(`，当日盘点页为
     *   `viewModel.events.collect`）
     * @param expectedKey 事件消费协程必须使用的 `LaunchedEffect` key（本模块统一为 `viewModel`）
     */
    private fun wiringFindings(
        functionBody: String,
        consumerAnchor: String,
        expectedKey: String,
    ): List<String> {
        val findings = mutableListOf<String>()
        val blocks = launchedEffectBlocks(functionBody)
        if (blocks.size < 2) {
            findings += "未扫描到足够的 LaunchedEffect 接线块，结构断言可能失效（括号配平或路径解析出错）：$blocks"
        }

        val consumerBlocks = blocks.filter { it.body.contains(consumerAnchor) }
        if (consumerBlocks.size != 1) {
            findings += "「$consumerAnchor」必须恰好写在一个 LaunchedEffect 的 lambda 体内（拿不到任何体内文本、" +
                "或出现在多个体内），实得 ${consumerBlocks.size} 个"
        }
        consumerBlocks.forEach { block ->
            if (block.key != expectedKey) {
                findings += "事件消费的 LaunchedEffect 必须以 $expectedKey 为 key（宿主替换实例时旧协程随之取消），" +
                    "实得「${block.key}」"
            }
        }

        val callSites = Regex(Regex.escape(consumerAnchor)).findAll(functionBody)
            .map { it.range.first }
            .toList()
        if (callSites.isEmpty()) {
            findings += "未找到「$consumerAnchor」调用点（整块接线可能已被删除）"
            return findings
        }

        callSites.forEach { callIndex ->
            val owner = blocks.firstOrNull { callIndex in it.bodyStart..it.bodyEnd }
            if (owner == null) {
                findings += "「$consumerAnchor」下标 $callIndex 不在任何 LaunchedEffect 的 lambda 体内" +
                    "（lambda 体之外不得出现消费调用点）"
                return@forEach
            }
            val enclosing = enclosingOpenBrace(functionBody, callIndex)
            if (enclosing != owner.bodyOpen) {
                findings += "「$consumerAnchor」下标 $callIndex 最近的未闭合开括号是 $enclosing，而该 LaunchedEffect 的 " +
                    "lambda 体开括号是 ${owner.bodyOpen}：消费点必须**直接**位于 LaunchedEffect 的 lambda 体内，" +
                    "不得嵌套在体内更深一层的 lambda（普通 lambda 或 suspend lambda）中——那种写法永不执行，" +
                    "用户点击按钮不会有任何反应"
            }
        }
        return findings
    }

    /**
     * 返回 [index] 位置**最近的未被闭合的开括号**下标（-1 表示没有任何未闭合开括号）。
     *
     * 实现方式是**词法扫描**而非文本包含性检查：从头扫到 [index]，跳过字符串字面量（含转义与 `"""` 原
     * 始字符串）、字符字面量、行注释与块注释，用栈记录开括号位置。这样才不会被「消费调用确实在某个
     * `{ ... }` 里、但那层 `{` 是一个永不执行的嵌套 lambda」骗过。
     */
    private fun enclosingOpenBrace(source: String, index: Int): Int {
        val openBraces = ArrayDeque<Int>()
        var i = 0
        while (i < index) {
            when (source[i]) {
                '"' -> i = closingQuoteOrRaw(source, i)
                '\'' -> i = closingCharQuote(source, i)
                '/' -> {
                    if (i + 1 < source.length && source[i + 1] == '/') {
                        var j = i + 2
                        while (j < index && source[j] != '\n') j++
                        i = j
                    } else if (i + 1 < source.length && source[i + 1] == '*') {
                        val end = source.indexOf("*/", i + 2)
                        i = if (end < 0 || end >= index) index else end + 1
                    }
                }

                '{' -> openBraces.addLast(i)
                '}' -> if (openBraces.isNotEmpty()) openBraces.removeLast()
            }
            i++
        }
        return openBraces.lastOrNull() ?: -1
    }

    /** 跳过字符串字面量（含 `"""` 原始字符串与转义），返回收尾引号下标 */
    private fun closingQuoteOrRaw(source: String, openQuote: Int): Int {
        if (source.startsWith("\"\"\"", openQuote)) {
            val end = source.indexOf("\"\"\"", openQuote + 3)
            return if (end < 0) source.length - 1 else end + 2
        }
        return closingQuote(source, openQuote)
    }

    /** 跳过字符字面量（如 `'{'`），返回收尾单引号下标 */
    private fun closingCharQuote(source: String, openQuote: Int): Int {
        var index = openQuote + 1
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index++
                '\'' -> return index
            }
            index++
        }
        return source.length - 1
    }

    // ---- 变异自检（在内存中改写原始源码；与隔离副本里的真实脚本同形） ----

    private data class Mutation(val id: String, val description: String)

    /**
     * 对原始源码施加一个接线变异，返回变异后的源码（仅在内存中，不落盘）。
     *
     * 四种变异的文本形态与 `.lizhu_env/stats_r10/run_mutations_r10.ps1` 完全一致，因此「内存自检通过」
     * 与「隔离副本真跑变红」是同一件事的两种执行方式。
     */
    private fun mutatedSource(pristine: String, mutationId: String): String {
        val start = pristine.indexOf(EFFECT_ANCHOR)
        assertTrue("原始码中未找到接线锚点「$EFFECT_ANCHOR」", start >= 0)
        val bodyOpen = pristine.indexOf('{', start)
        val bodyEnd = matchingDelimiter(pristine, bodyOpen, '{', '}')
        val inner = pristine.substring(bodyOpen + 1, bodyEnd)

        return when (mutationId) {
            // M-1：调用仍在 LaunchedEffect 的「文本范围内」，但被包进一个从未被调用的局部 suspend lambda
            MUTATION_NESTED_LAMBDA -> pristine.replaceRange(
                start,
                bodyEnd + 1,
                EFFECT_ANCHOR + NEWLINE +
                    "        val neverRuns: suspend () -> Unit = {" +
                    inner +
                    NEWLINE + "        }" + NEWLINE + "    }",
            )

            // M-2：整块 LaunchedEffect(viewModel){...} 删除（连同其所在行）
            MUTATION_REMOVED_EFFECT -> {
                val removeStart = pristine.lastIndexOf('\n', start) + 1
                val lineEnd = pristine.indexOf('\n', bodyEnd)
                val removeEnd = if (lineEnd < 0) bodyEnd + 1 else lineEnd + 1
                pristine.removeRange(removeStart, removeEnd)
            }

            // M-3：调用搬到 LaunchedEffect 之外（同文件内 rememberCoroutineScope().launch，仍可编译）
            MUTATION_MOVED_OUTSIDE -> pristine.replaceRange(
                start,
                bodyEnd + 1,
                EFFECT_ANCHOR + NEWLINE + "    }" + NEWLINE +
                    "    rememberCoroutineScope().launch {" +
                    inner +
                    NEWLINE + "    }",
            )

            // M-4：key 由 viewModel 改回 Unit
            MUTATION_KEY_UNIT -> pristine.replaceRange(start, start + EFFECT_ANCHOR.length, "LaunchedEffect(Unit) {")

            else -> throw AssertionError("未知变异：$mutationId")
        }
    }

    /** 源码里的一个 `LaunchedEffect(<key>) { <body> }` 接线块 */
    private data class EffectBlock(
        /** 形参文本（如 `viewModel`） */
        val key: String,
        /** 形参右括号下标（次序断言用：调用点必须在其后） */
        val paramsEnd: Int,
        /** lambda 体文本（不含花括号） */
        val body: String,
        /** lambda 体**开括号**下标（可达性断言用：消费点最近的未闭合开括号必须等于它） */
        val bodyOpen: Int,
        /** lambda 体首字符下标（含） */
        val bodyStart: Int,
        /** lambda 体末字符下标（含） */
        val bodyEnd: Int,
    )

    private companion object {

        /** 历史页源码（相对 app/ 的路径） */
        const val HISTORY_SCREEN_SOURCE = "src/main/java/com/assignmate/app/stats/ui/HistoryScreen.kt"

        /** 当日盘点页源码（相对 app/ 的路径；与历史页属同型接线，同口径看护） */
        const val DAY_SUMMARY_SCREEN_SOURCE = "src/main/java/com/assignmate/app/stats/ui/DaySummaryScreen.kt"

        /** 事件消费的启动点写法（含左括号，便于配平形参） */
        const val LAUNCHED_EFFECT = "LaunchedEffect("

        /** 历史页事件消费点的调用写法（含左括号，避免匹配到函数声明以外的同名前缀） */
        const val CONSUME_CALL = "consumeHistoryEvents("

        /** 当日盘点页事件消费点的锚点（该页直接内联 `collect`，不抽具名消费函数） */
        const val DAY_SUMMARY_CONSUME_ANCHOR = "viewModel.events.collect"

        /** 事件消费协程必须使用的 `LaunchedEffect` key（宿主替换实例时旧协程随之取消） */
        const val EXPECTED_KEY = "viewModel"

        /** 接线块锚点（含尾随 lambda 左花括号，便于整体替换与配平） */
        const val EFFECT_ANCHOR = "LaunchedEffect(viewModel) {"

        const val NEWLINE = "\n"

        const val MUTATION_NESTED_LAMBDA = "M-1"

        const val MUTATION_REMOVED_EFFECT = "M-2"

        const val MUTATION_MOVED_OUTSIDE = "M-3"

        const val MUTATION_KEY_UNIT = "M-4"

        /** 变异矩阵（与 `.lizhu_env/stats_r10/run_mutations_r10.ps1` 同形） */
        val MUTATIONS = listOf(
            Mutation(
                id = MUTATION_NESTED_LAMBDA,
                description = "M-1 把消费调用搬进「声明在 LaunchedEffect(viewModel) 体内、从未被调用」的局部 suspend lambda",
            ),
            Mutation(id = MUTATION_REMOVED_EFFECT, description = "M-2 删除整块 LaunchedEffect(viewModel)"),
            Mutation(
                id = MUTATION_MOVED_OUTSIDE,
                description = "M-3 把消费调用搬到 LaunchedEffect 之外（同文件内 rememberCoroutineScope().launch）",
            ),
            Mutation(id = MUTATION_KEY_UNIT, description = "M-4 LaunchedEffect 的 key 由 viewModel 改回 Unit"),
        )
    }
}
