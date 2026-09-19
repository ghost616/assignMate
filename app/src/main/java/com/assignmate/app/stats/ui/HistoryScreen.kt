package com.assignmate.app.stats.ui


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreErrorPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 历史查询页：按日期（或日期范围）查看历史完成情况（学生/家长视角均可查看名下学生）。
 *
 * 展示口径：逐日盘点按日期倒序（最近的一天在前），每行给出「已完成 x / y 项（百分比）」与
 * 「暂停 n 次 · 时长」，当天涉及阶段作业时再给出整段「阶段打卡进度」，点击可进入该日盘点页；
 * 范围合计展示在顶部。
 * 日期范围由 [onPickRange] 交回上层（framework 接日期选择器，本模块不引入额外依赖）。
 *
 * 导航契约（framework 接线）：
 * - [onBack] 返回；
 * - [onOpenDaySummary] 进入**所选日期**的当日盘点页：由本页消费 [HistoryViewModel.events]
 *   （见 [consumeHistoryEvents]）后透传「已解析的 studentId + 事件里的 epochDay」，
 *   因此历史条目卡的「查看这一天的盘点」在真机路径上可达；事件到达时学生仍未解析的情况
 *   由消费点的丢弃分支转成**用户可见提示**（`SnackbarHost`），不让点击落进无声死路；
 * - [onPickRange] 选择日期范围（选择器实现由 framework 提供，本模块不引入额外依赖）。
 * 本期不提供历史条目直达单项详情的入口，故本页只抛「进入某日盘点」一种导航意图。
 *
 * 事件消费协程的 key 为 `viewModel`（而非 `Unit`）：宿主替换 ViewModel 实例时旧协程随之取消，
 * 不会残留在废弃实例的 Channel 上（与 settings/homework/timer 页面的事件消费写法一致）。
 */
@Composable
fun HistoryRoute(
    studentId: Long,
    fromEpochDay: Long,
    toEpochDay: Long,
    onBack: () -> Unit,
    onOpenDaySummary: (studentId: Long, epochDay: Long) -> Unit,
    onPickRange: (studentId: Long, fromEpochDay: Long, toEpochDay: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(studentId, fromEpochDay, toEpochDay) {
        viewModel.start(studentId, fromEpochDay, toEpochDay)
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val noticeScope = rememberCoroutineScope()
    // 事件消费的 key 是 viewModel 而非 Unit：宿主替换 ViewModel 实例（进程恢复 / 依赖变更）时，
    // 旧协程必须随之取消——否则它会一直挂在**废弃实例**的 Channel 上，新实例的事件无人消费。
    LaunchedEffect(viewModel) {
        consumeHistoryEvents(
            events = viewModel.events,
            // 传 StateFlow 本体：读取时机归消费点所有，Route 侧不存在可提前求值的表达式
            uiState = viewModel.uiState,
            onOpenDaySummary = onOpenDaySummary,
            // 兜底：事件到达时仍未解析出学生（会话失效 / 尚未选定）时，原实现是静默丢弃 ——
            // 用户点了「查看这一天的盘点」既不导航也没有任何提示（可感知的死路）。
            // 这里用一次性 Snackbar 提示；提示协程与消费协程分离，避免提示阻塞后续事件消费。
            onEventDropped = { epochDay ->
                noticeScope.launch {
                    snackbarHostState.showSnackbar(
                        message = StatsErrorMessages.daySummaryDroppedText(epochDay),
                        duration = SnackbarDuration.Long,
                    )
                }
            },
        )
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        HistoryContent(
            uiState = uiState,
            onBack = onBack,
            onRetry = viewModel::reload,
            onSelectDay = viewModel::selectDay,
            onPickRange = {
                uiState.studentId?.let { resolved ->
                    onPickRange(resolved, uiState.query.startEpochDay, uiState.query.endEpochDay)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * 历史页一次性事件 -> 导航意图的**唯一消费点**（供 [HistoryRoute] 与单测共用）。
 *
 * 为什么抽成具名函数：之前 [HistoryRoute] 从未消费 [HistoryViewModel.events]，
 * 导致历史条目卡的「查看这一天的盘点」点击无反应、`AssignMateNavHost` 接好的 `onOpenDaySummary`
 * 成为死参数（且未消费的事件会在 Channel 内堆积）。
 *
 * 为什么第二个入参是 [StateFlow] 而不是 `() -> Long?`：状态必须在**事件到达时**读取。
 * 传 lambda 时调用方可以在调用处提前求值（`val snapshot = …; studentId = { snapshot }`），
 * 而这类退化在 JVM 单测里无法观测——本仓库无 Robolectric、无 `androidTest` 源集，
 * [HistoryRoute] 的组合体无法执行，单测只能直接调用本函数（离朱 R6 用「把生产 lambda 换成
 * `throw` 后用例仍全绿」的编译产物实验证实了这一点）。改为传状态流本体后，读取时机由本函数独占，
 * 「调用方提前快照」在类型上不可表达，并有行为用例锁定「按事件到达时的最新状态读取」。
 *
 * @param events ViewModel 的一次性事件流（Channel 转出的 Flow）
 * @param uiState 页面状态流；**每个事件到达时**读取其当前值，未解析出学生时不发起导航
 * @param onOpenDaySummary 进入某日盘点页的导航意图；studentId 未解析时不调用，避免误导航
 * @param onEventDropped 事件因**未解析出学生**而无法导航时的兜底出口（入参为目标日期）。
 *   没有它就只能静默丢弃——用户点了「查看这一天的盘点」既不跳转也无任何提示，
 *   属用户可感知的死路；接线方（[HistoryRoute]）据此给出一次性提示。
 */
internal suspend fun consumeHistoryEvents(
    events: Flow<HistoryEvent>,
    uiState: StateFlow<HistoryUiState>,
    onOpenDaySummary: (studentId: Long, epochDay: Long) -> Unit,
    onEventDropped: (epochDay: Long) -> Unit,
) {
    events.collect { event ->
        when (event) {
            is HistoryEvent.OpenDaySummary ->
                uiState.value.studentId?.let { resolved -> onOpenDaySummary(resolved, event.epochDay) }
                    ?: onEventDropped(event.epochDay)
        }
    }
}

/** 历史查询页内容（无状态，便于预览与测试） */
@Composable
fun HistoryContent(
    uiState: HistoryUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectDay: (epochDay: Long) -> Unit,
    onPickRange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        when (uiState.phase) {
            HistoryPhase.LOADING -> CoreLoadingPlaceholder(text = "正在读取历史记录…")
            HistoryPhase.NO_SESSION -> CoreErrorPlaceholder(
                message = StatsErrorMessages.NO_ACTIVE_SESSION,
                onRetry = onBack,
                retryText = "返回",
            )

            HistoryPhase.NO_STUDENT -> CoreEmptyPlaceholder(
                text = StatsErrorMessages.NO_STUDENT_SELECTED,
            )

            HistoryPhase.ERROR -> CoreErrorPlaceholder(
                message = uiState.errorMessage ?: StatsErrorMessages.READ_FAILED,
                onRetry = onRetry,
            )

            HistoryPhase.READY -> HistoryBody(
                uiState = uiState,
                onSelectDay = onSelectDay,
                onPickRange = onPickRange,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        AssignMateBigButton(text = "返回", onClick = onBack)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun HistoryBody(
    uiState: HistoryUiState,
    onSelectDay: (epochDay: Long) -> Unit,
    onPickRange: () -> Unit,
) {
    Text(
        text = buildString {
            append("历史完成情况")
            uiState.studentName?.let { append("（$it）") }
        },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    StatsHintText(text = "查询范围：${uiState.rangeText}")
    uiState.rangeError?.let { error ->
        Spacer(modifier = Modifier.height(4.dp))
        StatsHintText(text = error)
    }
    uiState.totalText?.let { total ->
        Spacer(modifier = Modifier.height(12.dp))
        StatsCard(title = "范围合计") {
            StatsHintText(text = total)
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    AssignMateBigButton(text = "选择日期范围", onClick = onPickRange)
    Spacer(modifier = Modifier.height(12.dp))
    if (uiState.isEmpty) {
        CoreEmptyPlaceholder(text = StatsErrorMessages.NO_HISTORY)
    } else {
        // 逐日条目直接铺在页面滚动容器内（不做嵌套懒加载）：
        // 外层已是 verticalScroll，再嵌 LazyColumn 会因高度无界而崩溃；历史条目上限受
        // StatsConstants.MAX_HISTORY_DAYS 约束，直接渲染成本可控。
        uiState.rows.forEachIndexed { index, row ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(12.dp))
            }
            HistoryRowCard(row = row, onSelectDay = onSelectDay)
        }
    }
}

/** 历史条目卡片：日期 + 完成情况 + 暂停情况 + 阶段打卡进度（如有）+ 进入该日盘点 */
@Composable
private fun HistoryRowCard(
    row: HistoryRow,
    onSelectDay: (epochDay: Long) -> Unit,
) {
    StatsCard(title = row.dateText) {
        StatsHintText(text = row.progressText)
        Spacer(modifier = Modifier.height(4.dp))
        StatsHintText(text = row.pauseText)
        row.stageText?.let { stage ->
            Spacer(modifier = Modifier.height(4.dp))
            StatsHintText(text = stage)
        }
        Spacer(modifier = Modifier.height(8.dp))
        AssignMateBigButton(
            text = "查看这一天的盘点",
            onClick = { onSelectDay(row.epochDay) },
        )
    }
}
