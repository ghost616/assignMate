package com.assignmate.app.timer.ui

import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.TimerCompleteResult
import com.assignmate.app.timer.data.TimerPauseResult
import com.assignmate.app.timer.data.TimerResumeResult
import com.assignmate.app.timer.data.TimerStartResult
import com.assignmate.app.timer.domain.TimerPhase

/**
 * timer 模块结果 -> 用户可读文案的集中映射（UI 层唯一出口，仓库层只给机器可读原因）。
 *
 * 文案风格与儿童用户一致：鼓励为主、指令明确；失败提示说明「下一步怎么做」而非只报错。
 */
object TimerErrorMessages {

    /** 会话失效统一提示 */
    const val NO_ACTIVE_SESSION = "登录状态已失效，请重新进入"

    /** 执行权不足统一提示 */
    const val PERMISSION_DENIED = "这项作业不是你负责的，暂时不能计时哦"

    /**
     * 会话操作被归属围栏拒绝的统一提示（暂停/恢复/完成的 [PermissionDenied]）。
     *
     * 覆盖「无有效会话（未登录）」与「该次计时不属于当前身份（跨学生/跨家长越权）」两种原因：
     * 两者对用户的下一步都是「退出后重新进入」，故不细分（细分还会泄露他人会话是否存在）。
     */
    const val SESSION_PERMISSION_DENIED = "当前身份不能操作这次计时，请重新进入后再试"

    /** 开始计时结果文案 */
    fun startMessage(result: TimerStartResult): String = when (result) {
        is TimerStartResult.Success -> "开始计时啦，专心做这一项吧！"
        TimerStartResult.HomeworkNotFound -> "作业不存在，可能已被删除"
        TimerStartResult.NoActiveSession -> NO_ACTIVE_SESSION
        TimerStartResult.PermissionDenied -> PERMISSION_DENIED
        is TimerStartResult.NotStartable -> notStartableMessage(result.status)
        is TimerStartResult.HomeworkSyncFailed -> "作业状态更新失败，请稍后再试"
    }

    /** 暂停结果文案 */
    fun pauseMessage(result: TimerPauseResult): String = when (result) {
        is TimerPauseResult.Success -> "已暂停，计时先停在这里，记得回来哦"
        TimerPauseResult.SessionNotFound -> "计时记录不存在，请重新开始"
        TimerPauseResult.PermissionDenied -> SESSION_PERMISSION_DENIED
        is TimerPauseResult.IllegalPhase -> illegalPhaseMessage(result.phase, "暂停")
    }

    /** 恢复结果文案 */
    fun resumeMessage(result: TimerResumeResult): String = when (result) {
        is TimerResumeResult.Success -> "欢迎回来，接着做吧！"
        TimerResumeResult.SessionNotFound -> "计时记录不存在，请重新开始"
        TimerResumeResult.PermissionDenied -> SESSION_PERMISSION_DENIED
        is TimerResumeResult.IllegalPhase -> illegalPhaseMessage(result.phase, "继续")
        TimerResumeResult.PauseRecordMissing -> "暂停记录不完整，请点「完成作业」结束本次计时"
    }

    /** 完成结果文案 */
    fun completeMessage(result: TimerCompleteResult): String = when (result) {
        is TimerCompleteResult.Success -> "完成啦，先去休息一下吧！"
        TimerCompleteResult.SessionNotFound -> "计时记录不存在，请重新开始"
        TimerCompleteResult.PermissionDenied -> SESSION_PERMISSION_DENIED
        is TimerCompleteResult.IllegalPhase -> illegalPhaseMessage(result.phase, "完成")
        is TimerCompleteResult.HomeworkSyncFailed -> "作业状态更新失败，计时未结束，请稍后再试"
    }

    /** 作业状态不可开始时按状态给出「下一步」指引 */
    private fun notStartableMessage(status: HomeworkStatus): String = when (status) {
        HomeworkStatus.RECORDED -> "这项作业还没设定开始时间，请先在清单里设定"
        HomeworkStatus.COMPLETED -> "这项作业已经完成啦，如需重做请先在清单里撤销完成"
        HomeworkStatus.PENDING, HomeworkStatus.IN_PROGRESS -> "当前状态不支持开始计时"
    }

    /** 阶段不合法时按当前阶段给出可读原因 */
    private fun illegalPhaseMessage(phase: TimerPhase, action: String): String = when (phase) {
        TimerPhase.IDLE -> "还没有开始计时，不能${action}"
        TimerPhase.RUNNING -> "计时进行中，不能${action}"
        TimerPhase.PAUSED -> "计时已暂停，不能${action}"
        TimerPhase.FINISHED -> "这项作业的计时已经结束了"
        TimerPhase.RESTING -> "正在休息中，不能${action}"
    }
}
