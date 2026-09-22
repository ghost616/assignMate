package com.assignmate.app.auth.ui

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder
import com.assignmate.app.core.ui.theme.AssignMateTheme

/**
 * 学生端首页：展示“当前学生姓名/家长账号归属”等会话信息，并提供“进入我的作业”“今日盘点”
 * “护眼设置”入口与退出登录（回身份选择页）。
 *
 * @param onEnterHomework 进入我的作业：导航侧使用当前学生会话的 studentId 打开 homework
 *   作业清单（学生会话由“家长账号 + 验证码”进入时写入）；默认空实现，保证既有接线兼容。
 * @param onOpenThemeSettings 打开护眼设置：仅暴露导航意图（本模块不依赖 settings 实现，
 *   亦不 import settings 包），由 NavHost 注入 settings 路由的跳转；
 *   默认空实现，未接线时点「护眼设置」为无操作，不崩溃。
 * @param onOpenDaySummary 打开我的「今日盘点」页：同样只暴露导航意图（本模块不依赖 stats 实现，
 *   亦不 import stats 包），**无参**——盘点页按当前学生会话收敛为本人，无需传学生 id；
 *   默认空实现，未接线时点击无操作、不崩溃。
 */
@Composable
fun StudentHomeRoute(
    onLoggedOut: () -> Unit,
    onEnterHomework: () -> Unit = {},
    onOpenThemeSettings: () -> Unit = {},
    onOpenDaySummary: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: StudentHomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                StudentHomeEvent.LoggedOut -> onLoggedOut()
            }
        }
    }
    StudentHomeContent(
        uiState = uiState,
        actions = StudentHomeActions(
            onLogout = viewModel::onLogoutClick,
            onEnterHomework = onEnterHomework,
            onOpenThemeSettings = onOpenThemeSettings,
            onOpenDaySummary = onOpenDaySummary,
        ),
        modifier = modifier,
    )
}

/**
 * 学生端首页动作集（避免内容函数参数过长）：
 * 「进入我的作业」「今日盘点」「护眼设置」「退出登录」四个入口均只承载导航/会话意图。
 */
class StudentHomeActions(
    val onLogout: () -> Unit,
    val onEnterHomework: () -> Unit,
    /** 打开护眼设置：导航意图回调（settings 实现在其自身模块，本模块不感知） */
    val onOpenThemeSettings: () -> Unit = {},
    /** 打开本人的「今日盘点」页：导航意图回调（stats 实现在其自身模块，本模块不感知），无需传学生 id */
    val onOpenDaySummary: () -> Unit = {},
)

/** 学生端首页动作集的默认实现：全部空实现，用于预览与未接线渲染（点击无操作、不崩溃） */
fun studentHomeActionsDefault(): StudentHomeActions = StudentHomeActions(
    onLogout = {},
    onEnterHomework = {},
    onOpenThemeSettings = {},
    onOpenDaySummary = {},
)

/** 学生端首页内容（无状态） */
@Composable
fun StudentHomeContent(
    uiState: StudentHomeUiState,
    actions: StudentHomeActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在进入…")
            uiState.missingSession -> {
                Text(
                    text = "😕 尚未进入学生模式",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "请回到身份选择页，输入“家长账号 + 验证码”进入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                AssignMateBigButton(
                    text = "退出并返回身份选择",
                    onClick = actions.onLogout,
                    containerColor = MaterialTheme.colorScheme.secondary,
                )
            }

            else -> {
                Text(
                    text = "你好，${uiState.studentName ?: "同学"}！👋",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "当前身份",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "学生：${uiState.studentName ?: "-"}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "家长账号 ID：${uiState.parentId ?: "-"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "📚 我的作业",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "点下方按钮查看你的作业清单",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                AssignMateBigButton(
                    text = "📚 进入我的作业",
                    onClick = actions.onEnterHomework,
                    containerColor = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                // 护眼设置入口：与「进入我的作业」并列，仅回调导航意图（settings 实现不在本模块）
                AssignMateBigButton(
                    text = "🌙 护眼设置",
                    onClick = actions.onOpenThemeSettings,
                    containerColor = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(modifier = Modifier.height(16.dp))
                // 今日盘点入口：与「进入我的作业」「护眼设置」并列，仅回调导航意图（stats 实现不在本模块）。
                // 无需传学生 id——盘点页按当前学生会话收敛为本人。
                AssignMateBigButton(
                    text = "📊 今日盘点",
                    onClick = actions.onOpenDaySummary,
                    containerColor = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(modifier = Modifier.height(24.dp))
                AssignMateBigButton(
                    text = "退出登录",
                    onClick = actions.onLogout,
                    containerColor = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StudentHomeContentPreview() {
    AssignMateTheme {
        StudentHomeContent(
            uiState = StudentHomeUiState(
                loading = false,
                parentId = 1L,
                studentId = 10L,
                studentName = "小明",
            ),
            actions = studentHomeActionsDefault(),
        )
    }
}