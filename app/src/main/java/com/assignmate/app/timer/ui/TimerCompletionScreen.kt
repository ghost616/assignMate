package com.assignmate.app.timer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
 * 完成反馈页：随机表扬语 + 完成情况概览（已完成/总数）。
 *
 * 导航契约（framework 接线）：[onBackToList] → homework 清单页；
 * 语音播报（把表扬语读出来）由 timer-B 计划经 core 的 TextToSpeechPlayer 接入，本页只负责展示。
 */
@Composable
fun TimerCompletionRoute(
    studentId: Long,
    onBackToList: (studentId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimerCompletionViewModel = hiltViewModel(),
) {
    LaunchedEffect(studentId) { viewModel.start(studentId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TimerCompletionContent(
        uiState = uiState,
        onBackToList = { uiState.studentId?.let(onBackToList) },
        modifier = modifier.fillMaxSize(),
    )
}

/** 完成反馈页内容（无状态，便于预览与测试） */
@Composable
fun TimerCompletionContent(
    uiState: TimerCompletionUiState,
    onBackToList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Spacer(modifier = Modifier.height(32.dp))
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在统计今天的表现…")
            uiState.missingSession -> CoreErrorPlaceholder(message = TimerErrorMessages.NO_ACTIVE_SESSION)
            uiState.missingStudent -> CoreEmptyPlaceholder(text = "请先选择学生")
            else -> CompletionBody(uiState = uiState, onBackToList = onBackToList)
        }
    }
}

@Composable
private fun CompletionBody(
    uiState: TimerCompletionUiState,
    onBackToList: () -> Unit,
) {
    Text(
        text = uiState.praiseText,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = uiState.progressText,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    uiState.remainingText?.let { text ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (uiState.allCompleted) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "清单里的作业全部完成，可以好好放松一下啦！",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(modifier = Modifier.height(32.dp))
    AssignMateBigButton(text = "回到作业清单", onClick = onBackToList)
}
