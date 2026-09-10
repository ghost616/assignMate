package com.assignmate.app.homework.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidationException
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageRange
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
 * 手动录入 / 编辑作业模板 ViewModel。
 *
 * 录入规则（与仓库同源，提交前先本地校验给出即时提示，仓库层再兜底）：
 * - 内容必填（家长录入）；阶段作业必须选阶段范围；家长录入的阶段作业必须设截止时间；
 * - 阶段范围的最后一天不得晚于截止时间所在日；
 * - 阶段作业自今天起逐日展开，每天一条作业项（逐日独立完成）。
 *
 * 编辑模式（[HomeworkTemplateUiState.homeworkId] > 0）：内容两种角色均可改（学生仅限自己录入项）；
 * 类型/阶段范围/截止时间为家长专属（见 [HomeworkTemplateUiState.templateEditable]），学生会话下保持原值。
 */
@HiltViewModel
class HomeworkTemplateViewModel @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeworkTemplateUiState())
    val uiState: StateFlow<HomeworkTemplateUiState> = _uiState.asStateFlow()

    private val _events = Channel<HomeworkTemplateEvent>(Channel.BUFFERED)
    val events: Flow<HomeworkTemplateEvent> = _events.receiveAsFlow()

    /** 初始化：读取会话与（可选）待编辑作业，准备表单初始值 */
    fun start(studentId: Long, homeworkId: Long = HomeworkConstants.INVALID_ID) {
        if (_uiState.value.initialized) {
            return
        }
        val todayEpochDay = currentEpochDay()
        _uiState.update { it.copy(initialized = true, todayEpochDay = todayEpochDay) }
        viewModelScope.launch {
            val session = authRepository.currentSession()
            val role = session.role
            val targetId = studentId.takeIf { it > 0L } ?: session.studentId ?: session.parentId
            if (role == null || targetId == null) {
                _uiState.update { it.copy(loading = false, missingSession = true) }
                return@launch
            }
            // 学生会话只可操作本人作业（编辑他人作业直接按无权限处理，仓库层亦兜底）
            if (session.isStudent && targetId != studentId && studentId > 0L) {
                _uiState.update { it.copy(loading = false, missingSession = true) }
                return@launch
            }
            val editing = homeworkId > 0L
            val item = if (editing) homeworkRepository.getHomework(homeworkId) else null
            if (editing && item == null) {
                _uiState.update { it.copy(loading = false, missingSession = true) }
                sendMessage("作业不存在，可能已被删除")
                return@launch
            }
            _uiState.update { state ->
                state.copy(
                    loading = false,
                    studentId = targetId,
                    studentName = authRepository.getStudent(targetId)?.name,
                    role = role,
                    editing = editing,
                    homeworkId = if (editing) homeworkId else HomeworkConstants.INVALID_ID,
                    content = item?.content ?: "",
                    originalContent = item?.content ?: "",
                    type = item?.type ?: HomeworkType.TODAY,
                    stageRange = item?.stageRange,
                    deadlineDate = item?.deadline?.let { instantToDateText(it, zoneId) } ?: "",
                    deadlineTime = item?.deadline?.let { instantToTimeText(it, zoneId) } ?: "",
                )
            }
        }
    }

    // ---- 表单编辑 ----

    fun onContentChange(value: String) {
        _uiState.update { it.copy(content = value, contentError = null, formError = null) }
    }

    fun onTypeChange(type: HomeworkType) {
        _uiState.update {
            it.copy(
                type = type,
                stageRange = if (type == HomeworkType.STAGE) it.stageRange else null,
                contentError = null,
                stageError = null,
                deadlineError = null,
            )
        }
    }

    fun onStageRangeChange(range: StageRange) {
        _uiState.update { it.copy(stageRange = range, stageError = null, deadlineError = null) }
    }

    fun onDeadlineDateChange(value: String) {
        _uiState.update { it.copy(deadlineDate = value, deadlineError = null) }
    }

    fun onDeadlineTimeChange(value: String) {
        _uiState.update { it.copy(deadlineTime = value, deadlineError = null) }
    }

    // ---- 提交 ----

    fun onSubmit() {
        val state = _uiState.value
        if (state.loading || state.submitting || state.missingSession) {
            return
        }
        val role = state.role ?: return
        val studentId = state.studentId ?: return
        val creatorRole = CreatorRole.fromSessionRole(role)
        val trimmed = state.content.trim()
        // 内容必填：家长录入一律必填；编辑既有作业时内容不可改空（仓库 updateContent 同口径）
        if ((creatorRole == CreatorRole.PARENT || state.editing) && trimmed.isEmpty()) {
            _uiState.update { it.copy(contentError = BLANK_CONTENT_HINT) }
            return
        }
        if (trimmed.length > HomeworkConstants.MAX_CONTENT_LENGTH) {
            _uiState.update { it.copy(contentError = CONTENT_TOO_LONG_HINT) }
            return
        }
        if (state.type == HomeworkType.STAGE && state.stageRange == null) {
            _uiState.update { it.copy(stageError = "请选择阶段范围") }
            return
        }
        val deadline = resolveDeadline(state)
        if (deadline is DeadlineParse.Failed) {
            _uiState.update { it.copy(deadlineError = deadline.message) }
            return
        }
        val deadlineInstant = (deadline as? DeadlineParse.Parsed)?.instant
        if (state.type == HomeworkType.STAGE &&
            creatorRole == CreatorRole.PARENT &&
            deadlineInstant == null
        ) {
            _uiState.update { it.copy(deadlineError = "家长录入的阶段作业需要设置截止时间") }
            return
        }
        _uiState.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            if (state.editing) {
                submitEdit(state, trimmed, deadlineInstant, role)
            } else {
                submitNew(trimmed, state, studentId, creatorRole, deadlineInstant)
            }
        }
    }

    /**
     * 编辑既有作业：先改内容（两种角色均可，学生仅限自己录入项），再改类型/阶段范围/截止时间
     * （家长专属——学生会话下这三项保持原值不动，仓库层亦会拒绝学生调用）。
     */
    private suspend fun submitEdit(
        state: HomeworkTemplateUiState,
        content: String,
        deadline: Instant?,
        role: Role,
    ) {
        if (content != state.originalContent) {
            when (val contentResult = homeworkRepository.updateContent(state.homeworkId, content, role)) {
                is HomeworkOperationResult.Success -> {
                    _uiState.update { it.copy(originalContent = contentResult.item.content) }
                }

                is HomeworkOperationResult.ContentInvalid -> {
                    _uiState.update {
                        it.copy(submitting = false, contentError = contentResult.error.toUserMessage())
                    }
                    return
                }

                else -> {
                    _uiState.update { it.copy(submitting = false) }
                    _events.send(HomeworkTemplateEvent.ShowMessage(contentResult.toUserMessage("已保存")))
                    return
                }
            }
        }
        if (!state.deadlineEditable) {
            _uiState.update { it.copy(submitting = false) }
            _events.send(HomeworkTemplateEvent.Saved)
            return
        }
        val result = homeworkRepository.updateTemplate(
            homeworkId = state.homeworkId,
            type = state.type,
            stageRange = state.stageRange,
            deadline = deadline,
            sessionRole = role,
        )
        _uiState.update { it.copy(submitting = false) }
        when (result) {
            is HomeworkOperationResult.Success -> _events.send(HomeworkTemplateEvent.Saved)
            is HomeworkOperationResult.TemplateInvalid ->
                _uiState.update { it.copy(formError = result.error.toUserMessage()) }

            else -> _events.send(HomeworkTemplateEvent.ShowMessage(result.toUserMessage("已保存")))
        }
    }

    private suspend fun submitNew(
        content: String,
        state: HomeworkTemplateUiState,
        studentId: Long,
        creatorRole: CreatorRole,
        deadline: Instant?,
    ) {
        val template = HomeworkTemplate(
            content = content,
            type = state.type,
            stageRange = if (state.type == HomeworkType.STAGE) state.stageRange else null,
            deadline = deadline,
            creatorRole = creatorRole,
            startEpochDay = state.todayEpochDay,
            zoneId = zoneId,
        )
        // 提交前本地预校验：与仓库同源，先给出即时提示
        val localCheck = HomeworkValidators.validateTemplate(template)
        if (localCheck.isFailure) {
            _uiState.update {
                it.copy(submitting = false, formError = localCheck.exceptionOrNull().toMessage())
            }
            return
        }
        val result = homeworkRepository.addHomework(template, studentId)
        _uiState.update { it.copy(submitting = false) }
        when (result) {
            is AddHomeworkResult.Success -> _events.send(
                HomeworkTemplateEvent.SavedWithMessage("已添加 ${result.items.size} 项作业"),
            )

            is AddHomeworkResult.TemplateInvalid ->
                _uiState.update { it.copy(formError = result.error.toUserMessage()) }

            AddHomeworkResult.NoActiveSession ->
                _events.send(HomeworkTemplateEvent.ShowMessage(result.toUserMessage()))
        }
    }

    // ---- 私有工具 ----

    /** 解析截止时间输入（日期 + 时间；均空表示未设置） */
    private fun resolveDeadline(state: HomeworkTemplateUiState): DeadlineParse {
        val dateText = state.deadlineDate.trim()
        val timeText = state.deadlineTime.trim()
        if (dateText.isEmpty() && timeText.isEmpty()) {
            return DeadlineParse.Parsed(null)
        }
        if (dateText.isEmpty()) {
            return DeadlineParse.Failed("请填写截止日期，例如 2025-01-02")
        }
        val date = runCatching { LocalDate.parse(dateText, DATE_FORMAT) }.getOrNull()
            ?: return DeadlineParse.Failed("截止日期格式应为 yyyy-MM-dd")
        val time = if (timeText.isEmpty()) {
            LocalTime.of(DEFAULT_DEADLINE_HOUR, 0)
        } else {
            runCatching { LocalTime.parse(timeText, TIME_FORMAT) }.getOrNull()
                ?: return DeadlineParse.Failed("截止时间格式应为 HH:mm")
        }
        return DeadlineParse.Parsed(date.atTime(time).atZone(zoneId).toInstant())
    }

    private fun sendMessage(message: String) {
        viewModelScope.launch { _events.send(HomeworkTemplateEvent.ShowMessage(message)) }
    }

    private fun currentEpochDay(): Long =
        HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zoneId)

    private fun Throwable?.toMessage(): String =
        (this as? HomeworkValidationException)
            ?.error
            ?.toUserMessage()
            ?: "提交失败，请重试"

    /** 截止时间解析结果 */
    private sealed interface DeadlineParse {

        data class Parsed(val instant: Instant?) : DeadlineParse

        data class Failed(val message: String) : DeadlineParse
    }

    private companion object {

        const val BLANK_CONTENT_HINT = "请填写作业内容"
        const val CONTENT_TOO_LONG_HINT = "作业内容过长，请精简后再提交"

        /** 未填截止时刻时的默认时刻（当天结束前的兜底，避免出现 00:00 语义歧义） */
        const val DEFAULT_DEADLINE_HOUR = 21

        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** 手动录入 / 编辑模板页状态（单数据流） */
data class HomeworkTemplateUiState(
    val initialized: Boolean = false,
    val loading: Boolean = true,
    val missingSession: Boolean = false,
    val submitting: Boolean = false,
    val studentId: Long? = null,
    val studentName: String? = null,
    val role: Role? = null,
    val todayEpochDay: Long = 0L,
    val editing: Boolean = false,
    val homeworkId: Long = HomeworkConstants.INVALID_ID,
    val content: String = "",
    val originalContent: String = "",
    val type: HomeworkType = HomeworkType.TODAY,
    val stageRange: StageRange? = null,
    val deadlineDate: String = "",
    val deadlineTime: String = "",
    val contentError: String? = null,
    val stageError: String? = null,
    val deadlineError: String? = null,
    val formError: String? = null,
) {

    /** 预计覆盖的最后一天（阶段作业按所选范围推算，便于页面提示） */
    val lastEpochDay: Long get() = stageRange?.lastEpochDay(todayEpochDay) ?: todayEpochDay

    /**
     * 截止时间是否可编辑：新建时两种角色皆可设定；
     * 编辑既有作业时仅家长可改（学生编辑只改内容，仓库 updateTemplate/权限规则亦拒绝学生改类型与 deadline）。
     */
    val deadlineEditable: Boolean get() = !editing || role == Role.PARENT

    /** 提交按钮可用条件（避免重复提交） */
    val canSubmit: Boolean get() = !loading && !submitting && !missingSession
}

/** 手动录入 / 编辑模板页一次性事件 */
sealed interface HomeworkTemplateEvent {

    /** 保存成功（新建或编辑）：返回上一页 */
    data object Saved : HomeworkTemplateEvent

    /** 保存成功并携带提示（建立新作业项时告知条数） */
    data class SavedWithMessage(val message: String) : HomeworkTemplateEvent

    /** 一次性提示 */
    data class ShowMessage(val message: String) : HomeworkTemplateEvent
}