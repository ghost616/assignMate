package com.assignmate.app.auth.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.auth.domain.AuthConstants
import com.assignmate.app.auth.domain.Student
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder

/**
 * 家长主界面：学生档案卡片列表 + 添加（≤5 拦截）+ 改名 + 重置/修改验证码 +
 * 删除确认 + “进入某学生作业界面”占位入口（待 homework 模块落地）。
 */
@Composable
fun ParentHomeRoute(
    onSessionExpired: () -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ParentHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ParentHomeEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    message = event.message,
                    duration = SnackbarDuration.Long,
                )

                ParentHomeEvent.SessionExpired -> onSessionExpired()
                ParentHomeEvent.LoggedOut -> onLoggedOut()
            }
        }
    }
    val actions = ParentHomeActions(
        onAddClick = viewModel::onAddClick,
        onAddNameChange = viewModel::onAddNameChange,
        onAddConfirm = viewModel::onAddConfirm,
        onAddDismiss = viewModel::onAddDismiss,
        onRenameClick = viewModel::onRenameClick,
        onRenameNameChange = viewModel::onRenameNameChange,
        onRenameConfirm = viewModel::onRenameConfirm,
        onRenameDismiss = viewModel::onRenameDismiss,
        onDeleteClick = viewModel::onDeleteClick,
        onDeleteConfirm = viewModel::onDeleteConfirm,
        onDeleteDismiss = viewModel::onDeleteDismiss,
        onCodeClick = viewModel::onCodeClick,
        onCodeInputChange = viewModel::onCodeInputChange,
        onRegenerateCode = viewModel::onRegenerateCode,
        onSaveCustomCode = viewModel::onSaveCustomCode,
        onCodeDismiss = viewModel::onCodeDismiss,
        onEnterHomework = viewModel::onEnterHomework,
        onLogout = viewModel::onLogoutClick,
    )
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        ParentHomeContent(
            uiState = uiState,
            actions = actions,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/** 家长主界面动作集（避免内容函数参数过长） */
class ParentHomeActions(
    val onAddClick: () -> Unit,
    val onAddNameChange: (String) -> Unit,
    val onAddConfirm: () -> Unit,
    val onAddDismiss: () -> Unit,
    val onRenameClick: (Student) -> Unit,
    val onRenameNameChange: (String) -> Unit,
    val onRenameConfirm: () -> Unit,
    val onRenameDismiss: () -> Unit,
    val onDeleteClick: (Student) -> Unit,
    val onDeleteConfirm: () -> Unit,
    val onDeleteDismiss: () -> Unit,
    val onCodeClick: (Student) -> Unit,
    val onCodeInputChange: (String) -> Unit,
    val onRegenerateCode: () -> Unit,
    val onSaveCustomCode: () -> Unit,
    val onCodeDismiss: () -> Unit,
    val onEnterHomework: (Student) -> Unit,
    val onLogout: () -> Unit,
)

/** 家长主界面内容（无状态） */
@Composable
fun ParentHomeContent(
    uiState: ParentHomeUiState,
    actions: ParentHomeActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "家长中心",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "共 ${uiState.students.size} 名学生（最多 ${AuthConstants.MAX_STUDENTS} 名）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        AssignMateBigButton(
            text = "＋ 添加学生",
            onClick = actions.onAddClick,
            containerColor = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在加载学生…")
            uiState.students.isEmpty() -> CoreEmptyPlaceholder(
                text = "还没有学生档案\n点上方按钮添加孩子吧",
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.students, key = { it.id }) { student ->
                    StudentCard(student = student, actions = actions)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = actions.onLogout) {
                Text(
                    text = "退出登录",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // ---- 弹窗：由状态驱动 ----
    if (uiState.addDialogVisible) {
        StudentNameDialog(
            title = "添加学生",
            confirmText = if (uiState.adding) "添加中…" else "添加",
            value = uiState.addName,
            error = uiState.addNameError,
            onValueChange = actions.onAddNameChange,
            onConfirm = actions.onAddConfirm,
            onDismiss = actions.onAddDismiss,
        )
    }
    val renameTarget = uiState.renameTarget
    if (renameTarget != null) {
        StudentNameDialog(
            title = "修改「${renameTarget.name}」的姓名",
            confirmText = if (uiState.renaming) "保存中…" else "保存",
            value = uiState.renameName,
            error = uiState.renameError,
            onValueChange = actions.onRenameNameChange,
            onConfirm = actions.onRenameConfirm,
            onDismiss = actions.onRenameDismiss,
        )
    }
    val deleteTarget = uiState.deleteTarget
    if (deleteTarget != null) {
        DeleteStudentDialog(
            studentName = deleteTarget.name,
            busy = uiState.deleting,
            onConfirm = actions.onDeleteConfirm,
            onDismiss = actions.onDeleteDismiss,
        )
    }
    val codeTarget = uiState.codeTarget
    if (codeTarget != null) {
        CodeDialog(
            studentName = codeTarget.name,
            value = uiState.codeInput,
            error = uiState.codeError,
            busy = uiState.codeBusy,
            onValueChange = actions.onCodeInputChange,
            onRegenerate = actions.onRegenerateCode,
            onSave = actions.onSaveCustomCode,
            onDismiss = actions.onCodeDismiss,
        )
    }
}

/** 学生档案卡片 */
@Composable
private fun StudentCard(
    student: Student,
    actions: ParentHomeActions,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = student.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "进入验证码：${student.verificationCode}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardActionButton(text = "进入作业", onClick = { actions.onEnterHomework(student) })
                CardActionButton(text = "改名", onClick = { actions.onRenameClick(student) })
                CardActionButton(text = "验证码", onClick = { actions.onCodeClick(student) })
                TextButton(onClick = { actions.onDeleteClick(student) }) {
                    Text(
                        text = "删除",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardActionButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 姓名输入弹窗（添加/改名复用） */
@Composable
private fun StudentNameDialog(
    title: String,
    confirmText: String,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("学生姓名") },
                    singleLine = true,
                    isError = error != null,
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
    )
}

/** 删除确认弹窗 */
@Composable
private fun DeleteStudentDialog(
    studentName: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除「$studentName」？") },
        text = {
            Text("删除后该学生的档案将无法恢复，确定继续吗？")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(text = if (busy) "删除中…" else "删除", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(text = "取消")
            }
        },
    )
}

/** 验证码查看/重置/自定义弹窗 */
@Composable
private fun CodeDialog(
    studentName: String,
    value: String,
    error: String?,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onRegenerate: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「$studentName」的进入验证码") },
        text = {
            Column {
                Text(
                    text = "学生凭“家长账号 + 该验证码”进入本人作业界面",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("验证码（4-6 位数字）") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !busy,
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !busy) {
                Text(text = if (busy) "处理中…" else "保存修改", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRegenerate, enabled = !busy) {
                    Text(text = "随机生成")
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text(text = "关闭")
                }
            }
        },
    )
}