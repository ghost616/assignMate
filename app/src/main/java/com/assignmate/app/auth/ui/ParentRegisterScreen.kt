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
import com.assignmate.app.auth.domain.AuthConstants
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.theme.AssignMateTheme

/**
 * 家长注册页：账号 / 密码 / 确认密码；注册成功直达家长主界面，
 * 账号重复时提示并可跳转登录页。
 */
@Composable
fun ParentRegisterRoute(
    onRegistered: () -> Unit,
    onNavigateToLogin: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ParentRegisterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ParentRegisterEvent.Registered -> onRegistered()
                ParentRegisterEvent.NavigateToLogin -> onNavigateToLogin()
            }
        }
    }
    ParentRegisterContent(
        uiState = uiState,
        onAccountChange = viewModel::onAccountChange,
        onPasswordChange = viewModel::onPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onTogglePasswordVisible = viewModel::onTogglePasswordVisible,
        onRegister = viewModel::onRegister,
        onBackToLogin = onNavigateToLogin,
        modifier = modifier,
    )
}

/** 家长注册内容（无状态，便于预览与测试） */
@Composable
fun ParentRegisterContent(
    uiState: ParentRegisterUiState,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onRegister: () -> Unit,
    onBackToLogin: () -> Unit,
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
            text = "注册家长账号",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "注册后可管理最多 ${AuthConstants.MAX_STUDENTS} 位学生的档案",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedTextField(
            value = uiState.account,
            onValueChange = onAccountChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("家长账号（邮箱/手机号等）") },
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
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.confirmPassword,
            onValueChange = onConfirmPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("确认密码") },
            singleLine = true,
            isError = uiState.confirmPasswordError != null,
            supportingText = uiState.confirmPasswordError?.let { { Text(it) } },
            visualTransformation = if (uiState.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
            text = if (uiState.submitting) "注册中…" else "注册并进入",
            onClick = onRegister,
            enabled = !uiState.submitting,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "已有账号？",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onBackToLogin, enabled = !uiState.submitting) {
                Text("返回登录")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ParentRegisterContentPreview() {
    AssignMateTheme {
        ParentRegisterContent(
            uiState = ParentRegisterUiState(),
            onAccountChange = {},
            onPasswordChange = {},
            onConfirmPasswordChange = {},
            onTogglePasswordVisible = {},
            onRegister = {},
            onBackToLogin = {},
        )
    }
}