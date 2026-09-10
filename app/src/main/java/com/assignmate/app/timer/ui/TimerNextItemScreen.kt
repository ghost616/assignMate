package com.assignmate.app.timer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreErrorPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder

/**
 * 下一项提示页：休息结束（或完成某项）后按清单顺序展示下一条待完成项与「现在开始」；
 * 清单已全部完成时展示完成提示并引导进入完成反馈页。
 *
 * 导航契约（framework 接线）：
 * - [onStartHomework] → 执行页 [TimerDestination.executionRoute]（本页已先行开始计时）；
 * - [onAllCompleted] → 完成反馈页 [TimerDestination.completionRoute]；
 * - [onBackToList] → homework 清单页。
 */
@Composable
fun TimerNextItemRoute(
    studentId: Long,
    onBackToList: (studentId: Long) -> Unit,
    onStartHomework: (studentId: Long, homeworkId: Long) -> Unit,
    onAllCompleted: (studentId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimerNextItemViewModel = hiltViewModel(),
) {
    LaunchedEffect(studentId) { viewModel.start(studentId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TimerNextItemEvent.ShowMessage ->
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Long)

                is TimerNextItemEvent.StartHomework ->
                    onStartHomework(event.studentId, event.homeworkId)
            }
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        TimerNextItemContent(
            uiState = uiState,
            onStartNext = viewModel::onStartNextClick,
            onAllCompleted = { uiState.studentId?.let(onAllCompleted) },
            onBackToList = { uiState.studentId?.let(onBackToList) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/** 下一项提示页内容（无状态，便于预览与测试） */
@Composable
fun TimerNextItemContent(
    uiState: TimerNextItemUiState,
    onStartNext: () -> Unit,
    onAllCompleted: () -> Unit,
    onBackToList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onBackToList) { Text(text = "回到作业清单") }
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在看下一项…")
            uiState.missingSession -> CoreErrorPlaceholder(message = TimerErrorMessages.NO_ACTIVE_SESSION)
            uiState.missingStudent -> CoreEmptyPlaceholder(text = "请先选择学生")
            else -> NextItemBody(
                uiState = uiState,
                onStartNext = onStartNext,
                onAllCompleted = onAllCompleted,
            )
        }
    }
}

@Composable
private fun NextItemBody(
    uiState: TimerNextItemUiState,
    onStartNext: () -> Unit,
    onAllCompleted: () -> Unit,
) {
    val next = uiState.nextItem
    Spacer(modifier = Modifier.height(20.dp))
    if (next == null) {
        Text(
            text = "今天的作业都完成啦！",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = uiState.progressText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        AssignMateBigButton(text = "看看今天的表现", onClick = onAllCompleted)
        return
    }
    Text(
        text = "下一项是",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = next.content,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = next.estimatedMinutes?.let { "预计 $it 分钟" } ?: "未设定预估时长",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = uiState.progressText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(28.dp))
    AssignMateBigButton(
        text = uiState.nextItemActionText,
        onClick = onStartNext,
        enabled = !uiState.busy,
    )
}
