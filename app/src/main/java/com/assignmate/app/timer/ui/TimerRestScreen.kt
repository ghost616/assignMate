package com.assignmate.app.timer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.domain.util.TimeFormatters
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreErrorPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder

/**
 * 休息页：完成一项作业后的 10 分钟休息倒计时，结束（或「跳过休息」）后进入下一项提示页。
 *
 * 导航契约（framework 接线）：
 * - [onRestFinished] → [TimerDestination.nextItemRoute]（下一项提示）；
 * - [onBackToList] → homework 清单页（studentId 为 0 时由清单页按会话解析本人）。
 */
@Composable
fun TimerRestRoute(
    studentId: Long,
    homeworkId: Long,
    onRestFinished: (studentId: Long) -> Unit,
    onBackToList: (studentId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimerRestViewModel = hiltViewModel(),
) {
    LaunchedEffect(studentId, homeworkId) { viewModel.start(studentId, homeworkId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 离开页面时打断未播完的语音（评审补充：避免已离开还在念上一屏内容）
    DisposableEffect(viewModel) {
        onDispose { viewModel.onLeavingPage() }
    }
    TimerRestContent(
        uiState = uiState,
        onRestFinished = { uiState.studentId?.let(onRestFinished) },
        onBackToList = { uiState.studentId?.let(onBackToList) },
        modifier = modifier.fillMaxSize(),
    )
}

/** 休息页内容（无状态，便于预览与测试） */
@Composable
fun TimerRestContent(
    uiState: TimerRestUiState,
    onRestFinished: () -> Unit,
    onBackToList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRestFinished) { Text(text = "跳过休息") }
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在准备休息时间…")
            uiState.missingSession -> CoreErrorPlaceholder(message = TimerErrorMessages.NO_ACTIVE_SESSION)
            uiState.missingStudentHint -> CoreEmptyPlaceholder(text = "请先选择学生")
            else -> RestBody(
                uiState = uiState,
                onRestFinished = onRestFinished,
                onBackToList = onBackToList,
            )
        }
    }
}

@Composable
private fun RestBody(
    uiState: TimerRestUiState,
    onRestFinished: () -> Unit,
    onBackToList: () -> Unit,
) {
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = uiState.finishedHomeworkContent
            ?.let { "已完成：$it" }
            ?: "这一项完成啦",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = TimeFormatters.formatDuration(uiState.remainingSeconds),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = if (uiState.restFinished) {
            "休息结束啦，眼睛和身体都放松好了吗？"
        } else {
            "休息 ${uiState.totalMinutes} 分钟：起来动一动、喝口水吧"
        },
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = if (uiState.restFinished) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(32.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
        AssignMateBigButton(
            text = if (uiState.restFinished) "休息结束，看下一项" else "跳过休息，看下一项",
            onClick = onRestFinished,
        )
        Spacer(modifier = Modifier.height(12.dp))
        AssignMateBigButton(
            text = "回到作业清单",
            onClick = onBackToList,
            containerColor = MaterialTheme.colorScheme.secondary,
        )
    }
}
