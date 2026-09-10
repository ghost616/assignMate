package com.assignmate.app.homework.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.ScheduleUpdateResult
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkValidation
import com.assignmate.app.homework.domain.HomeworkValidators
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 时间设定 / 编辑页 ViewModel：学生（或家长）为某条作业设定开始时间与预估时长。
 *
 * 提交时先做本地校验（deadline 约束、预估时长合法性）给出即时提示，
 * 再由仓库执行「deadline 约束 + 同学生时间段防冲突（排除自身）」兜底校验；
 * 校验失败原因映射为可读文案展示在表单上，成功后排定结果回传 [TimeSetEvent.Saved]。
 */
@HiltViewModel
class HomeworkTimeSetViewModel @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeworkTimeSetUiState())
    val uiState: StateFlow<HomeworkTimeSetUiState> = _uiState.asStateFlow()

    private val _events = Channel<TimeSetEvent>(Channel.BUFFERED)
    val events: Flow<TimeSetEvent> = _events.receiveAsFlow()

    /** 初始化：读取会话与目标作业，用已有排定值预填表单（默认今天 + 建议时长） */
    fun start(studentId: Long, homeworkId: Long) {
        if (_uiState.value.initialized) {
            return
        }
        _uiState.update { it.copy(initialized = true) }
        viewModelScope.launch {
            val session = authRepository.currentSession()
            val role = session.role
            if (role == null) {
                _uiState.update { it.copy(loading = false, missingSession = true) }
                return@launch
            }
            val item = homeworkRepository.getHomework(homeworkId)
            if (item == null) {
                _uiState.update { it.copy(loading = false, missingSession = true) }
                sendMessage("作业不存在，可能已被删除")
                return@launch
            }
            // 执行权（与仓库同源）：学生可为自己名下（含家长布置的）作业排定时间
            if (!HomeworkValidators.canOperate(item, role, session.studentId)) {
                _uiState.update { it.copy(loading = false, missingSession = true) }
                sendMessage(EXECUTE_PERMISSION_DENIED_HINT)
                return@launch
            }
            val today = LocalDate.ofEpochDay(HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zoneId))
            val startDateTime = item.startTime?.let { it.atZone(zoneId).toLocalDateTime() }
            _uiState.update {
                it.copy(
                    loading = false,
                    studentId = studentId,
                    role = role,
                    item = item,
                    todayEpochDay = today.toEpochDay(),
                    startDate = startDateTime?.toLocalDate()?.format(DATE_FORMAT)
                        ?: today.format(DATE_FORMAT),
                    startTime = startDateTime?.toLocalTime()?.format(TIME_FORMAT)
                        ?: DEFAULT_START_TIME.format(TIME_FORMAT),
                    estimatedMinutes = item.estimatedMinutes?.toString()
                        ?: DEFAULT_ESTIMATED_MINUTES.toString(),
                )
            }
        }
    }

    // ---- 表单编辑 ----

    fun onStartDateChange(value: String) {
        _uiState.update { it.copy(startDate = value, timeError = null) }
    }

    fun onStartTimeChange(value: String) {
        _uiState.update { it.copy(startTime = value, timeError = null) }
    }

    fun onEstimatedMinutesChange(value: String) {
        _uiState.update { it.copy(estimatedMinutes = value, minutesError = null, timeError = null) }
    }

    // ---- 提交 ----

    fun onSubmit() {
        val state = _uiState.value
        if (state.loading || state.submitting || state.missingSession) {
            return
        }
        val item = state.item ?: return
        val role = state.role ?: return
        val startInstant = parseStart(state) ?: run {
            _uiState.update { it.copy(timeError = "请填写正确的开始日期（yyyy-MM-dd）与时间（HH:mm）") }
            return
        }
        val minutes = state.estimatedMinutes.trim().toIntOrNull()
        if (HomeworkValidators.validateEstimatedMinutes(minutes) is HomeworkValidation.Invalid) {
            _uiState.update {
                it.copy(
                    minutesError = "预估时长需要是 ${HomeworkConstants.MIN_ESTIMATED_MINUTES}-" +
                        "${HomeworkConstants.MAX_ESTIMATED_MINUTES} 之间的整数（分钟）",
                )
            }
            return
        }
        val estimated = minutes ?: return
        // 本地预校验：deadline 约束（时间段冲突由仓库查库兜底）
        val deadlineCheck = HomeworkValidators.validateDeadline(
            startMillis = startInstant.toEpochMilli(),
            estimatedMinutes = estimated,
            deadlineMillis = item.deadline?.toEpochMilli(),
        )
        if (deadlineCheck is HomeworkValidation.Invalid) {
            _uiState.update { it.copy(timeError = deadlineCheck.error.toUserMessage()) }
            return
        }
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val result = homeworkRepository.updateSchedule(
                homeworkId = item.id,
                startTime = startInstant,
                estimatedMinutes = estimated,
                sessionRole = role,
            )
            _uiState.update { it.copy(submitting = false) }
            when (result) {
                is ScheduleUpdateResult.Success ->
                    _events.send(TimeSetEvent.Saved(result.toUserMessage()))

                ScheduleUpdateResult.DeadlineExceeded ->
                    _uiState.update { it.copy(timeError = result.toUserMessage()) }

                ScheduleUpdateResult.TimeConflict ->
                    _uiState.update { it.copy(timeError = result.toUserMessage()) }

                ScheduleUpdateResult.InvalidEstimatedMinutes ->
                    _uiState.update { it.copy(minutesError = result.toUserMessage()) }

                else -> _events.send(TimeSetEvent.ShowMessage(result.toUserMessage()))
            }
        }
    }

    // ---- 私有工具 ----

    /** 解析「开始日期 + 开始时间」；格式非法返回 null */
    private fun parseStart(state: HomeworkTimeSetUiState): Instant? {
        val date = runCatching { LocalDate.parse(state.startDate.trim(), DATE_FORMAT) }.getOrNull()
            ?: return null
        val time = runCatching { LocalTime.parse(state.startTime.trim(), TIME_FORMAT) }.getOrNull()
            ?: return null
        return date.atTime(time).atZone(zoneId).toInstant()
    }

    private fun sendMessage(message: String) {
        viewModelScope.launch { _events.send(TimeSetEvent.ShowMessage(message)) }
    }

    private companion object {

        /** 未排定时的默认开始时刻（今天 16:00，符合放学后写作业的习惯） */
        val DEFAULT_START_TIME: LocalTime = LocalTime.of(16, 0)

        /** 未排定时的默认预估时长（分钟） */
        const val DEFAULT_ESTIMATED_MINUTES = 30

        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** 时间设定页状态（单数据流） */
data class HomeworkTimeSetUiState(
    val initialized: Boolean = false,
    val loading: Boolean = true,
    val missingSession: Boolean = false,
    val submitting: Boolean = false,
    val studentId: Long? = null,
    val role: Role? = null,
    val item: HomeworkItem? = null,
    val todayEpochDay: Long = 0L,
    val startDate: String = "",
    val startTime: String = "",
    val estimatedMinutes: String = "",
    val timeError: String? = null,
    val minutesError: String? = null,
) {

    /** 提交按钮可用条件 */
    val canSubmit: Boolean get() = !loading && !submitting && !missingSession
}

/** 时间设定页一次性事件 */
sealed interface TimeSetEvent {

    /** 排定成功：携带提示文案后返回上一页 */
    data class Saved(val message: String) : TimeSetEvent

    /** 一次性提示（不关闭页面） */
    data class ShowMessage(val message: String) : TimeSetEvent
}