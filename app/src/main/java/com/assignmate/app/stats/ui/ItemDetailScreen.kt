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
 * 单项详情页：**指定某一天**的预估时长 / 实际时长 / 暂停时长 + 当天状态 + 困难度侧面评估提示。
 *
 * 口径：时长与暂停都取自「这一天的作业每天详情」，阶段作业的每一天分别查看（不再给整段累计），
 * 故卡片标题与说明文案均按所选日期输出（[ItemDetailUiState.timeCardTitle] / [ItemDetailUiState.noteText]）。
 * 当天没有执行痕迹（[ItemDetailUiState.hasExecution] 为 false）时展示「尚未开始 / 暂无数据」，
 * 而非困难度结论——空数据不应被误读为「很轻松」。
 *
 * 导航契约（framework 接线）：页面路由 `stats/item/{studentId}/{homeworkId}`（不带日期），
 * [epochDay] 为**页面入参**且缺省 [StatsDestination.ARG_EPOCH_DAY_TODAY]（「今天」）——
 * 让「历史日盘点里点开的详情」落在该历史日需要 framework 在路由上补查询参数并透传（framework 计划范围）；
 * [onBack] 返回上一页（当日盘点）。
 */
@Composable
fun ItemDetailRoute(
    studentId: Long,
    homeworkId: Long,
    onBack: () -> Unit,
    epochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    modifier: Modifier = Modifier,
    viewModel: ItemDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(studentId, homeworkId, epochDay) { viewModel.start(studentId, homeworkId, epochDay) }
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
            // 加载态文案随查看日期收敛（历史日显示具体日期，与卡片标题同源）
            ItemDetailPhase.LOADING -> CoreLoadingPlaceholder(text = uiState.loadingText)
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
    StatsCard(title = uiState.timeCardTitle) {
        StatsMetricRow(label = "预估时长", value = uiState.estimatedText)
        Spacer(modifier = Modifier.height(6.dp))
        StatsMetricRow(label = "实际时长", value = uiState.elapsedText)
        Spacer(modifier = Modifier.height(6.dp))
        StatsMetricRow(label = "暂停时长", value = uiState.pausedText)
        Spacer(modifier = Modifier.height(6.dp))
        StatsMetricRow(label = "当天状态", value = uiState.statusText)
        Spacer(modifier = Modifier.height(8.dp))
        // 阶段打卡进度以说明行的形式给出（与历史条目同一文案口径），避免与指标标签重复措辞
        uiState.stageProgressText?.let { progress ->
            StatsHintText(text = progress)
            Spacer(modifier = Modifier.height(4.dp))
        }
        StatsHintText(text = uiState.noteText)
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
