package com.assignmate.app.homework.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.domain.util.TimeFormatters
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder
import com.assignmate.app.homework.domain.HomeworkConstants
import java.time.ZoneId

/**
 * 时间设定 / 编辑页：填写开始日期与时刻、预估时长，提交后展示 deadline 超限与时段冲突提示。
 * 提交前本地校验 deadline 约束，时段冲突由仓库查库兜底（返回可读原因）。
 */
@Composable
fun HomeworkTimeSetRoute(
    studentId: Long,
    homeworkId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeworkTimeSetViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel, studentId, homeworkId) {
        viewModel.start(studentId = studentId, homeworkId = homeworkId)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TimeSetEvent.Saved -> {
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Long)
                    onSaved()
                }

                is TimeSetEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    message = event.message,
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        HomeworkTimeSetContent(
            uiState = uiState,
            callbacks = HomeworkTimeSetCallbacks(
                onBack = onBack,
                onStartDateChange = viewModel::onStartDateChange,
                onStartTimeChange = viewModel::onStartTimeChange,
                onMinutesChange = viewModel::onEstimatedMinutesChange,
                onSubmit = viewModel::onSubmit,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/** 时间设定页回调集合 */
class HomeworkTimeSetCallbacks(
    val onBack: () -> Unit,
    val onStartDateChange: (String) -> Unit,
    val onStartTimeChange: (String) -> Unit,
    val onMinutesChange: (String) -> Unit,
    val onSubmit: () -> Unit,
)

/** 时间设定页内容（无状态） */
@Composable
fun HomeworkTimeSetContent(
    uiState: HomeworkTimeSetUiState,
    callbacks: HomeworkTimeSetCallbacks,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = callbacks.onBack) { Text(text = "返回") }
        Text(
            text = "设定作业时间",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在读取作业…")
            uiState.missingSession -> CoreEmptyPlaceholder(text = "无法编辑该作业（会话失效或无权限）")
            else -> {
                val item = uiState.item
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = item?.content?.ifBlank { "（待补充内容）" } ?: "-",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "类型：${item?.typeLabel() ?: "-"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item?.deadline != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "截止：${
                                    TimeFormatters.formatDateTime(item.deadline.toEpochMilli(), zoneId)
                                }",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = uiState.startDate,
                            onValueChange = callbacks.onStartDateChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("开始日期（yyyy-MM-dd）") },
                            placeholder = { Text("2025-01-02") },
                            singleLine = true,
                            isError = uiState.timeError != null,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.startTime,
                            onValueChange = callbacks.onStartTimeChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("开始时间（HH:mm）") },
                            placeholder = { Text("16:00") },
                            singleLine = true,
                            isError = uiState.timeError != null,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.estimatedMinutes,
                            onValueChange = callbacks.onMinutesChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("预估时长（分钟）") },
                            placeholder = { Text("30") },
                            singleLine = true,
                            isError = uiState.minutesError != null,
                            supportingText = {
                                Text(
                                    text = uiState.minutesError
                                        ?: "允许 ${HomeworkConstants.MIN_ESTIMATED_MINUTES}-" +
                                        "${HomeworkConstants.MAX_ESTIMATED_MINUTES} 分钟",
                                )
                            },
                        )
                        if (uiState.timeError != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uiState.timeError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        AssignMateBigButton(
                            text = if (uiState.submitting) "保存中…" else "确认时间安排",
                            onClick = callbacks.onSubmit,
                            enabled = uiState.canSubmit,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}