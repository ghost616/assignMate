package com.assignmate.app.homework.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.homework.data.HomeworkFileStore
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageRange
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 作业录入页（四种方式统一入口 + 内容编辑与保存）ViewModel。
 *
 * 四种录入方式：
 * - [HomeworkEntryMethod.MANUAL] 手动：直接编辑内容输入框；
 * - [HomeworkEntryMethod.CAMERA] 拍照：相机写入临时文件 → [HomeworkOcrHandler] 识别 → 回填；
 * - [HomeworkEntryMethod.GALLERY] 相册：选图 → 同样走识别 → 回填；
 * - [HomeworkEntryMethod.VOICE] 语音：申请录音权限 → [SpeechToText] 实时回显 Partial → Final 回填。
 *
 * 统一流程：识别/输入 → 可编辑文本（[HomeworkEntryUiState.content] 始终可手改）→
 * 选定类型/阶段范围/deadline（规则与 homework-A 一致）→ 保存进既有仓库（权限与校验同源）。
 *
 * 页面销毁（onCleared）释放语音资源并取消识别协程。
 */
@HiltViewModel
class HomeworkEntryViewModel @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val authRepository: AuthRepository,
    private val ocrHandler: HomeworkOcrHandler,
    private val speechToText: SpeechToText,
    private val fileStore: HomeworkFileStore,
    private val clock: com.assignmate.app.core.domain.time.Clock,
    private val zoneId: ZoneId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeworkEntryUiState())
    val uiState: StateFlow<HomeworkEntryUiState> = _uiState.asStateFlow()

    private val _events = Channel<HomeworkEntryEvent>(Channel.BUFFERED)
    val events: Flow<HomeworkEntryEvent> = _events.receiveAsFlow()

    /** 语音识别/OCR 识别运行中的协程（取消用） */
    private var recognitionJob: Job? = null

    private var capturePath: String? = null

    /** 待重试任务数量（录入页入口展示，联网后批量重试）：统一走 [HomeworkOcrHandler] 口径 */
    val pendingCount: Flow<Int> = ocrHandler.observePendingCount()

    /** 初始化：准备会话与默认表单值 */
    fun start(studentId: Long, method: HomeworkEntryMethod = HomeworkEntryMethod.MANUAL) {
        if (_uiState.value.initialized) {
            return
        }
        // 「今天」口径统一走 HomeworkValidators.epochDayOf（按注入 zoneId 折算）；
        // 不可用 clock/86400000 直接折算，否则在 UTC+8 凌晨会取到昨天
        val todayEpochDay = HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zoneId)
        _uiState.update { it.copy(initialized = true, todayEpochDay = todayEpochDay, method = method) }
        viewModelScope.launch {
            // 启动时回收上次未清理的残留图片（超过 TTL 且无任务引用）
            ocrHandler.cleanupOrphanFiles()
            val session = authRepository.currentSession()
            val role = session.role
            val targetId = studentId.takeIf { it > 0L } ?: session.studentId ?: session.parentId
            if (role == null || targetId == null) {
                _uiState.update { it.copy(loading = false, missingSession = true) }
                return@launch
            }
            val studentName = targetId.let { authRepository.getStudent(it)?.name }
            _uiState.update {
                it.copy(loading = false, studentId = targetId, role = role, studentName = studentName)
            }
        }
    }

    // ---- 科目/类型/时间表单（与 homework-A 同规则） ----

    fun onContentChange(value: String) {
        _uiState.update { it.copy(content = value, contentError = null, formError = null) }
    }

    fun onMethodChange(method: HomeworkEntryMethod) {
        _uiState.update { it.copy(method = method) }
    }

    fun onTypeChange(type: HomeworkType) {
        _uiState.update {
            it.copy(
                type = type,
                stageRange = if (type == HomeworkType.STAGE) it.stageRange else null,
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

    // ---- 拍照录入 ----

    /** 申请/已获相机权限后开始拍照：创建临时文件并由 UI 启动相机 Intent */
    fun onCameraClick() {
        if (_uiState.value.processing) {
            return
        }
        val path = fileStore.createCaptureFile()
        capturePath = path
        _events.trySend(HomeworkEntryEvent.LaunchCamera(path))
    }

    /** 相机回调：成功则读取文件并识别，取消则清理临时文件 */
    fun onCameraResult(success: Boolean) {
        val path = capturePath
        capturePath = null
        if (!success || path == null) {
            path?.let { fileStore.delete(it) }
            sendMessage("已取消拍照")
            return
        }
        startOcr { ocrHandler.recognizeLocalImage(path) }
    }

    /** 相机权限被拒绝：给出可读提示（引导在设置中开启） */
    fun onCameraPermissionDenied() {
        sendMessage("需要相机权限才能拍照录入，请在系统设置中开启后重试")
    }

    // ---- 相册录入 ----

    /** 相册选图回调：得到本地路径后识别 */
    fun onGalleryPicked(path: String?) {
        if (path.isNullOrBlank()) {
            return
        }
        startOcr { ocrHandler.recognizeLocalImage(path) }
    }

    /** 相册读取失败（如权限/文件不可读） */
    fun onGalleryFailed(message: String = "无法读取所选图片，请重新选择") {
        sendMessage(message)
    }

    // ---- 语音录入 ----

    /** 录音权限被拒绝：可读提示 */
    fun onAudioPermissionDenied() {
        sendMessage("需要录音权限才能语音录入，请在系统设置中开启后重试")
    }

    /** 开始语音识别：授权后调用，Partial 实时回显、Final 回填内容 */
    fun onVoiceStart() {
        if (_uiState.value.voiceListening) {
            return
        }
        _uiState.update { it.copy(voiceListening = true, voicePartial = "") }
        recognitionJob?.cancel()
        recognitionJob = viewModelScope.launch {
            try {
                speechToText.startListening().collect { event ->
                    when (event) {
                        // Partial 实时回显：空白中间结果不回填（避免内容框闪出空白行）
                        is SpeechToText.SpeechEvent.Partial -> event.toPartialHint()?.let { hint ->
                            _uiState.update { it.copy(voicePartial = hint) }
                        }

                        is SpeechToText.SpeechEvent.Final -> {
                            val text = event.text.trim()
                            _uiState.update { state ->
                                state.copy(
                                    voiceListening = false,
                                    voicePartial = "",
                                    content = if (text.isEmpty()) {
                                        state.content
                                    } else {
                                        appendContent(state.content, text)
                                    },
                                )
                            }
                            if (text.isEmpty()) {
                                sendMessage("没有听清，请再说一遍")
                            }
                        }

                        is SpeechToText.SpeechEvent.Error -> {
                            _uiState.update { it.copy(voiceListening = false, voicePartial = "") }
                            sendMessage(event.describe())
                        }
                    }
                }
            } finally {
                _uiState.update { it.copy(voiceListening = false, voicePartial = "") }
            }
        }
    }

    /** 结束当前语音识别（尽量产出最终结果） */
    fun onVoiceStop() {
        speechToText.stopListening()
    }

    /** 取消语音识别：清空中间结果，不写入内容 */
    fun onVoiceCancel() {
        speechToText.stopListening()
        recognitionJob?.cancel()
        recognitionJob = null
        _uiState.update { it.copy(voiceListening = false, voicePartial = "") }
    }

    // ---- 待重试识别 ----

    /** 批量重试全部 PENDING 任务：成功后回填文本并提示 */
    fun onRetryPendingClick() {
        if (_uiState.value.retrying) {
            return
        }
        _uiState.update { it.copy(retrying = true) }
        viewModelScope.launch {
            val summary = ocrHandler.retryPendingTasks()
            _uiState.update { state ->
                state.copy(
                    retrying = false,
                    content = summary.firstText?.let { appendContent(state.content, it) } ?: state.content,
                )
            }
            sendMessage(summary.message)
        }
    }

    // ---- 提交保存 ----

    fun onSubmit() {
        val state = _uiState.value
        if (state.loading || state.submitting || state.missingSession || state.processing) {
            return
        }
        val role = state.role ?: return
        val studentId = state.studentId ?: return
        val creatorRole = CreatorRole.fromSessionRole(role)
        val trimmed = state.content.trim()
        if (creatorRole == CreatorRole.PARENT && trimmed.isEmpty()) {
            _uiState.update { it.copy(contentError = "请填写作业内容或先识别图片") }
            return
        }
        if (trimmed.length > HomeworkConstants.MAX_CONTENT_LENGTH) {
            _uiState.update { it.copy(contentError = "作业内容过长，请精简后再提交") }
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
        if (state.type == HomeworkType.STAGE && creatorRole == CreatorRole.PARENT && deadlineInstant == null) {
            _uiState.update { it.copy(deadlineError = "家长录入的阶段作业需要设置截止时间") }
            return
        }
        val template = HomeworkTemplate(
            content = trimmed,
            type = state.type,
            stageRange = if (state.type == HomeworkType.STAGE) state.stageRange else null,
            deadline = deadlineInstant,
            creatorRole = creatorRole,
            startEpochDay = state.todayEpochDay,
            zoneId = zoneId,
        )
        _uiState.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            val result = homeworkRepository.addHomework(template, studentId)
            _uiState.update { it.copy(submitting = false) }
            when (result) {
                is AddHomeworkResult.Success -> _events.send(
                    HomeworkEntryEvent.Saved("已添加 ${result.items.size} 项作业"),
                )

                is AddHomeworkResult.TemplateInvalid ->
                    _uiState.update { it.copy(formError = result.error.toUserMessage()) }

                AddHomeworkResult.NoActiveSession ->
                    sendMessage("会话已失效，请重新登录")
            }
        }
    }

    // ---- 生命周期 ----

    override fun onCleared() {
        // 页面销毁：释放语音识别底层资源并取消进行中的识别
        recognitionJob?.cancel()
        recognitionJob = null
        speechToText.release()
        super.onCleared()
    }

    // ---- 私有工具 ----

    /** 统一的识别执行入口：处理中标记 + 结果回填/提示 */
    private fun startOcr(recognize: suspend () -> OcrOutcome) {
        _uiState.update { it.copy(processing = true, formError = null) }
        recognitionJob = viewModelScope.launch {
            val outcome = recognize()
            _uiState.update { state ->
                when (outcome) {
                    is OcrOutcome.Filled -> state.copy(
                        processing = false,
                        content = appendContent(state.content, outcome.text),
                        contentError = null,
                    )

                    is OcrOutcome.NeedsRetry -> state.copy(processing = false)
                    is OcrOutcome.Failed -> state.copy(
                        processing = false,
                        formError = outcome.message,
                    )
                }
            }
            when (outcome) {
                is OcrOutcome.Filled -> if (outcome.text.isBlank()) {
                    sendMessage("识别结果为空，请在输入框中手动补充")
                } else {
                    sendMessage("识别完成，可继续修改内容")
                }

                is OcrOutcome.NeedsRetry -> sendMessage(outcome.message)

                is OcrOutcome.Failed -> {
                    sendMessage(outcome.message)
                    if (outcome.needsConfiguration) {
                        // 引导用户去设置页配置 OCR 厂商参数（图片已登记待重试，配置后可直接重试）
                        _events.send(HomeworkEntryEvent.NeedsOcrConfiguration(outcome.message))
                    }
                }
            }
        }
    }

    /** 追加文本到内容框（保留用户已输入内容，空行分隔） */
    private fun appendContent(current: String, addition: String): String {
        if (addition.isBlank()) {
            return current
        }
        return if (current.isBlank()) addition else "$current\n$addition"
    }

    private fun resolveDeadline(state: HomeworkEntryUiState): DeadlineParse {
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
        viewModelScope.launch { _events.send(HomeworkEntryEvent.ShowMessage(message)) }
    }

    /** 截止时间解析结果 */
    private sealed interface DeadlineParse {

        data class Parsed(val instant: Instant?) : DeadlineParse

        data class Failed(val message: String) : DeadlineParse
    }

    private companion object {

        /** 未填截止时刻时的默认时刻 */
        const val DEFAULT_DEADLINE_HOUR = 21

        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** 录入方式（四种入口之一） */
enum class HomeworkEntryMethod(val label: String, val description: String) {

    /** 手动录入：直接敲字 */
    MANUAL("手动录入", "直接用键盘输入作业内容"),

    /** 拍照录入：拍课本/黑板后识别文字 */
    CAMERA("拍照识别", "拍下作业照片，自动识别文字"),

    /** 相册录入：从相册选图识别 */
    GALLERY("相册识别", "从相册选择作业图片，自动识别文字"),

    /** 语音录入：说一句话转成文字 */
    VOICE("语音录入", "说一遍作业内容，自动转成文字"),
    ;

    /** 是否为图片识别类入口 */
    val isImageBased: Boolean get() = this == CAMERA || this == GALLERY
}

/** 录入页状态（单数据流） */
data class HomeworkEntryUiState(
    val initialized: Boolean = false,
    val loading: Boolean = true,
    val missingSession: Boolean = false,
    val submitting: Boolean = false,
    /** 识别中（OCR 请求进行中） */
    val processing: Boolean = false,
    /** 待重试任务重试中 */
    val retrying: Boolean = false,
    val studentId: Long? = null,
    val studentName: String? = null,
    val role: Role? = null,
    val method: HomeworkEntryMethod = HomeworkEntryMethod.MANUAL,
    val todayEpochDay: Long = 0L,
    val content: String = "",
    val type: HomeworkType = HomeworkType.TODAY,
    val stageRange: StageRange? = null,
    val deadlineDate: String = "",
    val deadlineTime: String = "",
    /** 语音识别进行中 */
    val voiceListening: Boolean = false,
    /** 语音实时回显文本（Partial） */
    val voicePartial: String = "",
    val contentError: String? = null,
    val stageError: String? = null,
    val deadlineError: String? = null,
    val formError: String? = null,
) {

    /** 截止时间是否可编辑（仅家长） */
    val deadlineEditable: Boolean get() = role == Role.PARENT

    /** 预计覆盖的最后一天 */
    val lastEpochDay: Long get() = stageRange?.lastEpochDay(todayEpochDay) ?: todayEpochDay

    /** 是否有内容可保存（学生允许留空） */
    val canSubmit: Boolean get() = !loading && !submitting && !missingSession && !processing
}

/** 录入页一次性事件 */
sealed interface HomeworkEntryEvent {

    /** 请求 UI 启动相机拍摄（携带临时文件路径） */
    data class LaunchCamera(val outputPath: String) : HomeworkEntryEvent

    /** 保存成功：返回清单页 */
    data class Saved(val message: String) : HomeworkEntryEvent

    /** 一次性提示 */
    data class ShowMessage(val message: String) : HomeworkEntryEvent

    /**
     * 识别服务未配置：提示引导去设置页填写厂商参数
     * （图片已保存为待重试任务，配置完成后可在本页「重试识别」入口继续）。
     */
    data class NeedsOcrConfiguration(val message: String) : HomeworkEntryEvent
}