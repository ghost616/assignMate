package com.assignmate.app.timer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.HomeworkReminderCoordinator
import com.assignmate.app.timer.data.TimerRepository
import com.assignmate.app.timer.data.TimerStartResult
import com.assignmate.app.timer.domain.TimerCalculations
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * 下一项提示页 ViewModel：按清单优先级顺序展示下一条待完成（或进行中）的作业与「现在开始」按钮。
 *
 * 语义：
 * - 下一项由纯函数 [TimerCalculations.pickNextItem] 选取（priority 升序、同优先级按创建时间升序），
 *   已完成的项自然被排除；清单内全部完成时 [TimerNextItemUiState.allCompleted] 为 true，页面引导进入完成反馈页；
 * - 「现在开始」经 [TimerRepository.startSession] 先把作业推进为「进行中」并创建会话，
 *   成功后再抛出导航事件进入执行页（执行页会发现已有未结束会话并直接走秒）；
 * - 每次进入页面都重新读取清单（`start` 非首次调用只刷新数据），
 *   保证从执行页返回后展示的仍是「当前真正剩下的下一项」。
 */
@HiltViewModel
class TimerNextItemViewModel @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val timerRepository: TimerRepository,
    private val authRepository: AuthRepository,
    private val reminderCoordinator: HomeworkReminderCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimerNextItemUiState())
    val uiState: StateFlow<TimerNextItemUiState> = _uiState.asStateFlow()

    private val _events = Channel<TimerNextItemEvent>(Channel.BUFFERED)
    val events: Flow<TimerNextItemEvent> = _events.receiveAsFlow()

    /** 页面入口：由路由参数 `timer/next/{studentId}` 传入（0 表示学生会话取本人） */
    fun start(routeStudentId: Long) {
        val firstEntry = !_uiState.value.started
        if (firstEntry) {
            _uiState.update { it.copy(started = true) }
        }
        viewModelScope.launch {
            if (firstEntry) {
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
                _uiState.update {
                    it.copy(
                        studentId = targetId,
                        role = session.role,
                        studentName = authRepository.getStudent(targetId)?.name,
                    )
                }
            }
            loadItems()
        }
    }

    /**
     * 「现在开始」：先把下一项推进为「进行中」并创建计时会话（幂等：已有未结束会话则复用），
     * 成功后抛出导航事件进入执行页；失败按结果给出可读提示，不跳转。
     */
    fun onStartNextClick() {
        val state = _uiState.value
        val next = state.nextItem ?: return
        val role = state.role ?: return
        val studentId = state.studentId ?: return
        if (state.busy) {
            return
        }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = timerRepository.startSession(next.id, role)
            _uiState.update { it.copy(busy = false) }
            when (result) {
                is TimerStartResult.Success ->
                    _events.send(TimerNextItemEvent.StartHomework(studentId, result.session.homeworkId))

                else -> _events.send(TimerNextItemEvent.ShowMessage(TimerErrorMessages.startMessage(result)))
            }
        }
    }

    /** 读取清单并推导下一项与完成情况（每次进入页面都重新计算） */
    private suspend fun loadItems() {
        val studentId = _uiState.value.studentId ?: return
        val items = homeworkRepository.listHomework(studentId)
        _uiState.update {
            it.copy(
                loading = false,
                nextItem = TimerCalculations.pickNextItem(items),
                totalCount = items.size,
                completedCount = TimerCalculations.completedCount(items),
                remainingCount = TimerCalculations.remainingCount(items),
            )
        }
        // 到点提醒与最新清单保持一致：应提醒的设上、已完成/已过期的取消（timer-B）
        reminderCoordinator.syncStudentReminders(studentId)
    }
}

/** 下一项提示页状态（单数据流） */
data class TimerNextItemUiState(
    val started: Boolean = false,
    val loading: Boolean = true,
    /** 无有效会话（未登录） */
    val missingSession: Boolean = false,
    /** 家长会话未选定学生 */
    val missingStudent: Boolean = false,
    val studentId: Long? = null,
    val role: Role? = null,
    val studentName: String? = null,
    /** 下一条待完成/进行中的作业；为 null 且数据已就绪表示清单已全部完成 */
    val nextItem: HomeworkItem? = null,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val remainingCount: Int = 0,
    /** 操作进行中（防重复点击） */
    val busy: Boolean = false,
) {

    /** 清单是否已全部完成（据此进入完成反馈页） */
    val allCompleted: Boolean
        get() = !loading && !missingSession && !missingStudent && nextItem == null

    /** 主操作按钮文案：进行中的项是「继续」，其余是「开始」 */
    val nextItemActionText: String
        get() = if (nextItem?.status == HomeworkStatus.IN_PROGRESS) "继续做这一项" else "现在开始"

    /** 完成情况文案（如「已完成 2 / 5 项」） */
    val progressText: String get() = "已完成 $completedCount / $totalCount 项"
}

/** 下一项提示页一次性事件 */
sealed interface TimerNextItemEvent {

    /** 一次性提示（Snackbar 展示） */
    data class ShowMessage(val message: String) : TimerNextItemEvent

    /** 已开始下一项：页面据此导航到执行页 */
    data class StartHomework(val studentId: Long, val homeworkId: Long) : TimerNextItemEvent
}
