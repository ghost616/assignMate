package com.assignmate.app.timer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.timer.data.HomeworkReminderCoordinator
import com.assignmate.app.timer.data.TimerCompleteResult
import com.assignmate.app.timer.data.TimerOverduePromptStore
import com.assignmate.app.timer.data.TimerPauseResult
import com.assignmate.app.timer.data.TimerPermissionChecker
import com.assignmate.app.timer.data.TimerPermissionStatus
import com.assignmate.app.timer.data.TimerRepository
import com.assignmate.app.timer.data.TimerResumeResult
import com.assignmate.app.timer.data.TimerStartResult
import com.assignmate.app.timer.data.TimerTickerController
import com.assignmate.app.timer.data.TimerTickerInfo
import com.assignmate.app.timer.data.TimerVoiceGuide
import com.assignmate.app.timer.data.TimerVoiceSettings
import com.assignmate.app.timer.domain.PauseRecord
import com.assignmate.app.timer.domain.TimerCalculations
import com.assignmate.app.timer.domain.TimerConstants
import com.assignmate.app.timer.domain.TimerFeedback
import com.assignmate.app.timer.domain.TimerPhase
import com.assignmate.app.timer.domain.TimerReminderRules
import com.assignmate.app.timer.domain.TimerSession
import com.assignmate.app.timer.domain.TimerSpeechTexts
import java.time.ZoneId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 作业执行页 ViewModel：作业内容/预估时长展示、走秒计时、开始/暂停（有事走开）/恢复（我回来啦）/完成。
 *
 * 状态流转（与 [TimerPhase] 状态机同源，仓库层兜底）：
 * - 进入页面：按「该作业最近一次未结束会话」恢复现场（RUNNING 继续走秒、PAUSED 冻结显示），
 *   无未结束会话则停留在 IDLE 等待「开始作业」；
 * - 开始 → RUNNING（作业同步置「进行中」，同时拉起计时前台服务常驻通知）；
 * - 暂停 → PAUSED（写入暂停明细，通知冻结走秒）；恢复 → RUNNING（补齐暂停时长并累计次数）；
 * - 完成 → FINISHED（会话收尾 + 作业置「已完成」），随后经一次性事件通知页面进入休息页。
 *
 * 界面锁定（需求：进行中不同时提供编辑作业入口）：[TimerExecutionUiState.homeworkEditingLocked]
 * 在 RUNNING/PAUSED 期间为 true，页面据此隐藏编辑入口并提示。
 *
 * 已用时长口径：每 [TimerConstants.TICK_INTERVAL_MILLIS] 用「当前时刻 - 开始时刻 - 暂停累计」重算
 * （非按跳数累加），因此刷新抖动不产生累计误差；暂停期间该值自动冻结。
 *
 * 走秒服务未被 onCleared 停止：离开页面（退到后台/切页）时计时应在后台继续，
 * 只有「完成作业」才停止前台服务。
 *
 * timer-B 附加职责：
 * - 语音引导（[TimerVoiceGuide] + [TimerSpeechTexts]）：开始/暂停/恢复/完成各播报一句，
 *   超时播报鼓励语；播报开关关闭或 TTS 不可用时静默跳过（见 [TimerVoiceSettings]）；
 * - 超时鼓励去重：同一作业按 [TimerConstants.OVERDUE_PROMPT_INTERVAL_MINUTES] 间隔最多提醒一次
 *   （记录于 [TimerOverduePromptStore]），避免走秒循环每秒重复骚扰；
 * - 到点提醒与数据保持一致：进入页面时同步本作业的闹钟，完成作业后同步（作业已完成 → 取消提醒）；
 * - 权限引导：暴露通知/精确闹钟权限状态供页面提示，拒绝授权不影响计时（[TimerPermissionChecker]）。
 */
@HiltViewModel
class TimerExecutionViewModel @Inject constructor(
    private val timerRepository: TimerRepository,
    private val homeworkRepository: HomeworkRepository,
    private val authRepository: AuthRepository,
    private val tickerController: TimerTickerController,
    private val reminderCoordinator: HomeworkReminderCoordinator,
    private val voiceGuide: TimerVoiceGuide,
    private val voiceSettings: TimerVoiceSettings,
    private val overduePromptStore: TimerOverduePromptStore,
    private val permissionChecker: TimerPermissionChecker,
    private val clock: Clock,
    /**
     * 业务时区：与 homework / core 的「作业每天详情」同源，用于阶段作业按「当天 + 每日截止时刻」
     * 判定是否到点（deadline 列对阶段作业不是绝对时间戳，见 [TimerCalculations.absoluteDeadlineMillisOf]）。
     * **必须由 Hilt 注入**（不提供系统时区默认值）：全应用唯一来源是 core 的
     * `core.di.DailyRecordModule.provideBusinessZoneId()`（无限定 [ZoneId] 绑定），覆写它即可让
     * 跨天归属、每天详情折算与超时判定同步切换；
     * **业务模块（含 timer）不得自建业务时区绑定**——homework / stats 同样消费该绑定，重复声明会是
     * Hilt 的 DuplicateBindings 编译错误并导致口径漂移。
     */
    private val zoneId: ZoneId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimerExecutionUiState())
    val uiState: StateFlow<TimerExecutionUiState> = _uiState.asStateFlow()

    private val _events = Channel<TimerExecutionEvent>(Channel.BUFFERED)
    val events: Flow<TimerExecutionEvent> = _events.receiveAsFlow()

    /**
     * 页面入口：由路由参数 `timer/execution/{studentId}/{homeworkId}` 传入。
     * 幂等：重复调用（重组/配置变更）不会重复建立走秒循环或重复拉起服务。
     */
    fun start(routeStudentId: Long, homeworkId: Long) {
        if (_uiState.value.started) {
            return
        }
        _uiState.update { it.copy(started = true, permissionStatus = permissionChecker.status()) }
        viewModelScope.launch {
            // 播报开关与权限状态属读取操作，先取出再建立走秒链路（失败静默，不影响计时）
            _uiState.update { it.copy(voiceEnabled = voiceSettings.isEnabled()) }
            val session = authRepository.currentSession()
            val targetId = resolveTimerStudentId(session, routeStudentId)
            val homework = if (homeworkId > 0L) homeworkRepository.getHomework(homeworkId) else null
            // 目标学生或作业不匹配（学生会话只能对本人名下作业计时）时统一走缺失提示
            if (targetId == null || homework == null || homework.studentId != targetId) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        missingSession = session.role == null,
                        missingStudent = session.role != null && targetId == null,
                        missingHomework = targetId != null &&
                            (homework == null || homework.studentId != targetId),
                    )
                }
                return@launch
            }
            val items = homeworkRepository.listHomework(targetId)
            _uiState.update {
                it.copy(
                    loading = false,
                    studentId = targetId,
                    role = session.role,
                    homework = homework,
                    totalCount = items.size,
                    completedCount = TimerCalculations.completedCount(items),
                )
            }
            restoreActiveSession()
            // 到点提醒与作业数据保持一致（作业已完成/未排定时间时此处会取消其闹钟）
            reminderCoordinator.syncHomeworkReminder(homework.id)
            startTicking()
        }
    }

    // ---- 操作（开始 / 暂停 / 恢复 / 完成） ----

    /** 开始计时：作业置「进行中」+ 落一条会话 + 拉起前台走秒服务 */
    fun onStartClick() {
        val state = _uiState.value
        val homework = state.homework ?: return
        val role = state.role ?: return
        if (state.busy) {
            return
        }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = timerRepository.startSession(homework.id, role)
            _uiState.update { it.copy(busy = false) }
            if (result !is TimerStartResult.Success) {
                sendMessage(TimerErrorMessages.startMessage(result))
                return@launch
            }
            val pauses = timerRepository.loadPauses(result.session.id)
            _uiState.update {
                it.copy(
                    session = result.session,
                    pauses = pauses,
                    phase = result.session.phase,
                    encouragementText = null,
                    overdue = false,
                    overduePrompted = false,
                )
            }
            refreshDerived()
            tickerController.startTicker(tickerInfoOf(result.session, pauses, frozenAtMillis = null))
            speakIfEnabled(TimerSpeechTexts.startTicking(homework.content))
        }
    }

    /** 暂停（有事走开）：写暂停明细并把会话置 PAUSED；通知冻结走秒 */
    fun onPauseClick() {
        val state = _uiState.value
        val session = state.session ?: return
        if (state.busy) {
            return
        }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = timerRepository.pauseSession(session.id)
            _uiState.update { it.copy(busy = false) }
            if (result !is TimerPauseResult.Success) {
                sendMessage(TimerErrorMessages.pauseMessage(result))
                return@launch
            }
            val pauses = timerRepository.loadPauses(result.session.id)
            _uiState.update { it.copy(session = result.session, pauses = pauses, phase = result.session.phase) }
            refreshDerived()
            tickerController.markPaused(
                tickerInfoOf(result.session, pauses, frozenAtMillis = ongoingPauseStartOf(pauses, session)),
            )
            speakIfEnabled(TimerSpeechTexts.paused())
        }
    }

    /** 恢复（我回来啦）：补齐暂停结束时刻、累计暂停时长与次数；通知继续走秒 */
    fun onResumeClick() {
        val state = _uiState.value
        val session = state.session ?: return
        if (state.busy) {
            return
        }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = timerRepository.resumeSession(session.id)
            _uiState.update { it.copy(busy = false) }
            if (result !is TimerResumeResult.Success) {
                sendMessage(TimerErrorMessages.resumeMessage(result))
                return@launch
            }
            val pauses = timerRepository.loadPauses(result.session.id)
            _uiState.update { it.copy(session = result.session, pauses = pauses, phase = result.session.phase) }
            refreshDerived()
            tickerController.markRunning(tickerInfoOf(result.session, pauses, frozenAtMillis = null))
            speakIfEnabled(TimerSpeechTexts.resumed())
        }
    }

    /** 完成作业：会话收尾 + 作业置「已完成」+ 停止前台服务，并通知页面进入休息页 */
    fun onCompleteClick() {
        val state = _uiState.value
        val session = state.session ?: return
        val role = state.role ?: return
        if (state.busy) {
            return
        }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = timerRepository.completeSession(session.id, role)
            _uiState.update { it.copy(busy = false) }
            if (result !is TimerCompleteResult.Success) {
                sendMessage(TimerErrorMessages.completeMessage(result))
                return@launch
            }
            tickerController.stopTicker()
            val pauses = timerRepository.loadPauses(session.id)
            val studentId = _uiState.value.studentId
            // 重新读取清单统计完成数量，保证鼓励语/概览口径与库内一致
            val items = if (studentId != null) homeworkRepository.listHomework(studentId) else emptyList()
            _uiState.update {
                it.copy(
                    session = result.session,
                    pauses = pauses,
                    phase = result.session.phase,
                    overdue = false,
                    overduePrompted = false,
                    encouragementText = null,
                    totalCount = items.size,
                    completedCount = TimerCalculations.completedCount(items),
                )
            }
            refreshDerived()
            if (studentId != null) {
                _events.send(TimerExecutionEvent.HomeworkCompleted(studentId, result.homework.id))
            }
            // 作业已完成：同步提醒（规则判定不再需要提醒 → 取消其闹钟）
            reminderCoordinator.syncHomeworkReminder(result.homework.id)
            speakIfEnabled(TimerSpeechTexts.completed(result.homework.content))
        }
    }

    // ---- timer-B：语音开关与权限引导 ----

    /** 切换语音播报开关（即时生效并落盘；持久化失败不影响本次会话） */
    fun onVoiceToggle(enabled: Boolean) {
        _uiState.update { it.copy(voiceEnabled = enabled) }
        viewModelScope.launch { voiceSettings.setEnabled(enabled) }
    }

    /** 刷新权限状态：页面恢复时调用（用户可能刚从系统设置返回） */
    fun refreshPermissionStatus() {
        _uiState.update { it.copy(permissionStatus = permissionChecker.status()) }
    }

    /**
     * 低版本（Android 13 以下）点「允许通知」时的处理：无需运行时申请，
     * 仅按系统当前设置刷新真实权限状态并给中性提示——**不谎报「已授权」**。
     */
    fun onPermissionRecheck() {
        refreshPermissionStatus()
        sendMessage("已按系统当前设置更新提醒权限状态")
    }

    /**
     * 离开页面（导航切换/页面销毁）：打断未播完的语音，避免用户已离开还在念上一屏内容。
     * 计时与走秒前台服务**不受影响**（页面离开不停计时，只停播报）。
     */
    fun onLeavingPage() {
        voiceGuide.stop()
    }

    /**
     * 权限申请结果回调（通知权限的系统弹窗返回、或用户从精确闹钟设置页返回后调用）：
     * 刷新状态并给出可读提示；**拒绝授权同样不阻断计时**。
     */
    fun onPermissionResult(granted: Boolean) {
        refreshPermissionStatus()
        sendMessage(
            if (granted) {
                "权限已开启，到点提醒可以正常送达啦"
            } else {
                "没有开启也不影响计时，之后可以在系统设置里打开"
            },
        )
    }

    // ---- 内部：走秒与状态刷新 ----

    /** 恢复未结束会话现场：RUNNING 继续走秒、PAUSED 冻结显示；同时把现场同步给前台服务 */
    private suspend fun restoreActiveSession() {
        val homework = _uiState.value.homework ?: return
        val active = timerRepository.findActiveSessionByHomework(homework.id)
        val pauses = active?.let { timerRepository.loadPauses(it.id) }.orEmpty()
        _uiState.update { it.copy(session = active, pauses = pauses, phase = active?.phase ?: TimerPhase.IDLE) }
        refreshDerived()
        if (active == null) {
            // 本作业无未结束会话：不动前台服务（可能正在为另一项作业走秒）
            return
        }
        if (active.phase == TimerPhase.PAUSED) {
            tickerController.markPaused(
                tickerInfoOf(active, pauses, frozenAtMillis = ongoingPauseStartOf(pauses, active)),
            )
        } else {
            tickerController.startTicker(tickerInfoOf(active, pauses, frozenAtMillis = null))
        }
    }

    /**
     * 走秒循环：按刷新间隔重算已用时长/暂停汇总/超时判定并写回状态。
     * 说明：`while (true)` 由 viewModelScope 生命周期兜底取消，无需额外标记。
     */
    private fun startTicking() {
        viewModelScope.launch {
            while (true) {
                refreshDerived()
                promptOverdueIfNeeded()
                delay(TimerConstants.TICK_INTERVAL_MILLIS)
            }
        }
    }

    /** 重算派生状态：已用时长、累计暂停时长/次数、是否超时与超时鼓励语（同一超时区间内文案保持稳定） */
    private fun refreshDerived() {
        val state = _uiState.value
        val session = state.session ?: return
        val homework = state.homework ?: return
        val now = clock.currentTimeMillis()
        val elapsed = TimerCalculations.elapsedOf(session, state.pauses, now)
        val pausedTotal = TimerCalculations.pauseAccumulatedMillis(state.pauses, now)
        val overdue = session.phase != TimerPhase.FINISHED &&
            TimerCalculations.isHomeworkOverdue(homework, session, now, zoneId)
        val encouragement = state.encouragementText
            ?: if (overdue) {
                TimerFeedback.encouragementText(state.completedCount, state.totalCount)
            } else {
                null
            }
        _uiState.update {
            it.copy(
                elapsedMillis = elapsed,
                pausedTotalMillis = pausedTotal,
                pauseCount = state.pauses.size,
                overdue = overdue,
                encouragementText = encouragement,
            )
        }
    }

    /**
     * 超时鼓励（含去重）：
     * - 同一作业两次提醒间隔不小于 [TimerConstants.OVERDUE_PROMPT_INTERVAL_MILLIS]（跨页面/重启也生效，
     *   记录在 [TimerOverduePromptStore]）；
     * - 本次页面会话内只提醒一次（[TimerExecutionUiState.overduePrompted]），
     *   避免每秒走秒刷新都重复播报；
     * - 提醒内容 = 随机鼓励语（含已完成数量），并同步播报（开关关闭时静默）。
     */
    private suspend fun promptOverdueIfNeeded() {
        val state = _uiState.value
        val homework = state.homework ?: return
        if (!state.overdue || state.overduePrompted) {
            return
        }
        val now = clock.currentTimeMillis()
        val lastPromptedAt = overduePromptStore.lastPromptedAtMillis(homework.id)
        if (!TimerReminderRules.shouldPromptOverdue(lastPromptedAt, now)) {
            return
        }
        overduePromptStore.markPrompted(homework.id, now)
        val text = state.encouragementText
            ?: TimerFeedback.encouragementText(state.completedCount, state.totalCount)
        _uiState.update { it.copy(encouragementText = text, overduePrompted = true) }
        speakIfEnabled(TimerSpeechTexts.overdueEncouragement(text))
    }

    /** 语音播报：开关关闭时静默跳过；TTS 不可用由 [TimerVoiceGuide] 静默降级（不阻塞界面） */
    private fun speakIfEnabled(text: String) {
        if (!_uiState.value.voiceEnabled) {
            return
        }
        voiceGuide.speak(text)
    }

    /** 组装前台服务的走秒基准（[frozenAtMillis] 非空表示暂停冻结） */
    private fun tickerInfoOf(
        session: TimerSession,
        pauses: List<PauseRecord>,
        frozenAtMillis: Long?,
    ): TimerTickerInfo = TimerTickerInfo(
        sessionId = session.id,
        homeworkContent = _uiState.value.homework?.content.orEmpty(),
        startedAtMillis = session.startedAt.toEpochMilli(),
        pausedTotalMillis = TimerCalculations.pauseAccumulatedMillis(
            pauses = pauses,
            referenceMillis = frozenAtMillis ?: clock.currentTimeMillis(),
        ),
        frozenAtMillis = frozenAtMillis,
    )

    /** 未结束暂停的开始时刻（暂停明细缺失时以会话开始时刻兜底，保证通知不至于涨秒） */
    private fun ongoingPauseStartOf(pauses: List<PauseRecord>, session: TimerSession): Long =
        pauses.lastOrNull { it.isOngoing }?.pauseStartAt?.toEpochMilli()
            ?: session.startedAt.toEpochMilli()

    /** 一次性提示：经 Channel 外抛，避免重组误触发 */
    private fun sendMessage(message: String) {
        viewModelScope.launch { _events.send(TimerExecutionEvent.ShowMessage(message)) }
    }
}

/** 作业执行页状态（单数据流） */
data class TimerExecutionUiState(
    val started: Boolean = false,
    val loading: Boolean = true,
    /** 无有效会话（未登录） */
    val missingSession: Boolean = false,
    /** 家长会话未选定学生 */
    val missingStudent: Boolean = false,
    /** 作业不存在或不属于目标学生 */
    val missingHomework: Boolean = false,
    val studentId: Long? = null,
    val role: Role? = null,
    val homework: HomeworkItem? = null,
    /** 当前未结束会话；IDLE 阶段为 null */
    val session: TimerSession? = null,
    val phase: TimerPhase = TimerPhase.IDLE,
    val pauses: List<PauseRecord> = emptyList(),
    /** 已用时长（毫秒，已扣除暂停累计） */
    val elapsedMillis: Long = 0L,
    /** 累计暂停时长（毫秒，暂停中时随时间增长） */
    val pausedTotalMillis: Long = 0L,
    /** 累计暂停次数（含当前未结束的暂停） */
    val pauseCount: Int = 0,
    val overdue: Boolean = false,
    /** 超时未完成时的随机鼓励语（同一超时区间内保持不变） */
    val encouragementText: String? = null,
    /** 清单作业总数与已完成数（鼓励语中的完成数量文案） */
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    /** 操作进行中（防重复点击） */
    val busy: Boolean = false,
    /** 语音播报开关（持久化于 DataStore，默认开启） */
    val voiceEnabled: Boolean = true,
    /** 本次页面会话是否已给出超时鼓励（页面内去重，避免每秒重复播报） */
    val overduePrompted: Boolean = false,
    /** 通知/精确闹钟权限状态；null 表示尚未读取（不做任何引导） */
    val permissionStatus: TimerPermissionStatus? = null,
) {

    /** 当前阶段可用的操作（纯函数 [executionActionStateOf] 推导，便于单测） */
    val actions: TimerExecutionActionState get() = executionActionStateOf(phase)

    /**
     * 界面是否锁定编辑作业入口：计时进行中（RUNNING/PAUSED）不允许同时编辑作业，
     * 避免执行期间改动内容/时间导致计时口径与作业数据不一致。
     */
    val homeworkEditingLocked: Boolean get() = phase.isActive

    /** 已用时长展示秒数（向上取整，避免显示比实际少 1 秒） */
    val elapsedSeconds: Long get() = TimerCalculations.displaySeconds(elapsedMillis)

    /** 需要展示的权限引导（纯函数推导：已授权项不出现，null 状态不引导） */
    val permissionHints: List<TimerPermissionHint>
        get() = TimerPermissionGuidance.hintsOf(permissionStatus)
}

/** 执行页操作可用性（按阶段推导，纯数据） */
data class TimerExecutionActionState(
    val canStart: Boolean,
    val canPause: Boolean,
    val canResume: Boolean,
    val canComplete: Boolean,
)

/**
 * 阶段 -> 操作可用性（纯函数）：
 * - 未开始：仅「开始」；进行中：可「有事走开」与「完成作业」；
 * - 已暂停：可「我回来啦」与「完成作业」；已完成/休息中：无操作（由页面引导流转）。
 */
internal fun executionActionStateOf(phase: TimerPhase): TimerExecutionActionState = when (phase) {
    TimerPhase.IDLE -> TimerExecutionActionState(
        canStart = true,
        canPause = false,
        canResume = false,
        canComplete = false,
    )

    TimerPhase.RUNNING -> TimerExecutionActionState(
        canStart = false,
        canPause = true,
        canResume = false,
        canComplete = true,
    )

    TimerPhase.PAUSED -> TimerExecutionActionState(
        canStart = false,
        canPause = false,
        canResume = true,
        canComplete = true,
    )

    TimerPhase.FINISHED, TimerPhase.RESTING -> TimerExecutionActionState(
        canStart = false,
        canPause = false,
        canResume = false,
        canComplete = false,
    )
}

/** 作业执行页一次性事件 */
sealed interface TimerExecutionEvent {

    /** 一次性提示（Snackbar 展示） */
    data class ShowMessage(val message: String) : TimerExecutionEvent

    /** 作业已完成：页面据此进入休息页（导航意图由 framework 接线） */
    data class HomeworkCompleted(val studentId: Long, val homeworkId: Long) : TimerExecutionEvent
}
