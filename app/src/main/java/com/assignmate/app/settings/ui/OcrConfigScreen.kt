package com.assignmate.app.settings.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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

/**
 * OCR 厂商配置页：服务地址 / 模型名称 / API 密钥 / 启用开关。
 *
 * 分权（页面层保险）：[OcrConfigUiState.accessDenied] 为 true 时整页渲染拒绝态、**不渲染任何输入框**——
 * 学生会话或未登录会话即使通过深链到达本页也看不到密钥，更无法保存（数据层另有一层拒绝）。
 *
 * 校验：保存前经 core 的 `OcrConfig.validationIssues()`（经 settings 的校验器映射到字段）就地渲染问题文案，
 * 不只依赖一次性 Snackbar；保存成功给出 Snackbar 提示。
 *
 * 密钥：默认掩码显示（`sk-****abcd`），可切换明文/掩码；掩码态下点击输入框先切明文再编辑，
 * 避免用户把掩码串当真实值保存。本页不做真实网络请求测试、不做图片识别。
 */
@Composable
fun OcrConfigRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OcrConfigViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // 两类事件都只做一次性提示：保存成功不关页（便于继续调整），分权拒绝也提示原因
            val message = when (event) {
                is OcrConfigEvent.SaveSucceeded -> event.message
                is OcrConfigEvent.ShowMessage -> event.message
            }
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
        }
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        OcrConfigContent(
            uiState = uiState,
            callbacks = OcrConfigCallbacks(
                onBack = onBack,
                onApiBaseUrlChange = viewModel::onApiBaseUrlChange,
                onModelNameChange = viewModel::onModelNameChange,
                onApiKeyChange = viewModel::onApiKeyChange,
                onEnabledChange = viewModel::onEnabledChange,
                onToggleApiKeyVisibility = viewModel::onToggleApiKeyVisibility,
                onSave = viewModel::onSave,
            ),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/** OCR 配置页回调集合 */
class OcrConfigCallbacks(
    val onBack: () -> Unit,
    val onApiBaseUrlChange: (String) -> Unit,
    val onModelNameChange: (String) -> Unit,
    val onApiKeyChange: (String) -> Unit,
    val onEnabledChange: (Boolean) -> Unit,
    val onToggleApiKeyVisibility: () -> Unit,
    val onSave: () -> Unit,
)

/** OCR 配置页内容（无状态，便于预览与测试） */
@Composable
fun OcrConfigContent(
    uiState: OcrConfigUiState,
    callbacks: OcrConfigCallbacks,
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
            text = "识别设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "填写可解析图片的多模态大模型厂商参数（兼容 chat/completions 协议）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在读取识别设置…")

            uiState.accessDenied -> {
                CoreEmptyPlaceholder(text = uiState.accessDeniedHint)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "识别设置包含 API 密钥，仅家长账号可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            else -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        EnabledSwitchRow(uiState = uiState, callbacks = callbacks)
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = uiState.apiBaseUrl,
                            onValueChange = callbacks.onApiBaseUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("服务地址") },
                            placeholder = { Text("https://api.example.com/v1") },
                            singleLine = true,
                            isError = uiState.apiBaseUrlError != null,
                            supportingText = {
                                Text(
                                    text = uiState.apiBaseUrlError
                                        ?: "兼容 OpenAI 协议的接口前缀，含版本号",
                                )
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.modelName,
                            onValueChange = callbacks.onModelNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("模型名称") },
                            placeholder = { Text("gpt-4o-mini") },
                            singleLine = true,
                            isError = uiState.modelNameError != null,
                            supportingText = {
                                Text(text = uiState.modelNameError ?: "需支持图片输入的多模态模型")
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.displayApiKey,
                            onValueChange = { value ->
                                // 掩码态下先切明文再写入，避免把掩码串当真实密钥提交
                                if (!uiState.apiKeyVisible) {
                                    callbacks.onToggleApiKeyVisibility()
                                }
                                callbacks.onApiKeyChange(value)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("API 密钥") },
                            placeholder = { Text("sk-…") },
                            singleLine = true,
                            readOnly = !uiState.apiKeyVisible,
                            isError = uiState.apiKeyError != null,
                            supportingText = {
                                Text(
                                    text = uiState.apiKeyError
                                        ?: "密钥仅保存在本机（加密存储），不会写入日志",
                                )
                            },
                            trailingIcon = {
                                TextButton(onClick = callbacks.onToggleApiKeyVisibility) {
                                    Text(text = uiState.apiKeyToggleText)
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        AssignMateBigButton(
                            text = uiState.saveButtonText,
                            onClick = callbacks.onSave,
                            enabled = uiState.canSave,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** 启用开关行：标题 + 说明 + 开关，未启用时就地展示 core 的「识别功能未启用」提示 */
@Composable
private fun EnabledSwitchRow(
    uiState: OcrConfigUiState,
    callbacks: OcrConfigCallbacks,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "启用云端识别",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = uiState.enabledHint ?: "关闭后识别一律返回「未配置」，不影响已有作业",
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.enabledHint != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Switch(
            checked = uiState.enabled,
            onCheckedChange = callbacks.onEnabledChange,
        )
    }
}