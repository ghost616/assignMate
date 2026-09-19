package com.assignmate.app.homework.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkOrderResult
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.data.ReorderDirection
import com.assignmate.app.homework.domain.HomeworkDayState
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
 * - 「待完成」项可「开始作业」（导航到计时页）与「标记完成」；
 * - 「进行中」项锁定调序与时间排定（需求假设 C），仅可「标记完成」或继续计时；
 * - 「已完成」项可撤销完成（completed → in_progress），便于纠正误标记；
 * - **阶段作业的「完成」是「完成今天」**：仓库只把今天记进每天详情，整条状态按每天进度收敛——
 *   阶段未走完时保持「待完成」（本页因此仍展示「开始作业」入口，第二天无需先撤销完成；
 *   行内以「今日：已完成 + 阶段进度：已打卡 N/M 天」表达当天进度），全部覆盖日完成才置「已完成」；
 *   今天已完成时改为展示「撤销完成」（撤销今天的完成记录）。
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
    /**
     * 业务时区：由 core 的**唯一**业务时区绑定注入（homework 不自建绑定），
     * 既是本页「今天」口径的唯一来源，也经 [zoneId] 暴露给页面用于展示格式化，
     * 避免 Compose 侧再以 `ZoneId.systemDefault()` 兜底造成覆写绑定后展示口径漂移。
     */
    val zoneId: ZoneId,
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
                .collect { items -> applyItems(items) }
        }
    }

    /**
     * 应用一次清单快照：拉取「作业每天详情」并重算每行的每日维度（今日状态 / 阶段进度）+ 角色口径分组。
     *
     * 「作业每天详情」是状态与进度的唯一数据来源（core 契约）；状态流转落库后经
     * [refreshDailyRecordsNow] 主动刷新，保证「标记完成」后今日状态与进度即时更新。
     */
    private suspend fun applyItems(items: List<HomeworkItem>) {
        val state = _uiState.value
        val records = runCatching { homeworkRepository.dailyRecordsOf(state.studentId ?: 0L) }
            .getOrDefault(emptyMap())
        _uiState.update { it.copy(allItems = items, items = buildRows(items, records, it)) }
    }

    /** 由清单快照 + 每天详情 + 会话推导全部行（含角色口径过滤与分组归属） */
    private fun buildRows(
        items: List<HomeworkItem>,
        records: Map<Long, List<HomeworkDailyRecord>>,
        state: HomeworkListUiState,
    ): List<HomeworkRowUiState> {
        val recordsOf: (Long) -> List<HomeworkDailyRecord> = { id -> records[id].orEmpty() }
        // 学生只看当天（阶段开始前不显示、结束后移出）；家长看全部
        val visible = HomeworkListRoleScope.visibleForDay(
            items = items,
            role = state.role,
            todayEpochDay = state.todayEpochDay,
            zoneId = zoneId,
        )
        val groups = HomeworkListRoleScope.group(
            items = visible,
            recordsOf = recordsOf,
            todayEpochDay = state.todayEpochDay,
            zoneId = zoneId,
        )
        val sections = buildMap {
            groups.main.forEach { item -> put(item.id, HomeworkListSection.MAIN) }
            groups.completedHistory.forEach { item -> put(item.id, HomeworkListSection.COMPLETED_HISTORY) }
            groups.ended.forEach { item -> put(item.id, HomeworkListSection.ENDED) }
        }
        val ordered = groups.main + groups.completedHistory + groups.ended
        return ordered.mapIndexed { index, item ->
            val timeline = HomeworkListRoleScope.rowTimeline(
                item = item,
                records = recordsOf(item.id),
                todayEpochDay = state.todayEpochDay,
                zoneId = zoneId,
            )
            val movable = canReorder(state.role, item, state.sessionStudentId) &&
                item.status != HomeworkStatus.IN_PROGRESS
            item.toRow(
                role = state.role,
                sessionStudentId = state.sessionStudentId,
                canMoveUp = index > 0 && movable,
                canMoveDown = index < ordered.lastIndex && movable,
                timeline = timeline,
                section = sections[item.id] ?: HomeworkListSection.MAIN,
                missedDayCount = groups.endedMissedDayCounts[item.id] ?: 0,
            )
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
        if (!canReorder(current.role, rows[index].homework, current.sessionStudentId)) {
            sendMessage(PERMISSION_DENIED_HINT)
            return
        }
        if (rows[index].lockedWorkInProgress) {
            // 进行中锁定：与仓库同源兜底（UI 已隐藏/禁用调序入口）
            sendMessage(WORK_IN_PROGRESS_LOCKED_HINT)
            return
        }
        val swapped = rows.toMutableList().apply {
            val moved = removeAt(index)
            add(targetIndex, moved)
        }
        _uiState.update { state ->
            val timelineOf: (HomeworkItem) -> HomeworkRowTimeline = { item ->
                rows.firstOrNull { row -> row.homework.id == item.id }?.timeline
                    ?: HomeworkRowTimeline(
                        todayState = null,
                        stageProgress = null,
                        isTodayActionable = false,
                    )
            }
            val sections = rows.associate { row -> row.homework.id to row.section }
            state.copy(
                // 重新推导整行（进行中锁定与越权项保持不可调序，避免本地交换绕过守卫）
                items = swapped.mapIndexed { i, row ->
                    val movable = canReorder(state.role, row.homework, state.sessionStudentId) &&
                        !row.lockedWorkInProgress
                    row.homework.toRow(
                        role = state.role,
                        sessionStudentId = state.sessionStudentId,
                        canMoveUp = i > 0 && movable,
                        canMoveDown = i < swapped.lastIndex && movable,
                        timeline = timelineOf(row.homework),
                        section = sections[row.homework.id] ?: HomeworkListSection.MAIN,
                        missedDayCount = row.missedDayCount,
                    )
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
            // 删除成功才外抛收尾事件：framework 据此取消该作业的到点提醒，
            // 避免作业已删除、旧闹钟仍触发指向不存在作业的提醒（失败静默，不影响主流程）
            if (result is HomeworkOperationResult.Success) {
                _events.send(HomeworkListEvent.Deleted(homeworkId = target.id))
            }
        }
    }

    // ---- 状态流转（本页提供「开始作业」「标记完成」与「撤销完成」） ----

    /**
     * 开始作业：把「开始」意图写入「进行中」状态，具体计时由 timer 模块（framework 接线）承接。
     *
     * 本页只负责状态推进与可读提示，不依赖 timer 模块；导航意图经
     * [HomeworkListCallbacks.onStartHomework] 回调外抛，未接线时为空实现。
     */
    fun onStartHomeworkClick(homeworkId: Long) {
        val row = _uiState.value.items.firstOrNull { it.homework.id == homeworkId } ?: return
        if (!row.canStart) {
            sendMessage(EXECUTE_PERMISSION_DENIED_HINT)
            return
        }
        val role = _uiState.value.role ?: return
        viewModelScope.launch {
            val result = homeworkRepository.startProgress(homeworkId, role)
            if (result is HomeworkStatusResult.Success) {
                // 开始作业写入「作业每天详情」（当天 → 进行中），刷新后行状态即时正确
                refreshDailyRecordsNow()
            }
            sendMessage(result.toStartMessage())
        }
    }

    /** 标记完成：待完成/进行中 → 已完成（执行权：本人名下作业；阶段作业为「完成今天」） */
    fun onCompleteClick(homeworkId: Long) {
        val row = _uiState.value.items.firstOrNull { it.homework.id == homeworkId } ?: return
        if (!row.canComplete) {
            // 入口不可用统一按执行权提示：阶段作业今天已完成/在窗口外时按钮本就不渲染
            sendMessage(EXECUTE_PERMISSION_DENIED_HINT)
            return
        }
        val role = _uiState.value.role ?: return
        viewModelScope.launch {
            val result = homeworkRepository.complete(homeworkId, role)
            if (result is HomeworkStatusResult.Success) {
                // 完成写入「作业每天详情」（当天 → 已完成），刷新后今日状态与阶段进度即时更新
                refreshDailyRecordsNow()
            }
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
            if (result is HomeworkStatusResult.Success) {
                // 撤销完成同样回退「作业每天详情」（当天 → 未开始）
                refreshDailyRecordsNow()
            }
            val message = when (result) {
                is HomeworkStatusResult.Success -> "已撤销完成"
                HomeworkStatusResult.NotFound -> "作业不存在，可能已被删除"
                HomeworkStatusResult.PermissionDenied -> EXECUTE_PERMISSION_DENIED_HINT
                is HomeworkStatusResult.IllegalTransition -> "当前状态不支持该操作"
            }
            sendMessage(message)
        }
    }

    /** 刷新「作业每天详情」并重算全部行（挂起：由状态流转链路内联调用，保证顺序） */
    private suspend fun refreshDailyRecordsNow() {
        val state = _uiState.value
        val studentId = state.studentId ?: return
        val records = runCatching { homeworkRepository.dailyRecordsOf(studentId) }.getOrDefault(emptyMap())
        _uiState.update { it.copy(items = buildRows(state.allItems, records, it)) }
    }

    /** 提示：受权限限制的操作（UI 已禁用按钮，此处兜底） */
    fun onPermissionDenied() {
        sendMessage(PERMISSION_DENIED_HINT)
    }

    /** 家长视角「已完成历史」分组展开/折叠（默认折叠） */
    fun onToggleCompletedHistory() {
        _uiState.update { it.copy(completedExpanded = !it.completedExpanded) }
    }

    // ---- 私有工具 ----

    /** 「开始作业」结果 → 可读文案（成功提示不关闭页面，计时由 timer 模块承接） */
    private fun HomeworkStatusResult.toStartMessage(): String = when (this) {
        is HomeworkStatusResult.Success -> "已开始该作业"
        HomeworkStatusResult.NotFound -> "作业不存在，可能已被删除"
        HomeworkStatusResult.PermissionDenied -> EXECUTE_PERMISSION_DENIED_HINT
        is HomeworkStatusResult.IllegalTransition -> "当前状态不支持开始作业"
    }

    private fun sendMessage(message: String) {
        viewModelScope.launch { _events.send(HomeworkListEvent.ShowMessage(message)) }
    }

    /** 改删/调序权限：学生仅可调序「本人名下且自己录入」的作业（家长全可，归属由仓库按会话圈定） */
    private fun canReorder(role: Role?, item: HomeworkItem, sessionStudentId: Long?): Boolean =
        role != null && HomeworkValidators.canReorder(item, role, sessionStudentId)

    private fun HomeworkItem.toRow(
        role: Role?,
        sessionStudentId: Long?,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        timeline: HomeworkRowTimeline,
        section: HomeworkListSection,
        missedDayCount: Int,
    ): HomeworkRowUiState = toRowUiState(
        role = role,
        sessionStudentId = sessionStudentId,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
        timeline = timeline,
        section = section,
        missedDayCount = missedDayCount,
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
    /**
     * 最近一次清单快照的全部作业（未按角色口径过滤）：状态流转后刷新每天详情时用它重算，
     * 避免用「已过滤/已分组」的行反推清单导致不可见项丢失。
     */
    val allItems: List<HomeworkItem> = emptyList(),
    /** 「已完成历史」分组是否展开（家长视角；默认折叠，避免历史项挤占当天清单） */
    val completedExpanded: Boolean = false,
    val deleteTarget: HomeworkItem? = null,
    val deleting: Boolean = false,
) {

    /** 是否为空清单（非加载、非会话/学生缺失且无数据） */
    val isEmpty: Boolean get() = !loading && !missingSession && !missingStudent && items.isEmpty()

    /**
     * 「查看盘点」入口的目标学生 id：清单当前展示的学生（学生本人 / 家长选定的孩子）。
     *
     * 为 null（加载中、会话失效、家长未选定学生）时顶部入口置灰不可点——
     * 盘点必须绑定到具体学生，故不在这里另行兜底推断。
     */
    val statsStudentId: Long? get() = studentId.takeIf { !missingSession && !missingStudent }
}

/**
 * 清单分区（角色口径分组）：
 * - [MAIN]：进行区（学生视角下为「今天」的全部作业）；
 * - [COMPLETED_HISTORY]：已完成历史（家长视角折叠展示）；
 * - [ENDED]：已结束（阶段已结束但仍有未完成天，标注未完成天数）。
 */
enum class HomeworkListSection {
    MAIN,
    COMPLETED_HISTORY,
    ENDED,
}

/**
 * 清单条目状态：作业数据 + 按角色计算的权限开关 + 每日维度（今日状态 / 阶段进度）。
 *
 * @param timeline 由作业每天详情推导的今日状态与阶段进度（[HomeworkListRoleScope.rowTimeline]）
 * @param section 分区归属（家长视角分组用；学生视角全部为 [HomeworkListSection.MAIN]）
 * @param missedDayCount 阶段作业的未完成天数（缺卡天数；「已结束」分组标注用）
 */
data class HomeworkRowUiState(
    val homework: HomeworkItem,
    /** 改删/调序权限：学生仅可操作自己录入的作业（[HomeworkValidators.canModify]） */
    val canModify: Boolean,
    val canDelete: Boolean,
    /** 执行权：可进入时间设定（本人名下作业，且未完成、未锁定进行中） */
    val canSchedule: Boolean,
    /**
     * 执行权：可「开始作业 / 继续计时」——
     * 待完成（PENDING，可执行）或进行中（IN_PROGRESS，继续计时）的本人名下作业；
     * 已记录（RECORDED）需先排定时间，已完成（COMPLETED）不可再开始，故均不展示该入口。
     */
    val canStart: Boolean,
    /** 执行权：可推进状态至完成（本人名下作业，且处于待完成/进行中；阶段作业还要求今天在阶段内且未缺卡） */
    val canComplete: Boolean,
    /** 执行权：已完成项可撤销完成（本人名下作业） */
    val canReopen: Boolean,
    /** 进行中锁定（需求假设 C）：已开始的作业不允许调整优先级与时间，UI 隐藏操作并给出提示 */
    val lockedWorkInProgress: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    /** 每日维度：今日状态 + 阶段进度（默认空，兼容既有构造调用） */
    val timeline: HomeworkRowTimeline = EMPTY_TIMELINE,
    /** 分区归属（默认进行区，兼容既有构造调用） */
    val section: HomeworkListSection = HomeworkListSection.MAIN,
    /** 阶段作业的未完成天数（缺卡天数） */
    val missedDayCount: Int = 0,
) {

    /** 今日状态文案（如「今日：已完成」） */
    val todayStateText: String? get() = timeline.todayStateText

    /** 阶段进度文案（如「阶段进度：已打卡 3/7 天」；非阶段作业为 null） */
    val stageProgressText: String? get() = timeline.progressText

    /** 「已结束」分组的未完成天数文案 */
    val missedDaysText: String?
        get() = if (section == HomeworkListSection.ENDED && missedDayCount > 0) {
            "阶段已结束，有 $missedDayCount 天未完成"
        } else {
            null
        }
}

/** 空每日维度（非每日数据场景的占位，保证行模型可独立构造） */
internal val EMPTY_TIMELINE = HomeworkRowTimeline(
    todayState = null,
    stageProgress = null,
    isTodayActionable = false,
)

/**
 * 由作业项与当前会话推导清单条目的可操作性（纯函数，便于单测；仓库层同源兜底）。
 *
 * 两个权限维度刻意分开：
 * - 改删/调序用 [HomeworkValidators.canModify]（学生仅自己录入项，家长全部）；
 * - 时间排定与状态流转用 [HomeworkValidators.canOperate]（学生可为本人名下全部作业，
 *   含家长布置的，保证「家长布置 → 学生排定时间 → 计时完成」主闭环可用）；
 * 「标记完成」仅在待完成/进行中可用（已记录需先排定时间，见 [HomeworkStatus] 流转表）；
 * 阶段作业额外要求「今天落在阶段覆盖窗口内且尚未完成」——阶段窗口外（尚未开始/已结束）
 * 与今天已完成时都不再开放「开始作业 / 标记完成」，与仓库「完成今天」的口径同源；
 * 今天已完成时改为开放「撤销完成」（撤销的是**今天**的完成记录，整条回到待完成）。
 *
 * **缺卡（[HomeworkDayState.MISSED]）不是「今天」可能的状态**：按缺卡规则，当天即便已过每日截止
 * 时刻仍可完成（[StageDayRecords]），只有**跨入次日**才判为未完成——因此行投影里不存在
 * 「今天缺卡」这一态，也不再有针对它的防御分支与提示文案（缺卡只体现在阶段进度的未完成天数上）。
 *
 * 进行中锁定（[lockedWorkInProgress]）：状态为「进行中」时不允许调序与改时间，
 * 故 [canSchedule] 为 false、调序按钮同样不可用（调序开关见 [HomeworkRowUiState.canMoveUp]/
 * [canMoveDown]，由调用方按「列表位置 + 改删权 + 该标记」共同决定）。
 */
internal fun HomeworkItem.toRowUiState(
    role: Role?,
    sessionStudentId: Long?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    timeline: HomeworkRowTimeline = EMPTY_TIMELINE,
    section: HomeworkListSection = HomeworkListSection.MAIN,
    missedDayCount: Int = 0,
): HomeworkRowUiState {
    val executable = role != null && HomeworkValidators.canOperate(this, role, sessionStudentId)
    val locked = status == HomeworkStatus.IN_PROGRESS
    val unfinished = status != HomeworkStatus.COMPLETED
    val statusAllowsComplete =
        status == HomeworkStatus.PENDING || status == HomeworkStatus.IN_PROGRESS
    // 阶段作业：只有「今天在阶段窗口内且尚未完成」才谈得上开始/完成今天（口径同 StageDayRecords：
    // 今天到点后仍可完成、跨入次日才判未完成；今天已完成则不再重复完成）；
    // 每天详情缺失（默认空投影）时不误伤既有口径。当天作业不受本维度约束（由状态列表达）。
    val dayAllowsAction = if (isStage) timeline.todayState?.isActionable ?: true else true
    // 阶段作业的时间排定同样受阶段窗口约束：阶段尚未开始的将来日与已结束的阶段都不该再排定时间
    // （当天仍在阶段内时照常可排定；每日数据缺失时不误伤既有口径）
    val dayAllowsSchedule = if (isStage) timeline.todayState != HomeworkDayState.NOT_ARRIVED else true
    return HomeworkRowUiState(
        homework = this,
        canModify = role != null && HomeworkValidators.canModify(this, role),
        canDelete = role != null && HomeworkValidators.canDelete(this, role),
        canSchedule = unfinished && !locked && executable && dayAllowsSchedule,
        canStart = executable && statusAllowsComplete && dayAllowsAction,
        canComplete = executable && statusAllowsComplete && dayAllowsAction,
        // 阶段作业的「撤销完成」= 撤销今天的完成（仅今天已有完成记录时开放）；
        // 当天作业沿用「整条已完成 → 可撤销完成」的既有口径
        canReopen = if (isStage) {
            executable && timeline.todayState == HomeworkDayState.COMPLETED
        } else {
            !unfinished && executable
        },
        lockedWorkInProgress = locked,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
        timeline = timeline,
        section = section,
        missedDayCount = missedDayCount,
    )
}

/** 作业清单页一次性事件 */
sealed interface HomeworkListEvent {

    /** 一次性提示（Snackbar 展示） */
    data class ShowMessage(val message: String) : HomeworkListEvent

    /**
     * 作业删除成功（仅成功时发出一次）：framework 据此取消该作业的到点提醒。
     *
     * 事件只携带 [homeworkId]，不含任何 timer 类型，homework 模块因此不依赖 timer。
     */
    data class Deleted(val homeworkId: Long) : HomeworkListEvent
}