package com.assignmate.app.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder

/**
 * 设置主页：OCR 配置摘要（已配置 / 未配置）+ 当前主题档位 + 数据清理面板。
 *
 * 导航契约（framework 接线）：
 * - [onOpenOcrConfig] -> [SettingsDestination.OCR_CONFIG]（**仅家长会话渲染该入口**：
 *   [SettingsUiState.canAccessOcrConfig] 为 false 时入口直接不渲染，学生会话页面层不可达）；
 * - [onOpenTheme] -> [SettingsDestination.THEME]（家长与学生均可进入）；
 * - [onBack] -> 返回上一级（家长主界面 / 学生首页）。
 *
 * 数据清理：面板展示待清理条数，按钮在无内容时置灰并展示「暂无可清理内容」空态；
 * 点击后弹二次确认（「将删除 N 条识别任务（含图片），不可恢复」），确认执行后刷新计数并提示「已清理 N 条」。
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenOcrConfig: () -> Unit,
    onOpenTheme: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowMessage -> snackbarHostState.showSnackbar(
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
        SettingsContent(
            uiState = uiState,
            callbacks = SettingsCallbacks(
                onBack = onBack,
                onOpenOcrConfig = onOpenOcrConfig,
                onOpenTheme = onOpenTheme,
                onCleanupRequest = viewModel::onCleanupRequested,
                onCleanupConfirm = viewModel::onCleanupConfirmed,
                onCleanupDismiss = viewModel::onCleanupDismissed,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
    if (uiState.confirmingCleanup) {
        AlertDialog(
            onDismissRequest = viewModel::onCleanupDismissed,
            title = { Text(text = "确认清理") },
            text = { Text(text = uiState.confirmMessage) },
            confirmButton = {
                TextButton(onClick = viewModel::onCleanupConfirmed) { Text(text = "确认删除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onCleanupDismissed) { Text(text = "取消") }
            },
        )
    }
}

/** 设置主页回调集合 */
class SettingsCallbacks(
    val onBack: () -> Unit,
    val onOpenOcrConfig: () -> Unit,
    val onOpenTheme: () -> Unit,
    val onCleanupRequest: () -> Unit,
    val onCleanupConfirm: () -> Unit,
    val onCleanupDismiss: () -> Unit,
)

/** 设置主页内容（无状态，便于预览与测试） */
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    callbacks: SettingsCallbacks,
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
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            !uiState.themeModeLoaded -> CoreLoadingPlaceholder(text = "正在读取设置…")

            // 分权拒绝：不渲染任何设置入口（OCR 配置对学生端不可达）
            uiState.accessDenied && !uiState.canAccessOcrConfig -> {
                CoreEmptyPlaceholder(text = SettingsViewModel.OCR_PAGE_DENIED_HINT)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "如为误判，请退出后重新以家长身份登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            else -> {
                if (uiState.canAccessOcrConfig) {
                    SettingsEntryCard(
                        title = "识别设置（OCR 厂商配置）",
                        value = uiState.ocrSummaryText,
                        hint = "配置服务地址、模型名称与 API 密钥",
                        onClick = callbacks.onOpenOcrConfig,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                SettingsEntryCard(
                    title = "护眼设置",
                    value = uiState.themeMode.label,
                    hint = "跟随系统 / 护眼浅色 / 护眼夜间",
                    onClick = callbacks.onOpenTheme,
                )
                Spacer(modifier = Modifier.height(12.dp))
                CleanupPanel(uiState = uiState, callbacks = callbacks)
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** 设置项卡片（标题 + 当前值 + 说明，整卡可点击进入二级页） */
@Composable
private fun SettingsEntryCard(
    title: String,
    value: String,
    hint: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 数据清理面板：待清理条数 + 清理按钮 + 空态文案。
 *
 * 空态（[SettingsUiState.cleanupEmpty]）：按钮置灰并就地展示「暂无可清理内容」，
 * 不只靠 Snackbar 提示（与 HomeworkTimeSetScreen 的就地渲染约定一致）。
 */
@Composable
private fun CleanupPanel(
    uiState: SettingsUiState,
    callbacks: SettingsCallbacks,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "数据清理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiState.pendingCountText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.cleanupEmpty) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = uiState.emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            uiState.cleanedResultText?.let { cleanedText ->
                // 清理结果就地留痕（不只依赖一次性 Snackbar，返回本页/重组后仍可见）
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = cleanedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            AssignMateBigButton(
                text = uiState.cleanupButtonText,
                onClick = callbacks.onCleanupRequest,
                enabled = uiState.canCleanup,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "清理将删除全部识别任务记录与本地图片，操作不可恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}