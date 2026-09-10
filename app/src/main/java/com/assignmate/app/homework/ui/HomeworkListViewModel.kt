package com.assignmate.app.homework.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkOrderResult
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.data.ReorderDirection
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkValidators
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 作业清单页 ViewModel：按优先级升序展示某学生的作业清单（内容/类型/开始时间/预估时长/状态），
 * 支持上移下移调整优先级、进入编辑与时间设定、删除确认；按会话角色决定各条目可操作与否
 * （学生不可操作家长录入项：隐藏或禁用按钮，仓库层同时兜底校验）。
 *
 * 状态流转语义（本页负责的入口）：
 * - 「已记录」项可进入时间设定；排定成功后仓库自动推进为「待完成」；
 * - 「已完成」项可撤销完成（completed → in_progress），便于纠正误标记。
 *
 * 数据来源：仓库 observeHomework 单数据流（StateFlow 暴露给 UI），
 * 一次性提示走 Channel 事件，避免重组误触发。
 *
 * 数据维度：目标学生 = 学生会话本人 id（忽略路由参数，防越权）/ 家长会话路由参数
 * `homework/list/{studentId}`；家长未选定学生时页面提示「请先选择学生」。
 *
 * 页面经 [start] 注入路由参数（幂等），避免重组重复建流。
 */
@HiltViewModel
class HomeworkListViewModel @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeworkListUiState())
    val uiState: StateFlow<HomeworkListUiState> = _uiState.asStateFlow()

    private val _events = Channel<HomeworkListEvent>(Channel.BUFFERED)
    val events: Flow<HomeworkListEvent> = _events.receiveAsFlow()

    /**
     * 页面入口：由路由参数 `homework/list/{studentId}` 传入目标学生（框架经
     * [HomeworkDestination.studentIdOf] 解析后传入，0 表示未指定）。
     * 幂等：重复调用（重组/配置变更）不会重复建立数据流。
     */
    fun start(routeStudentId: Long) {
        if (_uiState.value.started) {
            return
        }
        _uiState.update { it.copy(started = true) }
        viewModelScope.launch {
            val session = authRepository.currentSession()
            val targetId = resolveTargetStudentId(session, routeStudentId)
            if (targetId == null) {
                // 会话失效与「家长未选定学生」是两类不同提示，分别置位供页面区分展示
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
                    loading = false,
                    studentId = targetId,
                    studentName = authRepository.getStudent(targetId)?.name,
                    role = session.role,
                    // 执行权判定基准：学生会话本人 id（家长会话为 null）
                    sessionStudentId = if (session.isStudent) session.studentId else null,
                    todayEpochDay = currentEpochDay(),
                )
            }
            collectHomework(targetId)
        }
    }

    /**
     * 目标学生 id 解析：学生会话固定为本人（忽略路由参数，防止越权查看他人清单）；
     * 家长会话取路由参数指定的学生；均缺失时返回 null（页面提示「请先选择学生」）。
     */
    private fun resolveTargetStudentId(session: SessionState, routeStudentId: Long): Long? = when {
        session.isStudent -> session.studentId
        session.role == Role.PARENT -> routeStudentId.takeIf { it > 0L }
        else -> null
    }

    /** 观察清单：数据变化即刷新，权限与「可上移/可下移」标记随角色一并计算 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun collectHomework(studentId: Long) {
        viewModelScope.launch {
            authRepository.observeSession()
                .flatMapLatest { session -> observeForStudent(session, studentId) }
                .collect { items ->
                    _uiState.update { state ->
                        state.copy(
                            items = items.mapIndexed { index, item ->
                                // 调序用改删权限（学生不可调家长录入项），完成/撤销完成用执行权
                                val movable = canReorder(state.role, item)
                                item.toRow(
                                    role = state.role,
                                    sessionStudentId = state.sessionStudentId,
                                    canMoveUp = index > 0 && movable,
                                    canMoveDown = index < items.lastIndex && movable,
                                )
                            },
                        )
                    }
                }
        }
    }

    private fun observeForStudent(
        session: SessionState,
        studentId: Long,
    ): Flow<List<HomeworkItem>> =
        if (session.role == null) flowOf(emptyList()) else homeworkRepository.observeHomework(studentId)

    // ---- 优先级调整 ----

    fun onMoveUpClick(homeworkId: Long) = reorder(homeworkId, ReorderDirection.UP)

    fun onMoveDownClick(homeworkId: Long) = reorder(homeworkId, ReorderDirection.DOWN)

    /**
     * 调整优先级：先在本地交换展示顺序（即时反馈），再落库；
     * 落库失败时以仓库返回值/错误提示为准，数据流刷新会自动纠正本地顺序。
     */
    private fun reorder(homeworkId: Long, direction: ReorderDirection) {
        val current = _uiState.value
        val rows = current.items
        val index = rows.indexOfFirst { it.homework.id == homeworkId }
        if (index < 0) {
            return
        }
        val targetIndex = when (direction) {
            ReorderDirection.UP -> index - 1
            ReorderDirection.DOWN -> index + 1
        }
        if (targetIndex !in rows.indices) {
            return
        }
        if (!canReorder(current.role, rows[index].homework)) {
            sendMessage(PERMISSION_DENIED_HINT)
            return
        }
        val swapped = rows.toMutableList().apply {
            val moved = removeAt(index)
            add(targetIndex, moved)
        }
        _uiState.update { state ->
            state.copy(
                items = swapped.mapIndexed { i, row ->
                    row.copy(canMoveUp = i > 0, canMoveDown = i < swapped.lastIndex)
                },
            )
        }
        viewModelScope.launch {
            val role = current.role ?: return@launch
            val result = homeworkRepository.reorderHomework(homeworkId, direction, role)
            if (result is HomeworkOrderResult.Success) {
                return@launch
            }
            sendMessage(result.toUserMessage())
        }
    }

    // ---- 删除 ----

    fun onDeleteClick(homeworkId: Long) {
        val row = _uiState.value.items.firstOrNull { it.homework.id == homeworkId } ?: return
        if (!row.canDelete) {
            sendMessage(PERMISSION_DENIED_HINT)
            return
        }
        _uiState.update { it.copy(deleteTarget = row.homework) }
    }

    fun onDeleteDismiss() {
        _uiState.update { it.copy(deleteTarget = null, deleting = false) }
    }

    fun onDeleteConfirm() {
        val target = _uiState.value.deleteTarget ?: return
        val role = _uiState.value.role
        if (_uiState.value.deleting || role == null) {
            return
        }
        _uiState.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val result = homeworkRepository.deleteHomework(target.id, role)
            _uiState.update { it.copy(deleting = false, deleteTarget = null) }
            sendMessage(result.toUserMessage("已删除该作业"))
        }
    }

    // ---- 状态流转（本页提供「标记完成」与「撤销完成」） ----

    /** 标记完成：待完成/进行中 → 已完成（执行权：本人名下作业） */
    fun onCompleteClick(homeworkId: Long) {
        val row = _uiState.value.items.firstOrNull { it.homework.id == homeworkId } ?: return
        if (!row.canComplete) {
            sendMessage(EXECUTE_PERMISSION_DENIED_HINT)
            return
        }
        val role = _uiState.value.role ?: return
        viewModelScope.launch {
            val result = homeworkRepository.complete(homeworkId, role)
            val message = when (result) {
                is HomeworkStatusResult.Success -> "已标记完成"
                HomeworkStatusResult.NotFound -> "作业不存在，可能已被删除"
                HomeworkStatusResult.PermissionDenied -> EXECUTE_PERMISSION_DENIED_HINT
                is HomeworkStatusResult.IllegalTransition -> "当前状态不支持该操作"
            }
            sendMessage(message)
        }
    }

    fun onReopenClick(homeworkId: Long) {
        val row = _uiState.value.items.firstOrNull { it.homework.id == homeworkId } ?: return
        if (!row.canReopen) {
            sendMessage(EXECUTE_PERMISSION_DENIED_HINT)
            return
        }
        val role = _uiState.value.role ?: return
        viewModelScope.launch {
            val result = homeworkRepository.reopen(homeworkId, role)
            val message = when (result) {
                is HomeworkStatusResult.Success -> "已撤销完成"
                HomeworkStatusResult.NotFound -> "作业不存在，可能已被删除"
                HomeworkStatusResult.PermissionDenied -> EXECUTE_PERMISSION_DENIED_HINT
                is HomeworkStatusResult.IllegalTransition -> "当前状态不支持该操作"
            }
            sendMessage(message)
        }
    }

    /** 提示：受权限限制的操作（UI 已禁用按钮，此处兜底） */
    fun onPermissionDenied() {
        sendMessage(PERMISSION_DENIED_HINT)
    }

    // ---- 私有工具 ----

    private fun sendMessage(message: String) {
        viewModelScope.launch { _events.send(HomeworkListEvent.ShowMessage(message)) }
    }

    /** 改删/调序权限：学生仅可操作自己录入的作业 */
    private fun canReorder(role: Role?, item: HomeworkItem): Boolean =
        role != null && HomeworkValidators.canReorder(item, role)

    private fun HomeworkItem.toRow(
        role: Role?,
        sessionStudentId: Long?,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
    ): HomeworkRowUiState = toRowUiState(
        role = role,
        sessionStudentId = sessionStudentId,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
    )

    /** 当前自然日（epochDay）：按业务时区折算，保证「今天」口径与页面展示一致 */
    private fun currentEpochDay(): Long =
        HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zoneId)
}

/** 作业清单页状态（单数据流） */
data class HomeworkListUiState(
    val started: Boolean = false,
    val loading: Boolean = true,
    val missingSession: Boolean = false,
    val missingStudent: Boolean = false,
    val studentId: Long? = null,
    val studentName: String? = null,
    val role: Role? = null,
    /** 学生会话本人 id：执行权判定用（家长会话为 null，视为全部可操作） */
    val sessionStudentId: Long? = null,
    val todayEpochDay: Long = 0L,
    val items: List<HomeworkRowUiState> = emptyList(),
    val deleteTarget: HomeworkItem? = null,
    val deleting: Boolean = false,
) {

    /** 是否为空清单（非加载、非会话/学生缺失且无数据） */
    val isEmpty: Boolean get() = !loading && !missingSession && !missingStudent && items.isEmpty()
}

/** 清单条目状态：作业数据 + 按角色计算的权限开关 */
data class HomeworkRowUiState(
    val homework: HomeworkItem,
    /** 改删/调序权限：学生仅可操作自己录入的作业（[HomeworkValidators.canModify]） */
    val canModify: Boolean,
    val canDelete: Boolean,
    /** 执行权：可进入时间设定（本人名下作业，且未完成） */
    val canSchedule: Boolean,
    /** 执行权：可推进状态至完成（本人名下作业，且处于待完成/进行中） */
    val canComplete: Boolean,
    /** 执行权：已完成项可撤销完成（本人名下作业） */
    val canReopen: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/**
 * 由作业项与当前会话推导清单条目的可操作性（纯函数，便于单测；仓库层同源兜底）。
 *
 * 两个权限维度刻意分开：
 * - 改删/调序用 [HomeworkValidators.canModify]（学生仅自己录入项，家长全部）；
 * - 时间排定与状态流转用 [HomeworkValidators.canOperate]（学生可为本人名下全部作业，
 *   含家长布置的，保证「家长布置 → 学生排定时间 → 计时完成」主闭环可用）；
 * 「标记完成」仅在待完成/进行中可用（已记录需先排定时间，见 [HomeworkStatus] 流转表）。
 */
internal fun HomeworkItem.toRowUiState(
    role: Role?,
    sessionStudentId: Long?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
): HomeworkRowUiState {
    val executable = role != null && HomeworkValidators.canOperate(this, role, sessionStudentId)
    val unfinished = status != HomeworkStatus.COMPLETED
    val completable = executable &&
        (status == HomeworkStatus.PENDING || status == HomeworkStatus.IN_PROGRESS)
    return HomeworkRowUiState(
        homework = this,
        canModify = role != null && HomeworkValidators.canModify(this, role),
        canDelete = role != null && HomeworkValidators.canDelete(this, role),
        canSchedule = unfinished && executable,
        canComplete = completable,
        canReopen = !unfinished && executable,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
    )
}

/** 作业清单页一次性事件 */
sealed interface HomeworkListEvent {

    /** 一次性提示（Snackbar 展示） */
    data class ShowMessage(val message: String) : HomeworkListEvent
}