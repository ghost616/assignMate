package com.assignmate.app.timer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.timer.data.TimerVoiceGuide
import com.assignmate.app.timer.data.TimerVoiceSettings
import com.assignmate.app.timer.domain.TimerCalculations
import com.assignmate.app.timer.domain.TimerFeedback
import com.assignmate.app.timer.domain.TimerSpeechTexts
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 完成反馈页 ViewModel：随机表扬语 + 完成情况概览（已完成/总数）。
 *
 * 语义：
 * - 表扬语在进入页面时从 [TimerFeedback.PRAISE_TEXTS] 随机取一条并固定（不随重组变化，
 *   避免同一页面每次重组都换一句话）；
 * - 概览口径与清单同源：[com.assignmate.app.homework.data.HomeworkRepository.listHomework]
 *   按学生读取全部作业，已完成数由 [TimerCalculations.completedCount] 计算；
 * - 页面可在「清单全部完成」与「还有未完成项」两种情形进入，故概览同时给出剩余数量。
 *
 * timer-B 附加职责：进入页面后播报一次表扬语（[TimerSpeechTexts.allCompleted]：表扬语 + 完成数量），
 * 播报开关关闭或 TTS 不可用时静默跳过，不影响页面展示。
 */
@HiltViewModel
class TimerCompletionViewModel @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val authRepository: AuthRepository,
    private val voiceGuide: TimerVoiceGuide,
    private val voiceSettings: TimerVoiceSettings,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimerCompletionUiState())
    val uiState: StateFlow<TimerCompletionUiState> = _uiState.asStateFlow()

    /** 页面入口：由路由参数 `timer/completion/{studentId}` 传入（0 表示学生会话取本人） */
    fun start(routeStudentId: Long) {
        if (_uiState.value.started) {
            return
        }
        _uiState.update { it.copy(started = true, praiseText = TimerFeedback.praiseText()) }
        viewModelScope.launch {
            val voiceEnabled = voiceSettings.isEnabled()
            _uiState.update { it.copy(voiceEnabled = voiceEnabled) }
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
            val items = homeworkRepository.listHomework(targetId)
            val praiseText = _uiState.value.praiseText
            val announcedText = TimerSpeechTexts.allCompleted(
                praise = praiseText,
                completedCount = TimerCalculations.completedCount(items),
                totalCount = items.size,
            )
            _uiState.update {
                it.copy(
                    loading = false,
                    studentId = targetId,
                    studentName = authRepository.getStudent(targetId)?.name,
                    totalCount = items.size,
                    completedCount = TimerCalculations.completedCount(items),
                    remainingCount = TimerCalculations.remainingCount(items),
                    announced = true,
                    announcedText = announcedText,
                )
            }
            if (voiceEnabled) {
                voiceGuide.speak(announcedText)
            }
        }
    }
}

/** 完成反馈页状态（单数据流） */
data class TimerCompletionUiState(
    val started: Boolean = false,
    val loading: Boolean = true,
    /** 无有效会话（未登录） */
    val missingSession: Boolean = false,
    /** 家长会话未选定学生 */
    val missingStudent: Boolean = false,
    val studentId: Long? = null,
    val studentName: String? = null,
    /** 进入页面时随机取定的表扬语 */
    val praiseText: String = "",
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val remainingCount: Int = 0,
    /** 语音播报开关（持久化，默认开启） */
    val voiceEnabled: Boolean = true,
    /** 是否已播报表扬语（进入页面播报一次） */
    val announced: Boolean = false,
    /** 本次播报/展示的完整文案（表扬语 + 完成数量，供页面与测试断言） */
    val announcedText: String? = null,
) {

    /** 完成情况概览文案（如「今天完成了 5 / 5 项」） */
    val progressText: String get() = "今天完成了 $completedCount / $totalCount 项"

    /** 清单是否已全部完成（剩余为 0 且确实存在作业） */
    val allCompleted: Boolean get() = !loading && totalCount > 0 && remainingCount == 0

    /** 仍有未完成项时的提示文案 */
    val remainingText: String? get() = if (allCompleted || loading) null else "还有 $remainingCount 项没完成，休息好了再继续吧"
}
