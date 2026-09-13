package com.assignmate.app.timer.ui

import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.TimerCompleteResult
import com.assignmate.app.timer.data.TimerPauseResult
import com.assignmate.app.timer.data.TimerResumeResult
import com.assignmate.app.timer.data.TimerStartResult
import com.assignmate.app.timer.data.timerTestHomework
import com.assignmate.app.timer.domain.TimerPhase
import com.assignmate.app.timer.domain.TimerSession
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结果 -> 用户文案映射单测：四类结果（开始/暂停/恢复/完成）的全部错误分支都必须有可读文案，
 * 不得出现空串、占位符残留或「null」字样；不可开始状态与非法阶段的文案按分支给出「下一步」指引。
 *
 * 覆盖测试说明第 9 项「结果文案映射（TimerErrorMessages）：四类结果与各错误分支均可映射为可读文案、无遗漏分支」。
 */
class TimerErrorMessagesTest {

    // ---- 开始计时 ----

    @Test
    fun `开始计时成功文案为鼓励性提示`() {
        assertEquals(
            "开始计时啦，专心做这一项吧！",
            TimerErrorMessages.startMessage(TimerStartResult.Success(session())),
        )
    }

    @Test
    fun `开始计时各错误分支均有专属可读文案`() {
        assertEquals(
            "作业不存在，可能已被删除",
            TimerErrorMessages.startMessage(TimerStartResult.HomeworkNotFound),
        )
        assertEquals(
            TimerErrorMessages.NO_ACTIVE_SESSION,
            TimerErrorMessages.startMessage(TimerStartResult.NoActiveSession),
        )
        assertEquals(
            TimerErrorMessages.PERMISSION_DENIED,
            TimerErrorMessages.startMessage(TimerStartResult.PermissionDenied),
        )
        assertEquals(
            "作业状态更新失败，请稍后再试",
            TimerErrorMessages.startMessage(
                TimerStartResult.HomeworkSyncFailed(HomeworkStatusResult.NotFound),
            ),
        )
    }

    @Test
    fun `不可开始的作业按状态给出下一步指引`() {
        assertEquals(
            "这项作业还没设定开始时间，请先在清单里设定",
            TimerErrorMessages.startMessage(TimerStartResult.NotStartable(HomeworkStatus.RECORDED)),
        )
        assertEquals(
            "这项作业已经完成啦，如需重做请先在清单里撤销完成",
            TimerErrorMessages.startMessage(TimerStartResult.NotStartable(HomeworkStatus.COMPLETED)),
        )
        assertEquals(
            "当前状态不支持开始计时",
            TimerErrorMessages.startMessage(TimerStartResult.NotStartable(HomeworkStatus.PENDING)),
        )
        assertEquals(
            "当前状态不支持开始计时",
            TimerErrorMessages.startMessage(TimerStartResult.NotStartable(HomeworkStatus.IN_PROGRESS)),
        )
    }

    // ---- 暂停 ----

    @Test
    fun `暂停成功文案提醒回来继续`() {
        assertEquals(
            "已暂停，计时先停在这里，记得回来哦",
            TimerErrorMessages.pauseMessage(TimerPauseResult.Success(session())),
        )
    }

    @Test
    fun `暂停的会话不存在与非法阶段分支文案`() {
        assertEquals("计时记录不存在，请重新开始", TimerErrorMessages.pauseMessage(TimerPauseResult.SessionNotFound))
        assertEquals(
            TimerErrorMessages.SESSION_PERMISSION_DENIED,
            TimerErrorMessages.pauseMessage(TimerPauseResult.PermissionDenied),
        )
        assertEquals(
            "还没有开始计时，不能暂停",
            TimerErrorMessages.pauseMessage(TimerPauseResult.IllegalPhase(TimerPhase.IDLE)),
        )
        assertEquals(
            "计时进行中，不能暂停",
            TimerErrorMessages.pauseMessage(TimerPauseResult.IllegalPhase(TimerPhase.RUNNING)),
        )
        assertEquals(
            "计时已暂停，不能暂停",
            TimerErrorMessages.pauseMessage(TimerPauseResult.IllegalPhase(TimerPhase.PAUSED)),
        )
        assertEquals(
            "这项作业的计时已经结束了",
            TimerErrorMessages.pauseMessage(TimerPauseResult.IllegalPhase(TimerPhase.FINISHED)),
        )
        assertEquals(
            "正在休息中，不能暂停",
            TimerErrorMessages.pauseMessage(TimerPauseResult.IllegalPhase(TimerPhase.RESTING)),
        )
    }

    // ---- 恢复 ----

    @Test
    fun `恢复成功文案鼓励继续`() {
        assertEquals("欢迎回来，接着做吧！", TimerErrorMessages.resumeMessage(TimerResumeResult.Success(session())))
    }

    @Test
    fun `恢复的会话不存在明细缺失与非法阶段分支文案`() {
        assertEquals(
            "计时记录不存在，请重新开始",
            TimerErrorMessages.resumeMessage(TimerResumeResult.SessionNotFound),
        )
        assertEquals(
            TimerErrorMessages.SESSION_PERMISSION_DENIED,
            TimerErrorMessages.resumeMessage(TimerResumeResult.PermissionDenied),
        )
        assertEquals(
            "暂停记录不完整，请点「完成作业」结束本次计时",
            TimerErrorMessages.resumeMessage(TimerResumeResult.PauseRecordMissing),
        )
        assertEquals(
            "计时进行中，不能继续",
            TimerErrorMessages.resumeMessage(TimerResumeResult.IllegalPhase(TimerPhase.RUNNING)),
        )
        assertEquals(
            "这项作业的计时已经结束了",
            TimerErrorMessages.resumeMessage(TimerResumeResult.IllegalPhase(TimerPhase.FINISHED)),
        )
    }

    // ---- 完成 ----

    @Test
    fun `完成成功文案引导去休息`() {
        assertEquals(
            "完成啦，先去休息一下吧！",
            TimerErrorMessages.completeMessage(
                TimerCompleteResult.Success(
                    session = session(),
                    homework = timerTestHomework(id = 1L, status = HomeworkStatus.COMPLETED),
                ),
            ),
        )
    }

    @Test
    fun `完成的会话不存在同步失败与非法阶段分支文案`() {
        assertEquals(
            "计时记录不存在，请重新开始",
            TimerErrorMessages.completeMessage(TimerCompleteResult.SessionNotFound),
        )
        assertEquals(
            TimerErrorMessages.SESSION_PERMISSION_DENIED,
            TimerErrorMessages.completeMessage(TimerCompleteResult.PermissionDenied),
        )
        assertEquals(
            "作业状态更新失败，计时未结束，请稍后再试",
            TimerErrorMessages.completeMessage(
                TimerCompleteResult.HomeworkSyncFailed(
                    HomeworkStatusResult.IllegalTransition(HomeworkStatus.PENDING, HomeworkStatus.COMPLETED),
                ),
            ),
        )
        assertEquals(
            "还没有开始计时，不能完成",
            TimerErrorMessages.completeMessage(TimerCompleteResult.IllegalPhase(TimerPhase.IDLE)),
        )
        assertEquals(
            "计时已暂停，不能完成",
            TimerErrorMessages.completeMessage(TimerCompleteResult.IllegalPhase(TimerPhase.PAUSED)),
        )
        assertEquals(
            "这项作业的计时已经结束了",
            TimerErrorMessages.completeMessage(TimerCompleteResult.IllegalPhase(TimerPhase.FINISHED)),
        )
        assertEquals(
            "正在休息中，不能完成",
            TimerErrorMessages.completeMessage(TimerCompleteResult.IllegalPhase(TimerPhase.RESTING)),
        )
    }

    // ---- 无遗漏分支 / 文案质量 ----

    @Test
    fun `四类结果的每个分支文案都非空白且无占位残留`() {
        val messages = buildList {
            add(TimerErrorMessages.startMessage(TimerStartResult.Success(session())))
            add(TimerErrorMessages.startMessage(TimerStartResult.HomeworkNotFound))
            add(TimerErrorMessages.startMessage(TimerStartResult.NoActiveSession))
            add(TimerErrorMessages.startMessage(TimerStartResult.PermissionDenied))
            HomeworkStatus.entries.forEach {
                add(TimerErrorMessages.startMessage(TimerStartResult.NotStartable(it)))
            }
            add(TimerErrorMessages.startMessage(TimerStartResult.HomeworkSyncFailed(HomeworkStatusResult.NotFound)))
            add(
                TimerErrorMessages.startMessage(
                    TimerStartResult.HomeworkSyncFailed(HomeworkStatusResult.PermissionDenied),
                ),
            )
            add(
                TimerErrorMessages.startMessage(
                    TimerStartResult.HomeworkSyncFailed(
                        HomeworkStatusResult.IllegalTransition(HomeworkStatus.PENDING, HomeworkStatus.IN_PROGRESS),
                    ),
                ),
            )
            add(TimerErrorMessages.pauseMessage(TimerPauseResult.Success(session())))
            add(TimerErrorMessages.pauseMessage(TimerPauseResult.SessionNotFound))
            add(TimerErrorMessages.pauseMessage(TimerPauseResult.PermissionDenied))
            add(TimerErrorMessages.resumeMessage(TimerResumeResult.Success(session())))
            add(TimerErrorMessages.resumeMessage(TimerResumeResult.SessionNotFound))
            add(TimerErrorMessages.resumeMessage(TimerResumeResult.PermissionDenied))
            add(TimerErrorMessages.resumeMessage(TimerResumeResult.PauseRecordMissing))
            add(
                TimerErrorMessages.completeMessage(
                    TimerCompleteResult.Success(session(), timerTestHomework(id = 1L)),
                ),
            )
            add(TimerErrorMessages.completeMessage(TimerCompleteResult.SessionNotFound))
            add(TimerErrorMessages.completeMessage(TimerCompleteResult.PermissionDenied))
            add(
                TimerErrorMessages.completeMessage(
                    TimerCompleteResult.HomeworkSyncFailed(HomeworkStatusResult.NotFound),
                ),
            )
            // 非法阶段：三个动作 × 全部阶段，确保 when 分支穷尽且文案可读
            TimerPhase.entries.forEach { phase ->
                add(TimerErrorMessages.pauseMessage(TimerPauseResult.IllegalPhase(phase)))
                add(TimerErrorMessages.resumeMessage(TimerResumeResult.IllegalPhase(phase)))
                add(TimerErrorMessages.completeMessage(TimerCompleteResult.IllegalPhase(phase)))
            }
        }

        messages.forEach { message ->
            assertTrue("文案不应为空白: '$message'", message.isNotBlank())
            assertFalse("文案不应残留占位符: '$message'", message.contains("{"))
            assertFalse("文案不应出现 null: '$message'", message.contains("null", ignoreCase = true))
        }
        // 未收尾阶段（IDLE/RUNNING/PAUSED/RESTING）的文案必须带上被拒绝的动作名，
        // 避免「同一条文案对应多个动作」的歧义；FINISHED 为统一终态文案（已结束，无需再区分动作）
        val activePhases = TimerPhase.entries.filter { it != TimerPhase.FINISHED }
        activePhases.forEach { phase ->
            assertTrue(TimerErrorMessages.pauseMessage(TimerPauseResult.IllegalPhase(phase)).contains("暂停"))
            assertTrue(TimerErrorMessages.resumeMessage(TimerResumeResult.IllegalPhase(phase)).contains("继续"))
            assertTrue(TimerErrorMessages.completeMessage(TimerCompleteResult.IllegalPhase(phase)).contains("完成"))
        }
        val finishedMessages = listOf(
            TimerErrorMessages.pauseMessage(TimerPauseResult.IllegalPhase(TimerPhase.FINISHED)),
            TimerErrorMessages.resumeMessage(TimerResumeResult.IllegalPhase(TimerPhase.FINISHED)),
            TimerErrorMessages.completeMessage(TimerCompleteResult.IllegalPhase(TimerPhase.FINISHED)),
        )
        assertEquals(1, finishedMessages.toSet().size)
        assertTrue(finishedMessages.first().contains("已经结束"))
    }

    @Test
    fun `非法阶段的文案随阶段不同而不同`() {
        val pauseMessages = TimerPhase.entries.map {
            TimerErrorMessages.pauseMessage(TimerPauseResult.IllegalPhase(it))
        }

        assertEquals(TimerPhase.entries.size, pauseMessages.toSet().size)
    }

    @Test
    fun `会话失效与权限不足文案可复用常量`() {
        assertTrue(TimerErrorMessages.NO_ACTIVE_SESSION.isNotBlank())
        assertTrue(TimerErrorMessages.PERMISSION_DENIED.isNotBlank())
        assertTrue(TimerErrorMessages.SESSION_PERMISSION_DENIED.isNotBlank())
    }

    /**
     * 归属围栏三类 PermissionDenied 共用同一条文案：暂停/恢复/完成被拒后，
     * 用户下一步都是「重新进入」，不应因动作不同给出互相矛盾的指引。
     */
    @Test
    fun `暂停恢复完成的权限失败文案统一`() {
        val messages = listOf(
            TimerErrorMessages.pauseMessage(TimerPauseResult.PermissionDenied),
            TimerErrorMessages.resumeMessage(TimerResumeResult.PermissionDenied),
            TimerErrorMessages.completeMessage(TimerCompleteResult.PermissionDenied),
        )

        assertEquals(1, messages.toSet().size)
        assertEquals(TimerErrorMessages.SESSION_PERMISSION_DENIED, messages.first())
    }

    private fun session(): TimerSession = TimerSession(
        id = 1L,
        homeworkId = 1L,
        studentId = 2L,
        parentAccountId = 1L,
        startedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        finishedAt = null,
        phase = TimerPhase.RUNNING,
    )
}
