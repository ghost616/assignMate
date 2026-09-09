package com.assignmate.app.auth.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.auth.domain.AuthConstants
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.theme.AssignMateTheme

/**
 * 学生进入页：输入“家长账号 + 验证码”，匹配成功直达学生本人作业界面
 * （当前为 STUDENT_HOME 占位，作业清单待 homework 模块填充）。
 */
@Composable
fun StudentEnterRoute(
    onEntered: (studentName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StudentEnterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is StudentEnterEvent.Entered -> onEntered(event.studentName)
            }
        }
    }
    StudentEnterContent(
        uiState = uiState,
        onParentAccountChange = viewModel::onParentAccountChange,
        onVerificationCodeChange = viewModel::onVerificationCodeChange,
        onEnter = viewModel::onEnter,
        modifier = modifier,
    )
}

/** 学生进入内容（无状态，便于预览与测试） */
@Composable
fun StudentEnterContent(
    uiState: StudentEnterUiState,
    onParentAccountChange: (String) -> Unit,
    onVerificationCodeChange: (String) -> Unit,
    onEnter: () -> Unit,
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
            text = "学生入口",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "请爸爸妈妈输入家长账号并告诉你验证码，即可进入你的作业页面",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedTextField(
            value = uiState.parentAccount,
            onValueChange = onParentAccountChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("家长账号") },
            singleLine = true,
            isError = uiState.parentAccountError != null,
            supportingText = uiState.parentAccountError?.let { { Text(it) } },
            enabled = !uiState.submitting,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.verificationCode,
            onValueChange = onVerificationCodeChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("进入验证码") },
            singleLine = true,
            isError = uiState.codeError != null,
            supportingText = uiState.codeError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            enabled = !uiState.submitting,
        )
        Text(
            text = "${AuthConstants.VERIFICATION_CODE_MIN_LENGTH}-" +
                "${AuthConstants.VERIFICATION_CODE_MAX_LENGTH} 位数字，由家长在“学生档案”中查看",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
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
            text = if (uiState.submitting) "进入中…" else "进入我的作业",
            onClick = onEnter,
            enabled = !uiState.submitting,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StudentEnterContentPreview() {
    AssignMateTheme {
        StudentEnterContent(
            uiState = StudentEnterUiState(),
            onParentAccountChange = {},
            onVerificationCodeChange = {},
            onEnter = {},
        )
    }
}