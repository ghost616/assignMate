package com.assignmate.app.timer.service

import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.HomeworkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 到点投递核对规则的单测（[HomeworkReminderVerificationRules]，纯函数）。
 *
 * 语义：
 * - 当天作业（TODAY）：沿用既有口径——只有「待完成」才确认提醒，其余（已删除/已完成/进行中）一律静默；
 * - 阶段作业逐日提醒（带业务自然日）：作业整体未完成且**当天详情未完成**才确认提醒；
 *   **当天已打卡完成**（或阶段整体已完成）→ 静默，不再打扰。
 */
class HomeworkReminderVerificationRulesTest {

    @Test
    fun `当天作业仅待完成时确认提醒`() {
        assertEquals(
            ReminderVerification.ConfirmedPending("语文生字"),
            verify(content = "语文生字", status = HomeworkStatus.PENDING),
        )
        listOf(HomeworkStatus.RECORDED, HomeworkStatus.IN_PROGRESS, HomeworkStatus.COMPLETED).forEach { status ->
            assertEquals(
                "$status 的当天作业不提醒",
                ReminderVerification.Stale,
                verify(content = "语文生字", status = status),
            )
        }
    }

    @Test
    fun `作业不存在一律静默`() {
        assertEquals(ReminderVerification.Stale, verify(content = null, status = null))
        assertEquals(
            "即使库内仍残留状态，作业被删也不提醒",
            ReminderVerification.Stale,
            verify(content = null, status = HomeworkStatus.PENDING),
        )
    }

    @Test
    fun `阶段作业当天未完成时确认提醒`() {
        listOf(HomeworkDayStatus.NOT_STARTED, HomeworkDayStatus.IN_PROGRESS) .forEach { dayStatus ->
            assertEquals(
                "$dayStatus 的当天可完成，应当提醒",
                ReminderVerification.ConfirmedPending("背古诗"),
                verify(content = "背古诗", status = HomeworkStatus.IN_PROGRESS, isStage = true, dayStatus = dayStatus),
            )
        }
        assertEquals(
            "当天尚无详情（还没开始）同样应当提醒",
            ReminderVerification.ConfirmedPending("背古诗"),
            verify(content = "背古诗", status = HomeworkStatus.PENDING, isStage = true, dayStatus = null),
        )
    }

    @Test
    fun `阶段作业当天已完成时静默不再打扰`() {
        assertEquals(
            ReminderVerification.Stale,
            verify(
                content = "背古诗",
                status = HomeworkStatus.IN_PROGRESS,
                isStage = true,
                dayStatus = HomeworkDayStatus.COMPLETED,
            ),
        )
    }

    @Test
    fun `阶段作业整体已完成时静默`() {
        assertEquals(
            ReminderVerification.Stale,
            verify(
                content = "背古诗",
                status = HomeworkStatus.COMPLETED,
                isStage = true,
                dayStatus = HomeworkDayStatus.NOT_STARTED,
            ),
        )
    }

    @Test
    fun `未携带自然日时阶段作业退回作业状态口径`() {
        assertEquals(
            "单次提醒（旧闹钟）不带自然日：按作业状态判定",
            ReminderVerification.ConfirmedPending("背古诗"),
            verify(content = "背古诗", status = HomeworkStatus.PENDING, isStage = true, dayStatus = null, epochDay = null),
        )
        assertEquals(
            ReminderVerification.Stale,
            verify(content = "背古诗", status = HomeworkStatus.IN_PROGRESS, isStage = true, dayStatus = null, epochDay = null),
        )
    }

    // ---- 测试辅助 ----

    private fun verify(
        content: String?,
        status: HomeworkStatus?,
        isStage: Boolean = false,
        dayStatus: HomeworkDayStatus? = null,
        epochDay: Long? = if (isStage) 100L else null,
    ): ReminderVerification = HomeworkReminderVerificationRules.verify(
        content = content,
        status = status,
        isStage = isStage,
        epochDay = epochDay,
        dayStatus = dayStatus,
    )
}
