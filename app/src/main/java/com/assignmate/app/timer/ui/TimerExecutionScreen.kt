package com.assignmate.app.timer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.domain.util.TimeFormatters
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreErrorPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder
import com.assignmate.app.timer.data.TimerPermissionKind
import com.assignmate.app.timer.domain.TimerCalculations
import com.assignmate.app.timer.domain.TimerPhase

/**
 * 作业执行页：展示当前作业内容与预估时长，走秒显示已用时长，
 * 提供「开始作业 / 有事走开（暂停）/ 我回来啦（恢复）/ 完成作业」四个操作。
 *
 * 界面锁定（需求：进行中不同时提供编辑作业的操作入口）：计时进行中（RUNNING/PAUSED）时
 * 页面不展示任何编辑作业入口，并以提示文案说明原因（判定见 [TimerExecutionUiState.homeworkEditingLocked]）。
 *
 * 导航：完成后经 [onCompleted] 回调进入休息页（由 framework 接线到
 * [TimerDestination.restRoute]）；本页不做页面跳转。
 *
 * timer-B：页面同时承载「语音提醒开关」与「通知/精确闹钟权限引导」——
 * 通知权限在 Android 13+ 走系统运行时弹窗，精确闹钟跳系统「闹钟和提醒」设置页，
 * 两者被拒绝时都只提示可手动开启，**不阻断计时**（计时依赖前台服务与库内会话）。
 */
@Composable
fun TimerExecutionRoute(
    studentId: Long,
    homeworkId: Long,
    onBack: () -> Unit,
    onCompleted: (studentId: Long, homeworkId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimerExecutionViewModel = hiltViewModel(),
) {
    LaunchedEffect(studentId, homeworkId) { viewModel.start(studentId, homeworkId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // 通知权限：Android 13+ 走系统运行时弹窗（拒绝后仍可手动开启，不阻断计时）
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onPermissionResult(granted) }
    // 从系统设置返回时刷新权限状态（精确闹钟授权在设置页完成）
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissionStatus()
        onPauseOrDispose { }
    }
    // 离开页面时打断未播完的语音（计时与走秒服务继续，仅停播报）
    DisposableEffect(viewModel) {
        onDispose { viewModel.onLeavingPage() }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TimerExecutionEvent.ShowMessage ->
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Long)

                is TimerExecutionEvent.HomeworkCompleted ->
                    onCompleted(event.studentId, event.homeworkId)
            }
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        TimerExecutionContent(
            uiState = uiState,
            onBack = onBack,
            onStart = viewModel::onStartClick,
            onPause = viewModel::onPauseClick,
            onResume = viewModel::onResumeClick,
            onComplete = viewModel::onCompleteClick,
            onVoiceToggle = viewModel::onVoiceToggle,
            onPermissionAction = { kind ->
                when (kind) {
                    TimerPermissionKind.NOTIFICATIONS ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            // Android 13 以下通知默认可用：只读真实状态，不谎报「已授权」
                            viewModel.onPermissionRecheck()
                        }

                    TimerPermissionKind.EXACT_ALARM -> openExactAlarmSettings(context)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/**
 * 打开系统「闹钟和提醒」授权页（Android 12+）。
 * 个别设备无该页面时退化为应用详情页；启动失败一律静默，不影响计时（拒绝授权仅影响提醒准点性）。
 */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return
    }
    val packageUri = Uri.parse("package:${context.packageName}")
    val requestIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(requestIntent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** 执行页内容（无状态，便于预览与测试） */
@Composable
fun TimerExecutionContent(
    uiState: TimerExecutionUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    onVoiceToggle: (Boolean) -> Unit = {},
    onPermissionAction: (TimerPermissionKind) -> Unit = {},
) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(text = "返回") }
        }
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在准备计时…")
            uiState.missingSession -> CoreErrorPlaceholder(message = TimerErrorMessages.NO_ACTIVE_SESSION)
            uiState.missingStudent -> CoreEmptyPlaceholder(text = "请先选择学生")
            uiState.missingHomework -> CoreEmptyPlaceholder(text = "作业不存在，可能已被删除")
            else -> ExecutionBody(
                uiState = uiState,
                onStart = onStart,
                onPause = onPause,
                onResume = onResume,
                onComplete = onComplete,
                onVoiceToggle = onVoiceToggle,
                onPermissionAction = onPermissionAction,
            )
        }
    }
}

@Composable
private fun ExecutionBody(
    uiState: TimerExecutionUiState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onVoiceToggle: (Boolean) -> Unit,
    onPermissionAction: (TimerPermissionKind) -> Unit,
) {
    val homework = uiState.homework ?: return
    Spacer(modifier = Modifier.height(8.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = homework.content,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = homework.estimatedMinutes?.let { "预计 $it 分钟" } ?: "未设定预估时长",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "状态：${uiState.phase.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = TimeFormatters.formatDuration(uiState.elapsedSeconds),
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = "已用时长",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (uiState.pauseCount > 0) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "已暂停 ${uiState.pauseCount} 次 · 累计 " +
                TimeFormatters.formatDuration(TimerCalculations.displaySeconds(uiState.pausedTotalMillis)),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    uiState.encouragementText?.let { text ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (uiState.homeworkEditingLocked) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "计时进行中，暂时不能修改作业内容与时间",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (uiState.phase == TimerPhase.FINISHED) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "这项作业已完成，休息一下吧",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // 语音引导开关（timer-B）：关闭后所有播报静默跳过，不影响计时
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "语音提醒",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(0.8f),
        )
        Switch(
            checked = uiState.voiceEnabled,
            onCheckedChange = onVoiceToggle,
        )
    }
    // 权限引导（timer-B）：通知/精确闹钟未授权时给出可手动开启的提示，拒绝不阻断计时
    Spacer(modifier = Modifier.height(8.dp))
    TimerPermissionBanner(
        hints = uiState.permissionHints,
        onAction = onPermissionAction,
    )
    Spacer(modifier = Modifier.height(28.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (uiState.actions.canStart) {
            AssignMateBigButton(text = "开始作业", onClick = onStart, enabled = !uiState.busy)
        }
        if (uiState.actions.canPause) {
            AssignMateBigButton(
                text = "有事走开",
                onClick = onPause,
                enabled = !uiState.busy,
                containerColor = MaterialTheme.colorScheme.secondary,
            )
        }
        if (uiState.actions.canResume) {
            AssignMateBigButton(
                text = "我回来啦",
                onClick = onResume,
                enabled = !uiState.busy,
                containerColor = MaterialTheme.colorScheme.secondary,
            )
        }
        if (uiState.actions.canComplete) {
            AssignMateBigButton(
                text = "完成作业",
                onClick = onComplete,
                enabled = !uiState.busy,
                containerColor = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
