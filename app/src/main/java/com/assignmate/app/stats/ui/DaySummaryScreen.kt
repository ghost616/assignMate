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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreErrorPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder
import com.assignmate.app.stats.domain.DaySummary

/**
 * 当日盘点页：完成率（进度 + 百分比）、暂停次数、暂停总时长、暂停最久作业。
 *
 * 导航契约（framework 接线，本页不直接改 NavHost）：
 * - 路由 `stats/day/{studentId}?epochDay={epochDay}`；[epochDay] 为可选日期
 *   （[StatsDestination.ARG_EPOCH_DAY_TODAY] 表示「今天」），历史查询页「查看这一天的盘点」
 *   据此传入所选日期，本页展示该日的盘点；
 * - [onOpenItemDetail] → stats 单项详情页 `stats/item/{studentId}/{homeworkId}`；
 * - [onOpenHistory] → stats 历史查询页 `stats/history/{studentId}`；
 * - [onBack] → 上一页（清单/首页）。
 *
 * 状态渲染：加载态 → [CoreLoadingPlaceholder]；无会话/未选学生 → [CoreErrorPlaceholder] /
 * [CoreEmptyPlaceholder]；空数据 → [CoreEmptyPlaceholder]；失败 → [CoreErrorPlaceholder]（可重试）。
 *
 * 日期口径：本页可按 [epochDay] 展示任意历史日，故标题、加载态、清单卡标题与空态文案一律走
 * [DaySummaryUiState] 的日期口径投影（`titleText` / `loadingText` / `itemListTitle` / `emptyText`），
 * 页面内不再自行判断「今天」，避免同一屏出现「今天」与具体日期两套措辞。
 */
@Composable
fun DaySummaryRoute(
    studentId: Long,
    epochDay: Long,
    onBack: () -> Unit,
    onOpenItemDetail: (studentId: Long, homeworkId: Long) -> Unit,
    onOpenHistory: (studentId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DaySummaryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(studentId, epochDay) { viewModel.start(studentId, epochDay) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DaySummaryEvent.OpenItemDetail -> uiState.studentId?.let { resolved ->
                    onOpenItemDetail(resolved, event.homeworkId)
                }

                is DaySummaryEvent.OpenHistory -> onOpenHistory(event.studentId)
            }
        }
    }
    DaySummaryContent(
        uiState = uiState,
        onBack = onBack,
        onRetry = viewModel::reload,
        onOpenItemDetail = viewModel::openItemDetail,
        onOpenHistory = viewModel::openHistory,
        modifier = modifier.fillMaxSize(),
    )
}

/** 当日盘点页内容（无状态，便于预览与测试） */
@Composable
fun DaySummaryContent(
    uiState: DaySummaryUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenItemDetail: (homeworkId: Long) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        when (uiState.phase) {
            // 加载态文案随盘点日期收敛（历史日显示具体日期，与页面标题同源）
            DaySummaryPhase.LOADING -> CoreLoadingPlaceholder(text = uiState.loadingText)
            DaySummaryPhase.NO_SESSION -> CoreErrorPlaceholder(
                message = StatsErrorMessages.NO_ACTIVE_SESSION,
                onRetry = onBack,
                retryText = "返回",
            )

            DaySummaryPhase.NO_STUDENT -> CoreEmptyPlaceholder(
                text = StatsErrorMessages.NO_STUDENT_SELECTED,
            )

            DaySummaryPhase.ERROR -> CoreErrorPlaceholder(
                message = uiState.errorMessage ?: StatsErrorMessages.READ_FAILED,
                onRetry = onRetry,
            )

            DaySummaryPhase.READY -> DaySummaryBody(
                uiState = uiState,
                onOpenItemDetail = onOpenItemDetail,
                onOpenHistory = onOpenHistory,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DaySummaryBody(
    uiState: DaySummaryUiState,
    onOpenItemDetail: (homeworkId: Long) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val summary = uiState.summary
    Text(
        text = buildString {
            // 标题文案由状态按盘点日期投影（今天 / 具体日期），与加载态、清单卡标题同源
            append(uiState.titleText)
            uiState.studentName?.let { append("（$it）") }
        },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(16.dp))
    if (uiState.isEmpty || summary == null) {
        CoreEmptyPlaceholder(text = uiState.emptyText)
        Spacer(modifier = Modifier.height(16.dp))
    } else {
        CompletionCard(uiState = uiState)
        Spacer(modifier = Modifier.height(12.dp))
        PauseCard(uiState = uiState)
        Spacer(modifier = Modifier.height(12.dp))
        ItemListCard(
            summary = summary,
            title = uiState.itemListTitle,
            onOpenItemDetail = onOpenItemDetail,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
    AssignMateBigButton(text = "查看历史完成情况", onClick = onOpenHistory)
    Spacer(modifier = Modifier.height(8.dp))
}

/** 完成率卡片：进度条 + 百分比 + 分子分母 */
@Composable
private fun CompletionCard(uiState: DaySummaryUiState) {
    StatsCard(title = "完成率") {
        Text(
            text = uiState.percentText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        StatsProgressBar(progress = uiState.progress)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = uiState.progressText,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** 暂停情况卡片：次数、总时长、暂停最久作业 */
@Composable
private fun PauseCard(uiState: DaySummaryUiState) {
    StatsCard(title = "暂停情况") {
        StatsMetricRow(label = "暂停次数", value = uiState.pauseCountText)
        Spacer(modifier = Modifier.height(6.dp))
        StatsMetricRow(label = "暂停总时长", value = uiState.pausedTotalText)
        uiState.mostPausedText?.let { text ->
            Spacer(modifier = Modifier.height(6.dp))
            StatsHintText(text = "暂停最久：$text")
        }
    }
}

/**
 * 当日作业清单卡片：每项可进入单项详情。
 *
 * [title] 由状态按盘点日期投影（今天 / 这一天，含项数），避免历史日视图与上方标题口径矛盾。
 * 空清单不会走到这里（[DaySummaryBody] 已先行展示空态），故循环内不做空判，
 * 避免出现「两个空态提示」的重复展示。
 */
@Composable
private fun ItemListCard(
    summary: DaySummary,
    title: String,
    onOpenItemDetail: (homeworkId: Long) -> Unit,
) {
    StatsCard(title = title) {
        summary.items.forEachIndexed { index, item ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(12.dp))
            }
            Text(
                text = "${item.content}（${item.status.label}）",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(6.dp))
            AssignMateBigButton(
                text = "查看这一项详情",
                onClick = { onOpenItemDetail(item.id) },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        StatsHintText(text = "点上面的按钮可以看这一项的预估/实际/暂停时长")
    }
}