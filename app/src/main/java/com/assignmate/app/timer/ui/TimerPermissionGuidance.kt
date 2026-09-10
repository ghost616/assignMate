package com.assignmate.app.timer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assignmate.app.timer.data.TimerPermissionKind
import com.assignmate.app.timer.data.TimerPermissionStatus

/**
 * 一条权限引导（标题/说明/操作按钮文案，纯数据便于单测）。
 *
 * @param permission 需要处理的权限种类，页面据此决定拉起哪种系统交互
 * @param actionLabel 引导按钮文案（如「允许通知」「去设置」）
 */
data class TimerPermissionHint(
    val permission: TimerPermissionKind,
    val title: String,
    val message: String,
    val actionLabel: String,
)

/**
 * 计时/提醒权限引导（纯函数，集中可单测）。
 *
 * 文案原则（与需求一致）：
 * - 说明权限**只影响提醒是否可见/是否准点**，明确「不影响计时」；
 * - 拒绝时不阻断流程，仅给出可手动开启的引导。
 */
object TimerPermissionGuidance {

    /** 通知权限未授权时的引导 */
    val NOTIFICATIONS_HINT = TimerPermissionHint(
        permission = TimerPermissionKind.NOTIFICATIONS,
        title = "开启通知，到点提醒才看得见",
        message = "作业到点会发通知提醒你。没开也不影响计时，之后可在系统设置里打开。",
        actionLabel = "允许通知",
    )

    /** 精确闹钟未授权时的引导（Android 12+；口径与降级实现一致：不精确闹钟的延迟可能远超几分钟） */
    val EXACT_ALARM_HINT = TimerPermissionHint(
        permission = TimerPermissionKind.EXACT_ALARM,
        title = "开启精确提醒，更准点",
        message = "未开启时提醒可能晚一些（有时长达十几分钟）；计时和完成作业都不受影响。",
        actionLabel = "去设置",
    )

    /**
     * 由权限状态推导需要展示的引导列表（已授权项不出现；顺序固定：先通知、后精确闹钟）。
     * 权限状态未知（null）时不做任何引导，避免误提示。
     */
    fun hintsOf(status: TimerPermissionStatus?): List<TimerPermissionHint> {
        if (status == null) {
            return emptyList()
        }
        return buildList {
            if (!status.notificationsGranted) {
                add(NOTIFICATIONS_HINT)
            }
            if (!status.exactAlarmGranted) {
                add(EXACT_ALARM_HINT)
            }
        }
    }
}

/**
 * 权限引导卡片（无状态，可复用于任何计时/提醒相关页面）。
 * 传入空列表时不渲染任何内容。
 */
@Composable
fun TimerPermissionBanner(
    hints: List<TimerPermissionHint>,
    onAction: (TimerPermissionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hints.isEmpty()) {
        return
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            hints.forEach { hint ->
                Text(
                    text = hint.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = hint.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onAction(hint.permission) }) {
                    Text(text = hint.actionLabel)
                }
            }
        }
    }
}
