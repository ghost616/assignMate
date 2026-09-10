package com.assignmate.app.homework.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.time.LocalDate

/**
 * 手动录入 / 编辑作业模板页：输入内容 → 选类型（当天/阶段）→ 阶段作业选范围与截止时间 →
 * 保存后进入清单。提交时展示表单级错误提示（content/stage/deadline/form）。
 */
@Composable
fun HomeworkTemplateRoute(
    studentId: Long,
    homeworkId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeworkTemplateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel, studentId, homeworkId) {
        viewModel.start(studentId = studentId, homeworkId = homeworkId)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                HomeworkTemplateEvent.Saved -> onSaved()
                is HomeworkTemplateEvent.SavedWithMessage -> {
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Long)
                    onSaved()
                }

                is HomeworkTemplateEvent.ShowMessage -> snackbarHostState.showSnackbar(
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
        HomeworkTemplateContent(
            uiState = uiState,
            callbacks = HomeworkTemplateCallbacks(
                onBack = onBack,
                onContentChange = viewModel::onContentChange,
                onTypeChange = viewModel::onTypeChange,
                onStageRangeChange = viewModel::onStageRangeChange,
                onDeadlineDateChange = viewModel::onDeadlineDateChange,
                onDeadlineTimeChange = viewModel::onDeadlineTimeChange,
                onSubmit = viewModel::onSubmit,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/** 录入模板页回调集合 */
class HomeworkTemplateCallbacks(
    val onBack: () -> Unit,
    val onContentChange: (String) -> Unit,
    val onTypeChange: (HomeworkType) -> Unit,
    val onStageRangeChange: (StageRange) -> Unit,
    val onDeadlineDateChange: (String) -> Unit,
    val onDeadlineTimeChange: (String) -> Unit,
    val onSubmit: () -> Unit,
)

/** 录入模板页内容（无状态） */
@Composable
fun HomeworkTemplateContent(
    uiState: HomeworkTemplateUiState,
    callbacks: HomeworkTemplateCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = callbacks.onBack) { Text(text = "返回") }
        Text(
            text = if (uiState.editing) "编辑作业" else "手动录入作业",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在准备表单…")
            uiState.missingSession -> CoreEmptyPlaceholder(text = "会话已失效或作业不存在，请返回重试")
            else -> TemplateForm(uiState = uiState, callbacks = callbacks)
        }
    }
}

@Composable
private fun TemplateForm(
    uiState: HomeworkTemplateUiState,
    callbacks: HomeworkTemplateCallbacks,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = uiState.content,
                onValueChange = callbacks.onContentChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("作业内容") },
                placeholder = { Text("例如：语文第 3 课生字各写两遍") },
                minLines = 3,
                isError = uiState.contentError != null,
                supportingText = {
                    Text(
                        text = uiState.contentError
                            ?: "${uiState.content.length}/${HomeworkConstants.MAX_CONTENT_LENGTH}",
                    )
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "作业类型", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeworkType.entries.forEach { type ->
                    FilterChip(
                        selected = uiState.type == type,
                        onClick = { callbacks.onTypeChange(type) },
                        label = { Text(text = type.label) },
                    )
                }
            }
            if (uiState.type == HomeworkType.STAGE) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "阶段范围", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StageRange.entries.forEach { range ->
                        FilterChip(
                            selected = uiState.stageRange == range,
                            onClick = { callbacks.onStageRangeChange(range) },
                            label = { Text(text = range.label) },
                        )
                    }
                }
                if (uiState.stageError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    ErrorText(uiState.stageError)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "覆盖：${LocalDate.ofEpochDay(uiState.todayEpochDay)} 至 " +
                        "${LocalDate.ofEpochDay(uiState.lastEpochDay)}（每天一条、可逐日完成）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.deadlineEditable) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "截止时间（家长设置）", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.deadlineDate,
                        onValueChange = callbacks.onDeadlineDateChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("日期") },
                        placeholder = { Text("2025-01-02") },
                        singleLine = true,
                        isError = uiState.deadlineError != null,
                    )
                    OutlinedTextField(
                        value = uiState.deadlineTime,
                        onValueChange = callbacks.onDeadlineTimeChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("时间") },
                        placeholder = { Text("21:00") },
                        singleLine = true,
                        isError = uiState.deadlineError != null,
                    )
                }
                if (uiState.deadlineError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    ErrorText(uiState.deadlineError)
                }
            }
            if (uiState.formError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ErrorText(uiState.formError)
            }
            Spacer(modifier = Modifier.height(16.dp))
            AssignMateBigButton(
                text = if (uiState.submitting) "保存中…" else "保存作业",
                onClick = callbacks.onSubmit,
                enabled = uiState.canSubmit,
            )
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}