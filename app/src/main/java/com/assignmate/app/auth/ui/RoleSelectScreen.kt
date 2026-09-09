package com.assignmate.app.auth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.theme.AssignMateTheme

/**
 * 首页身份选择入口：儿童友好大按钮风格——“我是家长 / 我是学生”。
 * 启动若存在已持久化会话，ViewModel 会发送恢复事件直达对应首页。
 */
@Composable
fun RoleSelectRoute(
    onGoParentLogin: () -> Unit,
    onGoStudentEnter: () -> Unit,
    onResumeParentHome: () -> Unit,
    onResumeStudentHome: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoleSelectViewModel = hiltViewModel(),
) {
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RoleSelectEvent.ResumeParentHome -> onResumeParentHome()
                RoleSelectEvent.ResumeStudentHome -> onResumeStudentHome()
            }
        }
    }
    RoleSelectContent(
        onParentClick = onGoParentLogin,
        onStudentClick = onGoStudentEnter,
        modifier = modifier,
    )
}

/** 身份选择内容（无状态，便于预览与测试） */
@Composable
fun RoleSelectContent(
    onParentClick: () -> Unit,
    onStudentClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "👋",
            style = MaterialTheme.typography.displayMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "欢迎使用作业助手",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "请选择你的身份进入",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(40.dp))
        AssignMateBigButton(
            text = "我是家长",
            onClick = onParentClick,
            containerColor = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        AssignMateBigButton(
            text = "我是学生",
            onClick = onStudentClick,
            containerColor = MaterialTheme.colorScheme.secondary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "家长可管理孩子档案与作业；学生凭家长提供的验证码直接进入",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RoleSelectContentPreview() {
    AssignMateTheme {
        RoleSelectContent(onParentClick = {}, onStudentClick = {})
    }
}