package com.assignmate.app.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder
import com.assignmate.app.settings.domain.EyeCareTheme

/**
 * 护眼设置页：三档单选（跟随系统 / 护眼浅色 / 护眼夜间），选中即时生效并持久化。
 *
 * 生效机制：选中即写入 core 的主题偏好（[com.assignmate.app.core.domain.prefs.ThemePreferenceStore]），
 * 页面状态同步更新 → 根主题按 `resolveEyeCareDarkTheme(mode)` 立即切换深浅色；
 * 持久化后重启仍保持所选档位。
 *
 * 分权：家长与学生均可访问（学生端首页有「🌙 护眼设置」入口）；仅未登录会话写入被拒并就地提示（回滚档位）。
 */
@Composable
fun ThemeSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemeSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ThemeSettingsEvent.ShowMessage -> snackbarHostState.showSnackbar(
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
        ThemeSettingsContent(
            uiState = uiState,
            onBack = onBack,
            onModeSelected = viewModel::onModeSelected,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/** 护眼设置页内容（无状态，便于预览与测试） */
@Composable
fun ThemeSettingsContent(
    uiState: ThemeSettingsUiState,
    onBack: () -> Unit,
    onModeSelected: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text(text = "返回") }
        Text(
            text = "护眼设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "当前档位：${uiState.currentModeText}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (uiState.loading) {
            CoreLoadingPlaceholder(text = "正在读取护眼设置…")
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    uiState.modes.forEach { mode ->
                        ThemeModeRow(
                            mode = mode,
                            selected = mode == uiState.selectedMode,
                            onSelected = { onModeSelected(mode) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = modeDescription(uiState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.accessDenied && uiState.accessDeniedHint.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.accessDeniedHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 单个档位行：单选圆点 + 档位文案（整行可点击，触达面积适合儿童/家长操作） */
@Composable
private fun ThemeModeRow(
    mode: ThemeMode,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelected)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelected)
        Text(
            text = mode.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 档位补充说明（跟随系统说明交给系统，两档护眼说明固定色方案） */
private fun modeDescription(uiState: ThemeSettingsUiState): String = when (uiState.darkThemeOverride) {
    null -> "当前跟随系统深浅色设置；选用护眼档位可固定为低蓝光暖色方案。"
    false -> "已固定护眼浅色（低蓝光暖色浅色方案），不受系统深浅色影响。"
    true -> "已固定护眼夜间（低蓝光暖棕暗色方案），夜间使用更柔和。"
}

/** 三档顺序（供页面横幅/测试引用，避免各处硬编码顺序） */
val EYE_CARE_MODES: List<ThemeMode> get() = EyeCareTheme.MODES