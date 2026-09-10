package com.assignmate.app.timer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.timer.data.TimerRestStartStore
import com.assignmate.app.timer.data.TimerVoiceGuide
import com.assignmate.app.timer.data.TimerVoiceSettings
import com.assignmate.app.timer.domain.TimerCalculations
import com.assignmate.app.timer.domain.TimerConstants
import com.assignmate.app.timer.domain.TimerFeedback
import com.assignmate.app.timer.domain.TimerSpeechTexts
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 休息页 ViewModel：完成一项作业后的 [TimerConstants.REST_DURATION_MINUTES] 分钟休息倒计时。
 *
 * 语义与边界：
 * - 倒计时起点为「进入休息页的时刻」（由可注入 Clock 提供），每 [TimerConstants.TICK_INTERVAL_MILLIS] 重算剩余；
 * - 剩余归零即 [TimerRestUiState.restFinished]，页面提示「休息结束」并引导查看下一项；
 * - 「跳过休息」是允许的（孩子想接着做），故页面同时提供直接进入下一项的入口；
 * - 休息状态不落库（属页面级阶段，见 [com.assignmate.app.timer.domain.TimerPhase.RESTING]）：
 *   休息时长本身不是需要盘点的执行数据。
 *
 * timer-B 附加职责：休息结束时播报一次语音引导（[TimerSpeechTexts]）——清单里还有下一项就播报下一项内容，
 * 已全部完成则播报表扬语 + 完成数量；播报开关关闭或 TTS 不可用时静默跳过，不影响倒计时与页面流转。
 *
 * 休息起点持久化（评审补充）：起点落盘于 [TimerRestStartStore]（学生 + 作业维度），
 * 进程被回收后重新进入休息页可**幂等重建**——起点仍在休息窗口内则接着原起点倒计时
 * （[TimerRestUiState.restRebuilt] 标记为 true），已过窗口则视为新的休息、以当前时刻重新起算。
 * 已知取舍：窗口内「已播报过」不落盘，进程回收后重建可能重复播报一次（见 spec 记录）。
 */
@HiltViewModel
class TimerRestViewModel @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val authRepository: AuthRepository,
    private val voiceGuide: TimerVoiceGuide,
    private val voiceSettings: TimerVoiceSettings,
    private val restStartStore: TimerRestStartStore,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimerRestUiState())
    val uiState: StateFlow<TimerRestUiState> = _uiState.asStateFlow()

    /**
     * 页面入口：由路由参数 `timer/rest/{studentId}/{homeworkId}` 传入。
     * 幂等：重复调用不会重置倒计时起点（避免重组导致休息时间被无限续期）。
     */
    fun start(routeStudentId: Long, homeworkId: Long) {
        if (_uiState.value.started) {
            return
        }
        _uiState.update { it.copy(started = true) }
        viewModelScope.launch {
            val session = authRepository.currentSession()
            val targetId = resolveTimerStudentId(session, routeStudentId)
            if (targetId == null) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        missingSession = session.role == null,
                        missingStudent = session.role != null,
                    )
                }
                return@launch
            }
            val finishedContent = if (homeworkId > 0L) {
                homeworkRepository.getHomework(homeworkId)?.content
            } else {
                null
            }
            val now = clock.currentTimeMillis()
            // 幂等重建：命中持久化起点（仍在休息窗口内、且不晚于当前时刻）则接着原起点倒计时
            val stored = if (homeworkId > 0L) {
                restStartStore.restStartedAtMillis(targetId, homeworkId)
            } else {
                null
            }
            val rebuilt = stored != null &&
                stored <= now &&
                now - stored <= TimerConstants.REST_RESUME_WINDOW_MILLIS
            val restStartedAt = if (rebuilt) stored!! else now
            if (homeworkId > 0L) {
                restStartStore.markRestStarted(targetId, homeworkId, restStartedAt)
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    studentId = targetId,
                    finishedHomeworkContent = finishedContent,
                    restStartedAtMillis = restStartedAt,
                    remainingMillis = TimerCalculations.restRemainingMillis(restStartedAt, now),
                    restRebuilt = rebuilt,
                    voiceEnabled = voiceSettings.isEnabled(),
                )
            }
            startCountdown()
        }
    }

    /**
     * 离开页面（导航切换/页面销毁）：打断未播完的语音（休息倒计时本身随页面生命周期结束）。
     */
    fun onLeavingPage() {
        voiceGuide.stop()
    }

    /** 倒计时循环：按刷新间隔用「休息开始时刻 + 时长 - 现在」重算，归零即结束并播报一次 */
    private fun startCountdown() {
        viewModelScope.launch {
            while (true) {
                val state = _uiState.value
                val startedAt = state.restStartedAtMillis ?: return@launch
                val remaining = TimerCalculations.restRemainingMillis(
                    restStartedAtMillis = startedAt,
                    nowMillis = clock.currentTimeMillis(),
                )
                val finished = remaining == 0L
                if (finished && !state.restAnnounced) {
                    // 先置位再播报：播报内部有挂起点，避免下一轮循环重复播报
                    _uiState.update {
                        it.copy(remainingMillis = remaining, restFinished = true, restAnnounced = true)
                    }
                    announceRestFinished()
                } else {
                    _uiState.update { it.copy(remainingMillis = remaining, restFinished = finished) }
                }
                delay(TimerConstants.TICK_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * 休息结束的语音引导（只播报一次）：
     * - 清单里还有下一项 → 播报「休息结束啦，下一项是 XXX」；
     * - 已全部完成 → 播报表扬语 + 完成数量（与完成反馈页同一语料）。
     * 播报开关关闭时只更新状态不发声；TTS 不可用由 [TimerVoiceGuide] 静默降级。
     */
    private suspend fun announceRestFinished() {
        val state = _uiState.value
        val studentId = state.studentId ?: return
        val items = homeworkRepository.listHomework(studentId)
        val next = TimerCalculations.pickNextItem(items)
        val text = if (next != null) {
            TimerSpeechTexts.restFinishedNextItem(next.content)
        } else {
            TimerSpeechTexts.restFinishedAllCompleted(
                praise = TimerFeedback.praiseText(),
                completedCount = TimerCalculations.completedCount(items),
                totalCount = items.size,
            )
        }
        _uiState.update { it.copy(nextItemContent = next?.content, announcedText = text) }
        if (state.voiceEnabled) {
            voiceGuide.speak(text)
        }
    }
}

/** 休息页状态（单数据流） */
data class TimerRestUiState(
    val started: Boolean = false,
    val loading: Boolean = true,
    /** 无有效会话（未登录） */
    val missingSession: Boolean = false,
    /** 家长会话未选定学生 */
    val missingStudent: Boolean = false,
    val studentId: Long? = null,
    /** 刚完成作业的内容（展示「已完成 XXX，休息一下」） */
    val finishedHomeworkContent: String? = null,
    /** 休息开始时刻；未进入倒计时为 null */
    val restStartedAtMillis: Long? = null,
    /** 休息开始时起算的剩余毫秒（归零即结束；进程回收后按持久化起点重建） */
    val remainingMillis: Long = TimerConstants.REST_DURATION_MILLIS,
    /** 本次休息起点是否由持久化记录重建（true = 进程回收后接着原起点倒计时） */
    val restRebuilt: Boolean = false,
    /** 休息是否已结束 */
    val restFinished: Boolean = false,
    /** 本次休息是否已播报结束引导（页面内去重，保证只播一次） */
    val restAnnounced: Boolean = false,
    /** 休息结束时播报/展示的文案（供页面与测试断言） */
    val announcedText: String? = null,
    /** 休息结束后引导的下一项作业内容（全部完成时为 null） */
    val nextItemContent: String? = null,
    /** 语音播报开关（持久化，默认开启） */
    val voiceEnabled: Boolean = true,
) {

    /** 休息总时长（分钟），用于文案「休息 10 分钟」 */
    val totalMinutes: Int get() = TimerConstants.REST_DURATION_MINUTES

    /** 剩余展示秒数（向上取整，归零时为 0） */
    val remainingSeconds: Long get() = TimerCalculations.displaySeconds(remainingMillis)

    /** 家长未选定学生时页面提示用 */
    val missingStudentHint: Boolean get() = !loading && missingStudent
}
