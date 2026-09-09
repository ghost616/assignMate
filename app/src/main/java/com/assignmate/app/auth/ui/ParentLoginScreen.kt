package com.assignmate.app.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.auth.data.ThirdPartyChannel
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.theme.AssignMateTheme

/**
 * 家长登录页：账号 + 密码登录；含“去注册”入口与三方登录占位入口
 * （微信/QQ/支付宝未接入，按钮置灰并提示“即将上线”）。
 */
@Composable
fun ParentLoginRoute(
    onNavigateToRegister: () -> Unit,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ParentLoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ParentLoginEvent.NavigateToRegister -> onNavigateToRegister()
                ParentLoginEvent.LoginSuccess -> onLoggedIn()
            }
        }
    }
    ParentLoginContent(
        uiState = uiState,
        onAccountChange = viewModel::onAccountChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisible = viewModel::onTogglePasswordVisible,
        onLogin = viewModel::onLogin,
        onNavigateToRegister = viewModel::onNavigateToRegister,
        modifier = modifier,
    )
}

/** 家长登录内容（无状态，便于预览与测试） */
@Composable
fun ParentLoginContent(
    uiState: ParentLoginUiState,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onLogin: () -> Unit,
    onNavigateToRegister: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "家长登录",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(
            value = uiState.account,
            onValueChange = onAccountChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("家长账号") },
            singleLine = true,
            isError = uiState.accountError != null,
            supportingText = uiState.accountError?.let { { Text(it) } },
            enabled = !uiState.submitting,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            singleLine = true,
            isError = uiState.passwordError != null,
            supportingText = uiState.passwordError?.let { { Text(it) } },
            visualTransformation = if (uiState.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = onTogglePasswordVisible) {
                    Text(if (uiState.passwordVisible) "隐藏" else "显示")
                }
            },
            enabled = !uiState.submitting,
        )
        if (uiState.formError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = uiState.formError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        AssignMateBigButton(
            text = if (uiState.submitting) "登录中…" else "登 录",
            onClick = onLogin,
            enabled = !uiState.submitting,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "还没有账号？",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onNavigateToRegister, enabled = !uiState.submitting) {
                Text("立即注册")
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "三方登录",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThirdPartyChannel.entries.forEach { channel ->
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("${channel.displayName} 登录")
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "微信 / QQ / 支付宝登录即将上线",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ParentLoginContentPreview() {
    AssignMateTheme {
        ParentLoginContent(
            uiState = ParentLoginUiState(),
            onAccountChange = {},
            onPasswordChange = {},
            onTogglePasswordVisible = {},
            onLogin = {},
            onNavigateToRegister = {},
        )
    }
}