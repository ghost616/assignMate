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
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidationError
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
 * - 内容必填（家长录入）；阶段作业必须选阶段范围；家长录入的阶段作业必须设**每日截止时刻**；
 * - 阶段作业**固定产出 1 条**作业项（不再逐日展开）：阶段范围只决定覆盖起止日与进度分母 M；
 * - 截止时间两类语义不同：当天作业填「日期 + 时刻」；阶段作业**只填时刻**（每天到这个时刻截止）。
 *
 * 编辑模式（[HomeworkTemplateUiState.homeworkId] > 0）：内容两种角色均可改（学生仅限自己录入项）；
 * 类型/阶段范围/截止时间为家长专属（见 [HomeworkTemplateUiState.templateEditable]），学生会话下保持原值。
 *
 * **作业类型与默认值按会话角色收敛**（需求：家长不再能添加当天作业；添加页与编辑页同口径）：
 * 家长会话可见的类型只有「阶段作业」（[HomeworkTemplateUiState.availableTypes] 由
 * [homeworkTypeOptions] 推导，[onTypeChange] 亦拒绝对不可见类型的选择），新建时默认预填
 * 「阶段作业 + 一周 + 21:00」（[homeworkFormDefaults]，幂等且不覆盖用户已改的选择）；
 * 学生会话保持两项、默认当天作业。编辑既有作业一律以作业自身的取值为准（存量当天作业不做数据兼容）。
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

    /**
     * 用户是否已显式改过表单字段（类型 / 阶段范围 / 每日时刻）。
     *
     * 会话角色在 [start] 中**异步**解析，而「家长只能录阶段作业 + 一周 + 21:00」的默认值收敛
     * 发生在角色到位之后；若不加区分地写回默认值就会覆盖用户已经改过的选择。故收敛时逐字段
     * 比对本标记（见 [homeworkFormDefaults] 的 `appliedTo`），使收敛**幂等且不覆盖用户选择**。
     */
    private var formTouched = HomeworkFormTouched()

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
            // 新建时的角色默认值：家长 = 阶段作业 + 一周 + 21:00（降低必填项操作成本）；
            // 学生会话取到的默认值与既有初始值一致（当天作业 / 空），故学生端行为逐字不变。
            // **编辑一律以既有作业的取值为准**，绝不套用默认值（否则会把用户的存量设置改掉）。
            // 仅对用户尚未改过的字段套用（[formTouched]）→ 本收敛幂等且不覆盖用户已改的选择
            // （角色在 start() 中异步解析，收敛发生在角色到位之后）。
            val defaults = if (editing) {
                null
            } else {
                homeworkFormDefaults(role).appliedTo(
                    type = _uiState.value.type,
                    stageRange = _uiState.value.stageRange,
                    deadlineTime = _uiState.value.deadlineTime,
                    touched = formTouched,
                )
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
                    // 录入者角色随作业读入：编辑时「每日截止时刻是否必填」按**作业的录入者**
                    // 判定（与仓库 updateTemplate / validateTypeChange 同一口径），新建时回落会话角色
                    createdByRole = item?.createdByRole,
                    // 编辑存量作业时类型原样保留（**不做存量当天作业的数据兼容**：家长会话下该类型
                    // 已不在选项列表内，但既有数据不被擅自改写）；新建时取角色默认值
                    type = item?.type ?: defaults?.type ?: HomeworkType.TODAY,
                    stageRange = if (editing) item?.stageRange else defaults?.stageRange,
                    // 阶段作业只填时刻（每日截止时刻，无日期）；当天作业仍为「日期 + 时刻」
                    deadlineDate = if (item != null && item.isStage) {
                        ""
                    } else {
                        item?.deadline?.let { instantToDateText(it, zoneId) } ?: ""
                    },
                    deadlineTime = if (item != null && item.isStage) {
                        item.dailyDeadlineTime?.let { time -> HomeworkDailyDeadlineCodec.formatTime(time) } ?: ""
                    } else if (editing) {
                        item?.deadline?.let { instantToTimeText(it, zoneId) } ?: ""
                    } else {
                        defaults?.deadlineTime ?: ""
                    },
                )
            }
        }
    }

    // ---- 表单编辑 ----

    fun onContentChange(value: String) {
        _uiState.update { it.copy(content = value, contentError = null, formError = null) }
    }

    /**
     * 切换作业类型：只接受当前会话**可见**的类型（家长会话只有「阶段作业」，
     * 见 [homeworkTypeOptions]）——越界选择直接忽略，与页面只渲染 `availableTypes` 同口径。
     */
    fun onTypeChange(type: HomeworkType) {
        if (type !in homeworkTypeOptions(_uiState.value.role)) {
            return
        }
        // 记录「用户已显式改过类型」：角色异步到位后的默认值收敛不得覆盖本次选择
        formTouched = formTouched.copy(type = true)
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
        formTouched = formTouched.copy(stageRange = true)
        _uiState.update { it.copy(stageRange = range, stageError = null, deadlineError = null) }
    }

    fun onDeadlineDateChange(value: String) {
        _uiState.update { it.copy(deadlineDate = value, deadlineError = null) }
    }

    fun onDeadlineTimeChange(value: String) {
        formTouched = formTouched.copy(deadlineTime = true)
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
        // 阶段作业的每日截止时刻**只有「家长录入」时必填**（与仓库 validateTemplate /
        // validateTypeChange 同源）：新建看会话角色，编辑看作业原有录入者；
        // 此处与 resolveDeadline 一起构成唯一判定点，学生录入的阶段作业不再被 UI 先拦下
        if (state.requiresDailyDeadline && deadlineInstant == null) {
            _uiState.update {
                it.copy(deadlineError = HomeworkValidationError.MISSING_STAGE_DEADLINE.toUserMessage())
            }
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
            // 编辑路径：事件回抛被编辑作业的 id（框架据此按 id 精确同步提醒，不再依赖路由参数）
            _events.send(HomeworkTemplateEvent.Saved(homeworkId = state.homeworkId))
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
            is HomeworkOperationResult.Success ->
                _events.send(HomeworkTemplateEvent.Saved(homeworkId = state.homeworkId))
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
                // 新建路径：事件回抛**新建作业项的真实 id**（模板恒定产出 1 条），
                // 使「学生会话未指定学生」的新建场景也能按 id 精确同步逐日提醒
                HomeworkTemplateEvent.SavedWithMessage(
                    message = "已添加 ${result.items.size} 项作业",
                    homeworkId = result.items.firstOrNull()?.id ?: HomeworkConstants.INVALID_ID,
                ),
            )

            is AddHomeworkResult.TemplateInvalid ->
                _uiState.update { it.copy(formError = result.error.toUserMessage()) }

            AddHomeworkResult.NoActiveSession ->
                _events.send(HomeworkTemplateEvent.ShowMessage(result.toUserMessage()))
        }
    }

    // ---- 私有工具 ----

    /**
     * 解析截止时间输入（**按作业类型分两种语义**）：
     * - 阶段作业（STAGE）：**只填时刻**（time-of-day，如 21:00）——语义为「阶段范围内每天到这个时刻截止」，
     *   留空时返回无截止；是否必填由 [onSubmit] 按 `state.requiresDailyDeadline`（= 家长录入）判定，
     *   与 [HomeworkValidators.validateTemplate] / [HomeworkValidators.validateTypeChange] 同源；
     *   返回的 Instant 只是该钟面值的载体，落库时由 [HomeworkTemplate.toItems] 编码进阶段起始日；
     * - 当天作业（TODAY）：沿用「日期 + 时刻」的绝对 deadline 语义，日期缺失时给出格式提示。
     */
    private fun resolveDeadline(state: HomeworkTemplateUiState): DeadlineParse {
        val dateText = state.deadlineDate.trim()
        val timeText = state.deadlineTime.trim()
        if (timeText.isEmpty()) {
            return if (state.type == HomeworkType.STAGE) {
                DeadlineParse.Parsed(null)
            } else if (dateText.isEmpty()) {
                DeadlineParse.Parsed(null)
            } else {
                DeadlineParse.Failed("请填写截止时刻，例如 21:00")
            }
        }
        val time = runCatching { LocalTime.parse(timeText, TIME_FORMAT) }.getOrNull()
            ?: return DeadlineParse.Failed("截止时刻格式应为 HH:mm")
        if (state.type == HomeworkType.STAGE) {
            // 阶段作业：只取时刻，日期不参与（每日到点截止）
            return DeadlineParse.Parsed(HomeworkDailyDeadlineCodec.timeOfDayCarrier(time))
        }
        if (dateText.isEmpty()) {
            return DeadlineParse.Failed("请填写截止日期，例如 2025-01-02")
        }
        val date = runCatching { LocalDate.parse(dateText, DATE_FORMAT) }.getOrNull()
            ?: return DeadlineParse.Failed("截止日期格式应为 yyyy-MM-dd")
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
    /**
     * 编辑既有作业时的**录入者角色**（新建时为 null）：判定「阶段每日截止时刻是否必填」用，
     * 与仓库 [com.assignmate.app.homework.data.HomeworkRepository.updateTemplate] 传入
     * `domain.createdByRole` 的口径一致（作业归属不随编辑会话变化）。
     */
    val createdByRole: CreatorRole? = null,
    val type: HomeworkType = HomeworkType.TODAY,
    val stageRange: StageRange? = null,
    val deadlineDate: String = "",
    val deadlineTime: String = "",
    val contentError: String? = null,
    val stageError: String? = null,
    val deadlineError: String? = null,
    val formError: String? = null,
) {

    /** 阶段覆盖的最后一天（阶段作业按所选范围推算，便于页面提示「覆盖至 X 月 X 日」） */
    val lastEpochDay: Long get() = stageRange?.lastEpochDay(todayEpochDay) ?: todayEpochDay

    /**
     * 当前会话**可见**的作业类型（页面据此渲染类型选项，是唯一的选项来源）：
     * - 家长会话：只有「阶段作业」（需求：去掉家长的当天作业添加；添加页与编辑页同口径）；
     * - 学生会话：两项（当天作业 / 阶段作业），新建默认当天作业（学生端行为不变）。
     *
     * 与 [HomeworkTemplateViewModel.onTypeChange] 的越界拒绝共用 [homeworkTypeOptions]，
     * 与添加页 [HomeworkEntryUiState.availableTypes] 亦同源。
     */
    val availableTypes: List<HomeworkType> get() = homeworkTypeOptions(role)

    /**
     * 截止时间是否可编辑：新建时两种角色皆可设定；
     * 编辑既有作业时仅家长可改（学生编辑只改内容，仓库 updateTemplate/权限规则亦拒绝学生改类型与 deadline）。
     */
    val deadlineEditable: Boolean get() = !editing || role == Role.PARENT

    /**
     * 该模板的**录入者角色**：编辑既有作业时取作业原有录入者（[createdByRole]），
     * 新建时取会话角色推导。用于与仓库同一口径地判定「阶段每日截止时刻是否必填」。
     */
    val ownerRole: CreatorRole? get() = createdByRole ?: role?.let(CreatorRole::fromSessionRole)

    /**
     * 阶段作业的每日截止时刻是否必填：**仅「家长录入」的阶段作业必填**，学生录入的可留空
     * （与 [com.assignmate.app.homework.domain.HomeworkValidators.validateTemplate] /
     * [com.assignmate.app.homework.domain.HomeworkValidators.validateTypeChange] 同源）。
     *
     * 此前录入页对**所有角色**的阶段作业都要求填每日时刻，学生录入会被 UI 先拦下而仓库本会放行——
     * 该口径分叉已收敛到本属性（配合 `stageDailyDeadlineHint` 的提示文案）。
     */
    val requiresDailyDeadline: Boolean
        get() = type == HomeworkType.STAGE && ownerRole == CreatorRole.PARENT

    /** 提交按钮可用条件（避免重复提交） */
    val canSubmit: Boolean get() = !loading && !submitting && !missingSession
}

/**
 * 手动录入 / 编辑模板页一次性事件。
 *
 * 保存成功事件一律携带 [Saved.homeworkId]（编辑 = 被编辑作业 id；新建 = 新建作业项的真实 id），
 * 供 framework 按 id 精确同步该作业的提醒；拿不到 id 时退化为
 * [HomeworkConstants.INVALID_ID]（非正数），消费方据此回落既有兜底口径。
 */
sealed interface HomeworkTemplateEvent {

    /** 保存成功（编辑既有作业）：返回上一页，并回抛被编辑作业 id */
    data class Saved(val homeworkId: Long) : HomeworkTemplateEvent

    /** 保存成功并携带提示（新建作业项时告知条数），同时回抛新建作业项 id */
    data class SavedWithMessage(val message: String, val homeworkId: Long) : HomeworkTemplateEvent

    /** 一次性提示 */
    data class ShowMessage(val message: String) : HomeworkTemplateEvent
}