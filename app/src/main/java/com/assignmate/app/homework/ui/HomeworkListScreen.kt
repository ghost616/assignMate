package com.assignmate.app.homework.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.util.TimeFormatters
import com.assignmate.app.core.ui.components.AssignMateBigButton
import com.assignmate.app.core.ui.components.CoreEmptyPlaceholder
import com.assignmate.app.core.ui.components.CoreLoadingPlaceholder
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import java.time.ZoneId
import kotlinx.coroutines.flow.filterNotNull

/** 顶部并排大按钮的宽度：与「添加作业」按钮同高（56dp），避免占满整宽挤掉返回入口 */
private val INLINE_BIG_BUTTON_WIDTH = 160.dp

/**
 * 作业清单页：按优先级升序展示作业（内容/类型/开始时间/预估时长/状态标签，当天与阶段可区分），
 * 提供「开始作业」、上移下移调整优先级、进入编辑与时间设定、标记完成/撤销完成、删除确认；
 * 操作按钮按两个权限维度分别展示（见 [HomeworkRowUiState]）：
 * 编辑/删除/调序用改删权（学生仅自己录入项），开始作业/设时间/标记完成/撤销完成用执行权
 * （学生可操作本人名下全部作业，含家长布置的，保证「家长布置 → 学生计时完成」闭环可用）；
 * 进行中锁定（需求假设 C）：状态为「进行中」的作业隐藏调序与时间入口，仅保留完成/撤销完成。
 * 顶部「添加作业」进入录入入口页（四方式：手动 / 拍照 / 相册 / 语音）。
 *
 * [onStartHomework] 即「开始作业」的导航意图（默认空实现保持兼容），
 * 由 framework 计划接到计时页；[onHomeworkRemoved] 为删除成功后的收尾通知
 * （默认空实现，由 framework 接到「取消该作业的到点提醒」）；本模块不依赖 timer。
 *
 * 顶部「查看盘点」入口同样只以回调 [onOpenStats] 暴露导航意图（默认空实现），
 * 不引入 stats 模块依赖，接线由 framework 计划完成。
 *
 * 本页同时是「保存成功」提示的展示点：录入 / 模板 / 时间设定三个页面保存成功后**先回清单**
 * （onSaved），再由本页展示其文案（见 [homeworkSaveNotice] 与 [dispatchSaveCompletion]）——
 * 保存页因此无需 await 提示条，也不会因离开组合而丢掉提示或提醒同步。
 */
@Composable
fun HomeworkListRoute(
    studentId: Long,
    onBack: () -> Unit,
    onAddHomework: () -> Unit,
    onEditTime: (Long) -> Unit,
    onEditTemplate: (Long) -> Unit,
    onStartHomework: (homeworkId: Long) -> Unit = {},
    /**
     * 「查看盘点」导航意图（家长与学生均可进入）：携带清单当前展示的学生 id，
     * 由 framework 计划接到盘点页；本模块不依赖 stats。
     *
     * 默认空实现：未接线时点击无副作用，清单页既有行为完全不变。
     */
    onOpenStats: (studentId: Long) -> Unit = {},
    /**
     * 「作业已删除」的收尾通知（仅删除成功后调用一次）：由 framework 接到
     * 「取消该作业的到点提醒」，避免旧闹钟触发指向已删除作业的提醒。
     *
     * 默认空实现：未接线时删除流程与既有行为完全一致（homework 不依赖 timer）。
     */
    onHomeworkRemoved: (homeworkId: Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeworkListViewModel = hiltViewModel(),
) {
    LaunchedEffect(studentId) { viewModel.start(studentId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeworkListEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Long)
                }

                // 删除成功：先外抛收尾通知（framework 取消该作业的到点提醒），再展示提示
                is HomeworkListEvent.Deleted -> onHomeworkRemoved(event.homeworkId)
            }
        }
    }
    // 保存成功提示的**跨页展示点**：录入 / 模板 / 时间设定三个页面保存后先回清单（onSaved），
    // 再以非挂起方式把文案写入 homeworkSaveNotice；本页据此展示，故提示既不阻塞导航也不会丢失
    // （StateFlow 会把待展示值重放给本页新组合，见 HomeworkSaveNoticeHolder）。
    // 用 Short：确认性提示无需 Long 的 10 秒驻留，也不至于长时间占用清单页的提示队列。
    LaunchedEffect(homeworkSaveNotice) {
        homeworkSaveNotice.notice.filterNotNull().collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            homeworkSaveNotice.consume(message)
        }
    }
    val callbacks = HomeworkListCallbacks(
        onBack = onBack,
        onAddHomework = onAddHomework,
        onEditTime = onEditTime,
        onEditTemplate = onEditTemplate,
        onStartHomework = onStartHomework,
        onOpenStats = onOpenStats,
        onMoveUp = viewModel::onMoveUpClick,
        onMoveDown = viewModel::onMoveDownClick,
        onDeleteClick = viewModel::onDeleteClick,
        onDeleteConfirm = viewModel::onDeleteConfirm,
        onDeleteDismiss = viewModel::onDeleteDismiss,
        onStart = viewModel::onStartHomeworkClick,
        onComplete = viewModel::onCompleteClick,
        onReopen = viewModel::onReopenClick,
        onToggleCompleted = viewModel::onToggleCompletedHistory,
    )
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        HomeworkListContent(
            uiState = uiState,
            callbacks = callbacks,
            // 业务时区来自 ViewModel 注入的 core 唯一绑定：页面展示（时间/截止格式化）与
            // 仓库「今天」口径因此同源，覆写该绑定即全局生效（不再以 systemDefault 兜底）
            zoneId = viewModel.zoneId,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

/** 清单页回调集合（避免内容函数参数过长；默认实现项置于末尾，保留向下兼容） */
class HomeworkListCallbacks(
    val onBack: () -> Unit,
    val onAddHomework: () -> Unit,
    val onEditTime: (Long) -> Unit,
    val onEditTemplate: (Long) -> Unit,
    val onMoveUp: (Long) -> Unit,
    val onMoveDown: (Long) -> Unit,
    val onDeleteClick: (Long) -> Unit,
    val onDeleteConfirm: () -> Unit,
    val onDeleteDismiss: () -> Unit,
    val onComplete: (Long) -> Unit,
    val onReopen: (Long) -> Unit,
    /**
     * 「开始作业」导航意图：由 framework 接到计时页；默认空实现，未接线时点击不导航。
     * 状态推进（→ 进行中）由 [onStart] 落库，保证清单状态即时正确。
     */
    val onStartHomework: (homeworkId: Long) -> Unit = {},
    /**
     * 「查看盘点」导航意图：由 framework 接到盘点页；默认空实现，未接线时点击不导航。
     * 参数为目标学生 id（家长为学生本人的 id，学生为本人 id），家长与学生均可进入。
     */
    val onOpenStats: (studentId: Long) -> Unit = {},
    /** 「开始作业」：把作业推进为「进行中」（执行权校验 + 仓库兜底） */
    val onStart: (Long) -> Unit = {},
    /** 「已完成历史」分组展开/折叠（家长视角；学生视角不渲染该分组） */
    val onToggleCompleted: () -> Unit = {},
)

/**
 * 清单页内容（无状态，便于预览与测试）。
 *
 * @param zoneId 业务时区：由 [HomeworkListRoute] 透传 ViewModel 注入的 core 唯一绑定，
 *   **刻意不带默认值**（默认 `systemDefault()` 会让覆写绑定后的展示口径漂移）
 */
@Composable
fun HomeworkListContent(
    uiState: HomeworkListUiState,
    callbacks: HomeworkListCallbacks,
    zoneId: ZoneId,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 20.dp)) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = callbacks.onBack) { Text(text = "返回") }
            // 「查看盘点」：家长与学生均可进入；盘点对象为清单当前展示的学生，
            // 故仅在目标学生已确定时可用（加载中/未选学生/会话失效时禁用）
            AssignMateBigButton(
                text = "查看盘点",
                onClick = { uiState.statsStudentId?.let(callbacks.onOpenStats) },
                modifier = Modifier.width(INLINE_BIG_BUTTON_WIDTH),
                enabled = uiState.statsStudentId != null,
                containerColor = MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            text = "作业清单",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = uiState.studentName?.let { "学生：$it" }
                ?: "共 ${uiState.items.size} 项作业（按优先级排列）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 家长与学生均可录入作业（学生录入项由学生本人修改/删除，见权限规则）
        Spacer(modifier = Modifier.height(12.dp))
        AssignMateBigButton(
            text = "＋ 添加作业",
            onClick = callbacks.onAddHomework,
            containerColor = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            uiState.loading -> CoreLoadingPlaceholder(text = "正在加载作业…")
            uiState.missingSession -> CoreEmptyPlaceholder(
                text = "会话已失效\n请重新以家长或学生身份进入",
            )

            uiState.missingStudent -> CoreEmptyPlaceholder(
                text = "还没有选择学生\n请先在家庭页选择要查看作业的孩子",
            )

            uiState.isEmpty -> CoreEmptyPlaceholder(
                text = if (uiState.role == Role.PARENT) {
                    "还没有作业\n点上方按钮添加第一条吧"
                } else {
                    "今天还没有作业，好好休息一下 🌟"
                },
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 家长视角：已完成折叠入「已完成历史」；阶段已结束但仍有未完成天的归「已结束」并标注未完成天数。
                // 学生视角：只看当天，全部落在进行区（无分组标题）。
                val mainRows = uiState.items.filter { it.section == HomeworkListSection.MAIN }
                val completedRows = uiState.items.filter {
                    it.section == HomeworkListSection.COMPLETED_HISTORY
                }
                val endedRows = uiState.items.filter { it.section == HomeworkListSection.ENDED }
                if (uiState.role == Role.PARENT && mainRows.isNotEmpty()) {
                    item { SectionHeader(text = "今天 · 进行中") }
                }
                items(mainRows, key = { it.homework.id }) { row ->
                    HomeworkRow(row = row, role = uiState.role, callbacks = callbacks, zoneId = zoneId)
                }
                if (uiState.role == Role.PARENT && endedRows.isNotEmpty()) {
                    item { SectionHeader(text = "已结束") }
                    items(endedRows, key = { it.homework.id }) { row ->
                        HomeworkRow(row = row, role = uiState.role, callbacks = callbacks, zoneId = zoneId)
                    }
                }
                if (uiState.role == Role.PARENT && completedRows.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = "已完成历史（${completedRows.size}）",
                            expanded = uiState.completedExpanded,
                            onClick = callbacks.onToggleCompleted,
                        )
                    }
                    if (uiState.completedExpanded) {
                        items(completedRows, key = { it.homework.id }) { row ->
                            HomeworkRow(row = row, role = uiState.role, callbacks = callbacks, zoneId = zoneId)
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }

    val deleteTarget = uiState.deleteTarget
    if (deleteTarget != null) {
        DeleteHomeworkDialog(
            content = deleteTarget.content,
            busy = uiState.deleting,
            onConfirm = callbacks.onDeleteConfirm,
            onDismiss = callbacks.onDeleteDismiss,
        )
    }
}

/**
 * 单条作业卡片：优先级调序 + 信息 + 状态标签 + 操作区（按权限禁用/隐藏）。
 *
 * @param zoneId 业务时区（展示格式化用；由 [HomeworkListContent] 透传，刻意不带默认值）
 */
@Composable
fun HomeworkRow(
    row: HomeworkRowUiState,
    role: Role?,
    callbacks: HomeworkListCallbacks,
    zoneId: ZoneId,
    modifier: Modifier = Modifier,
) {
    val item = row.homework
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (item.content.isBlank()) "（待补充内容）" else item.content,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // 进行中锁定：隐藏调序入口（需求假设 C），避免点击后才发现被拒
                if (!row.lockedWorkInProgress) {
                    TextButton(
                        onClick = { callbacks.onMoveUp(item.id) },
                        enabled = row.canMoveUp,
                    ) { Text(text = "↑") }
                    TextButton(
                        onClick = { callbacks.onMoveDown(item.id) },
                        enabled = row.canMoveDown,
                    ) { Text(text = "↓") }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.typeLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = scheduleText(item, zoneId),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 截止时间：当天作业为「日期 + 时刻」，阶段作业只展示「每日时刻」（如「每天 21:00 截止」）
            item.deadlineLabel { millis -> TimeFormatters.formatDateTime(millis, zoneId) }?.let { text ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // 阶段覆盖区间（阶段作业）：展示阶段结束日与「每天到点截止」说明
            row.timeline.coverageText?.let { text ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(status = item.status)
                Text(
                    text = item.createdByRole.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 每日维度：今日状态 + 阶段进度（均由「作业每天详情」推导）
            if (row.todayStateText != null || row.stageProgressText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(row.todayStateText, row.stageProgressText).joinToString("　"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // 「已结束」分组标注未完成天数
            row.missedDaysText?.let { text ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 开始作业：执行权——待完成（及进行中）的本人名下作业；
                // 先落库推进为「进行中」，再以回调外抛导航意图（framework 接到计时页）
                if (row.canStart) {
                    TextButton(
                        onClick = {
                            callbacks.onStart(item.id)
                            callbacks.onStartHomework(item.id)
                        },
                    ) {
                        Text(
                            text = if (item.status == HomeworkStatus.IN_PROGRESS) {
                                "继续计时"
                            } else {
                                "开始作业"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // 编辑（内容/类型）：改删权——学生仅自己录入的作业
                if (row.canModify) {
                    TextButton(onClick = { callbacks.onEditTemplate(item.id) }) {
                        Text(text = "编辑", style = MaterialTheme.typography.labelLarge)
                    }
                }
                // 时间排定：执行权——学生可为自己名下（含家长布置的）作业排定时间；
                // 进行中锁定后不再展示（需求假设 C）
                if (row.canSchedule) {
                    TextButton(onClick = { callbacks.onEditTime(item.id) }) {
                        Text(
                            text = if (item.isScheduled) "改时间" else "设时间",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                // 标记完成：执行权——待完成/进行中的本人名下作业
                if (row.canComplete) {
                    TextButton(onClick = { callbacks.onComplete(item.id) }) {
                        Text(text = "标记完成", style = MaterialTheme.typography.labelLarge)
                    }
                }
                // 撤销完成：执行权——已完成的本人名下作业
                if (row.canReopen) {
                    TextButton(onClick = { callbacks.onReopen(item.id) }) {
                        Text(text = "撤销完成", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (row.canDelete) {
                    TextButton(onClick = { callbacks.onDeleteClick(item.id) }) {
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (row.lockedWorkInProgress) {
                    // 进行中锁定提示：调序与时间均不可调整
                    Text(
                        text = WORK_IN_PROGRESS_LOCKED_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!row.canModify && !row.canSchedule && !row.canStart &&
                    !row.canComplete && !row.canReopen && !row.canDelete
                ) {
                    Text(
                        text = EXECUTE_PERMISSION_DENIED_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 分组标题：家长视角下「今天 · 进行中」/「已结束」为静态标题，
 * 「已完成历史」可点击展开/折叠（[onClick] 非空时渲染为可点击行）。
 */
@Composable
private fun SectionHeader(
    text: String,
    expanded: Boolean? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val label = if (expanded == null) text else "$text ${if (expanded) "▾" else "▸"}"
    if (onClick == null) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 4.dp),
        )
    } else {
        TextButton(onClick = onClick, modifier = modifier) {
            Text(text = label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

/** 状态标签（颜色区分：已完成为主色，进行中为次要色，其余中性） */@Composable
fun StatusChip(status: HomeworkStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        HomeworkStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        HomeworkStatus.IN_PROGRESS -> MaterialTheme.colorScheme.secondary
        HomeworkStatus.PENDING -> MaterialTheme.colorScheme.tertiary
        HomeworkStatus.RECORDED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = status.label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier,
    )
}

/** 时间展示：已排定显示「开始 - 预计完成（时长）」，未排定提示待设定 */
internal fun scheduleText(item: HomeworkItem, zoneId: ZoneId): String {
    val start = item.startTime ?: return "尚未设定开始时间"
    val startText = TimeFormatters.formatDateTime(start.toEpochMilli(), zoneId)
    val minutes = item.estimatedMinutes
    if (minutes == null) {
        return "开始：$startText"
    }
    val finish = item.estimatedFinishedAt
    val finishText = finish?.let { TimeFormatters.formatDateTime(it.toEpochMilli(), zoneId) } ?: "-"
    return "开始：$startText　预计完成：$finishText（$minutes 分钟）"
}

/** 删除确认弹窗 */
@Composable
private fun DeleteHomeworkDialog(
    content: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除这条作业？") },
        text = { Text("「${content.ifBlank { "未填写内容" }}」删除后无法恢复，确定继续吗？") },
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
            TextButton(onClick = onDismiss, enabled = !busy) { Text(text = "取消") }
        },
    )
}