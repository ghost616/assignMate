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
import com.assignmate.app.homework.domain.HomeworkItem
import java.time.ZoneId

/**
 * 时间设定 / 编辑页：填写开始日期与时刻、预估时长，提交后展示 deadline 超限与时段冲突提示。
 * 提交前本地校验 deadline 约束，时段冲突由仓库查库兜底（返回可读原因）。
 *
 * 不可编辑的两种情形均**就地渲染**（不只依赖一次性 Snackbar）：
 * - 无执行权（学生排定他人名下作业）→ 展示作业概要 + EXECUTE_PERMISSION_DENIED_HINT；
 * - 进行中锁定（作业已开始）→ 展示作业概要 + WORK_IN_PROGRESS_LOCKED_HINT 并隐藏表单。
 * 两种情况都在读作业后保留 item，避免深链进入时渲染成残缺空表单。
 *
 * [onHomeworkScheduleSaved] 为排定成功后的收尾通知（默认空实现），由 framework 接到
 * 「按新时刻同步该作业的到点提醒」；本模块不依赖 timer。
 *
 * 排定成功后的时序：外抛收尾通知 → **立即**执行 [onSaved]（回清单）→ 非挂起地把提示文案写入
 * [homeworkSaveNotice] 由清单页展示；本页不再 await 提示条（详见 [dispatchSaveCompletion]）。
 */
@Composable
fun HomeworkTimeSetRoute(
    studentId: Long,
    homeworkId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    /**
     * 「时间已保存」的收尾通知（仅排定成功后调用一次）：由 framework 接到
     * 「按新时刻同步该作业的到点提醒」，避免旧时刻闹钟仍生效、新时刻无闹钟。
     *
     * 默认空实现：未接线时保存流程与既有行为完全一致（homework 不依赖 timer）。
     */
    onHomeworkScheduleSaved: (homeworkId: Long) -> Unit = {},
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
                    // 排定成功：先外抛收尾通知（framework 按新时刻同步到点提醒），再**立即**回清单。
                    // 提示文案不再在此 await（修复前 Long 约 10 秒的提示会挡住返回，且提示期间离开组合
                    // 会使 onSaved 连同提醒同步被取消），改为经跨页一次性暂存由清单页展示
                    onHomeworkScheduleSaved(event.homeworkId)
                    dispatchSaveCompletion(
                        dispatchMessage = event.message,
                        saveNotice = homeworkSaveNotice,
                        onSaved = onSaved,
                    )
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
            // 业务时区来自 ViewModel 注入的 core 唯一绑定：作业概要的截止时刻展示与
            // 仓库/「今天」口径同源（覆写该绑定即全局生效，页面不再以 systemDefault 兜底）
            zoneId = viewModel.zoneId,
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

/**
 * 时间设定页内容（无状态）。
 *
 * @param zoneId 业务时区：由 [HomeworkTimeSetRoute] 透传 ViewModel 注入的 core 唯一绑定，
 *   **刻意不带默认值**（默认 `systemDefault()` 会让覆写绑定后的展示口径漂移）
 */
@Composable
fun HomeworkTimeSetContent(
    uiState: HomeworkTimeSetUiState,
    callbacks: HomeworkTimeSetCallbacks,
    zoneId: ZoneId,
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
            text = "设定作业时间",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在读取作业…")
            uiState.missingSession -> CoreEmptyPlaceholder(text = "无法编辑该作业（会话失效或作业不存在）")
            // 无执行权：保留作业概要 + 就地提示，避免渲染成残缺空表单
            uiState.permissionDenied -> {
                HomeworkSummaryCard(item = uiState.item, zoneId = zoneId)
                Spacer(modifier = Modifier.height(12.dp))
                CoreEmptyPlaceholder(text = EXECUTE_PERMISSION_DENIED_HINT)
                Spacer(modifier = Modifier.height(24.dp))
            }
            // 进行中锁定：就地提示（不只依赖一次性 Snackbar），隐藏表单
            uiState.lockedWorkInProgress -> {
                HomeworkSummaryCard(item = uiState.item, zoneId = zoneId)
                Spacer(modifier = Modifier.height(12.dp))
                CoreEmptyPlaceholder(text = WORK_IN_PROGRESS_LOCKED_HINT)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "作业已开始计时，如需重新安排时间请先在作业清单中完成或纠正状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            else -> {
                HomeworkSummaryCard(item = uiState.item, zoneId = zoneId)
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

/**
 * 作业概要卡片（内容 + 类型与阶段 + 截止时间）。
 *
 * 抽为独立 composable 供「正常表单」「无执行权」「进行中锁定」三个分支复用：
 * 锁定/无权限分支同样展示概要，避免深链进入时渲染成「（待补充内容）/ 类型：-」的残缺空表单。
 */
@Composable
private fun HomeworkSummaryCard(item: HomeworkItem?, zoneId: ZoneId) {
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
                    // 阶段作业只展示「每天 HH:mm 截止」；当天作业展示「日期 + 时刻」
                    text = item.deadlineLabel { millis -> TimeFormatters.formatDateTime(millis, zoneId) }
                        ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}