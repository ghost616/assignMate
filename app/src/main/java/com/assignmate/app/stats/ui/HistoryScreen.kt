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
 * 历史查询页：按日期（或日期范围）查看历史完成情况（学生/家长视角均可查看名下学生）。
 *
 * 展示口径：逐日盘点按日期倒序（最近的一天在前），每行给出「已完成 x / y 项（百分比）」与
 * 「暂停 n 次 · 时长」，点击可进入该日盘点页；范围合计展示在顶部。
 * 日期范围由 [onPickRange] 交回上层（framework 接日期选择器，本模块不引入额外依赖）。
 *
 * 导航契约（framework 接线）：[onBack] 返回；[onOpenDaySummary] 进入当日盘点页（指定日期）。
 * 本期不提供历史条目直达单项详情的入口，故本页只抛「进入某日盘点」一种导航意图。
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
        modifier = modifier.fillMaxSize(),
    )
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

/** 历史条目卡片：日期 + 完成情况 + 暂停情况 + 进入该日盘点 */
@Composable
private fun HistoryRowCard(
    row: HistoryRow,
    onSelectDay: (epochDay: Long) -> Unit,
) {
    StatsCard(title = row.dateText) {
        StatsHintText(text = row.progressText)
        Spacer(modifier = Modifier.height(4.dp))
        StatsHintText(text = row.pauseText)
        Spacer(modifier = Modifier.height(8.dp))
        AssignMateBigButton(
            text = "查看这一天的盘点",
            onClick = { onSelectDay(row.epochDay) },
        )
    }
}