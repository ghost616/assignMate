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

/**
 * 单项详情页：预估时长 / 实际时长 / 暂停时长 + 困难度侧面评估提示。
 *
 * 无执行记录（[ItemDetailUiState.hasExecution] 为 false）时展示「尚未开始 / 暂无数据」，
 * 而非困难度结论——空数据不应被误读为「很轻松」。
 *
 * 导航契约（framework 接线）：[onBack] 返回上一页（当日盘点/历史查询）。
 */
@Composable
fun ItemDetailRoute(
    studentId: Long,
    homeworkId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ItemDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(studentId, homeworkId) { viewModel.start(studentId, homeworkId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ItemDetailContent(
        uiState = uiState,
        onBack = onBack,
        onRetry = viewModel::reload,
        modifier = modifier.fillMaxSize(),
    )
}

/** 单项详情页内容（无状态，便于预览与测试） */
@Composable
fun ItemDetailContent(
    uiState: ItemDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        when (uiState.phase) {
            ItemDetailPhase.LOADING -> CoreLoadingPlaceholder(text = "正在读取这一项的用时…")
            ItemDetailPhase.NO_SESSION -> CoreErrorPlaceholder(
                message = StatsErrorMessages.NO_ACTIVE_SESSION,
                onRetry = onBack,
                retryText = "返回",
            )

            ItemDetailPhase.NO_STUDENT -> CoreEmptyPlaceholder(
                text = StatsErrorMessages.NO_STUDENT_SELECTED,
            )

            ItemDetailPhase.ERROR -> CoreErrorPlaceholder(
                message = uiState.errorMessage ?: StatsErrorMessages.READ_FAILED,
                onRetry = onRetry,
            )

            ItemDetailPhase.READY -> ItemDetailBody(uiState = uiState)
        }
        Spacer(modifier = Modifier.height(24.dp))
        AssignMateBigButton(text = "返回", onClick = onBack)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ItemDetailBody(uiState: ItemDetailUiState) {
    Text(
        text = uiState.contentText,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(16.dp))
    StatsCard(title = "用时情况（累计）") {
        StatsMetricRow(label = "预估时长", value = uiState.estimatedText)
        Spacer(modifier = Modifier.height(6.dp))
        StatsMetricRow(label = "实际时长（累计）", value = uiState.elapsedText)
        Spacer(modifier = Modifier.height(6.dp))
        StatsMetricRow(label = "暂停时长（累计）", value = uiState.pausedText)
        Spacer(modifier = Modifier.height(6.dp))
        StatsMetricRow(label = "执行次数", value = uiState.sessionCountText)
        Spacer(modifier = Modifier.height(8.dp))
        StatsHintText(text = ITEM_DETAIL_CUMULATIVE_NOTE)
    }
    Spacer(modifier = Modifier.height(12.dp))
    StatsCard(title = "困难度提示（仅供参考）") {
        if (uiState.hasExecution) {
            StatsMetricRow(label = "侧面评估", value = uiState.difficultyLabel)
            Spacer(modifier = Modifier.height(8.dp))
        }
        StatsHintText(text = uiState.assessmentHint)
    }
    Spacer(modifier = Modifier.height(12.dp))
}

/**
 * 累计口径说明文案：详情页的时长是「全部执行历史」合计，而当日盘点只统计当日窗口内的部分，
 * 跨天多次计时的作业两处数字会不同——显式标注避免家长误读为当日用时。
 */
private const val ITEM_DETAIL_CUMULATIVE_NOTE = "实际时长与暂停时长为累计值（含全部执行历史），与当日盘点口径可能不同"
