package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.stats.data.StatsRepository
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.DaySummary
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.StatsCalculations
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
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
 * 历史查询页 ViewModel：按日期（或日期范围）查看历史完成情况（学生/家长视角均可查看名下学生）。
 *
 * 数据来源：[StatsRepository.history]（按天聚合**作业每天详情**，逐日盘点，按日期倒序；
 * 单日与范围口径同源，均走 [StatsCalculations.summarizeDay]）。阶段作业在**覆盖期内每一天都应做**
 * （由覆盖范围推导，见 [StatsCalculations.shouldDoOn]；每天详情是按需写入的，不逐日预建），
 * 因此其覆盖期内每一天都会出现在历史里，行内同时体现「当日是否完成」与「阶段打卡进度」。
 *
 * 状态流转（单数据流 [uiState] + 一次性事件 [events]）：
 * 1. 初始 [HistoryPhase.LOADING]；
 * 2. [start] 注入路由参数（幂等，默认查「今天」）→ 解析目标学生（学生会话固定本人，防越权）；
 * 3. 学生确定后按 [HistoryQuery] 取数 → [HistoryPhase.READY]（无记录时页面据 [HistoryUiState.isEmpty] 展示空态）；
 * 4. 日期变更（[setRange]）重新取数；非法范围（结束日早于开始日）就地提示且不改动数据；
 * 5. 仓库失败 → [HistoryPhase.ERROR]，可经 [reload] 重试。
 *
 * 范围上限：超过 [com.assignmate.app.stats.domain.StatsConstants.MAX_HISTORY_DAYS] 天时，
 * 本 ViewModel 在**存储查询前**即调用 [HistoryQuery.normalized] 收敛结束日，
 * 因此 [HistoryUiState.rangeText] 展示的范围与仓库实际查询范围完全一致，
 * 避免「选了 200 天、页面却仍显示 200 天而只返回 92 天」的困惑
 * （仓库层仍保留一次收敛兜底，防止绕过 UI 直接调用）。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val _events = Channel<HistoryEvent>(Channel.BUFFERED)
    val events: Flow<HistoryEvent> = _events.receiveAsFlow()

    /**
     * 页面入口：由路由参数 `stats/history/{studentId}?fromEpochDay=&toEpochDay=` 传入
     * （参数缺省为「今天」）。幂等：重复调用不会重复取数。
     *
     * 查询范围在建流时即按 [HistoryQuery.normalized] 收敛（超上限截断结束日），
     * 使页面展示的范围与实际查询范围一致。
     */
    fun start(routeStudentId: Long, fromEpochDay: Long, toEpochDay: Long) {
        if (_uiState.value.started) {
            return
        }
        val today = todayEpochDay()
        val from = fromEpochDay.takeIf { it >= 0L } ?: today
        val to = toEpochDay.takeIf { it >= 0L } ?: from
        _uiState.update {
            it.copy(
                started = true,
                routeStudentId = routeStudentId,
                query = HistoryQuery(from, to).normalized(),
            )
        }
        load()
    }

    /** 重新加载（错误态重试 / 返回页面后刷新） */
    fun reload() {
        if (!_uiState.value.started) {
            return
        }
        load()
    }

    /**
     * 修改查询范围：非法范围（结束日早于开始日）就地给出提示且不改动查询，
     * 避免把非法入参送到仓库（仓库同样会拒绝，但 UI 先拦一层体验更好）；
     * 合法范围按 [HistoryQuery.normalized] 收敛后存储，保证页面展示范围 = 实际查询范围。
     */
    fun setRange(fromEpochDay: Long, toEpochDay: Long) {
        val query = HistoryQuery(fromEpochDay, toEpochDay)
        if (!query.isValid) {
            _uiState.update { it.copy(rangeError = StatsErrorMessages.INVALID_RANGE) }
            return
        }
        _uiState.update { it.copy(query = query.normalized(), rangeError = null) }
        load()
    }

    /** 选中某一天（页面点击历史条目 → 查看该日详情；事件由页面转发为导航意图） */
    fun selectDay(epochDay: Long) {
        viewModelScope.launch { _events.send(HistoryEvent.OpenDaySummary(epochDay)) }
    }

    private fun load() {
        val state = _uiState.value
        val query = state.query
        _uiState.update { it.copy(phase = HistoryPhase.LOADING, errorMessage = null) }
        viewModelScope.launch {
            val session = authRepository.currentSession()
            val targetId = resolveStatsStudentId(session, state.routeStudentId)
            if (targetId == null) {
                _uiState.update {
                    it.copy(
                        phase = if (session.role == null) {
                            HistoryPhase.NO_SESSION
                        } else {
                            HistoryPhase.NO_STUDENT
                        },
                    )
                }
                return@launch
            }
            when (val result = statsRepository.history(targetId, query)) {
                is StatsResult.Success -> _uiState.update {
                    it.copy(
                        phase = HistoryPhase.READY,
                        studentId = targetId,
                        studentName = authRepository.getStudent(targetId)?.name,
                        summaries = result.data,
                        total = StatsCalculations.totalOf(result.data).takeIf { result.data.isNotEmpty() },
                        errorMessage = null,
                    )
                }

                is StatsResult.Failure -> _uiState.update {
                    it.copy(
                        phase = HistoryPhase.ERROR,
                        studentId = targetId,
                        errorMessage = StatsErrorMessages.messageOf(result.reason),
                    )
                }
            }
        }
    }

    /**
     * 「今天」的纪元日：直接复用领域层统一换算入口（可注入时钟 + 业务时区），
     * 与 [com.assignmate.app.stats.data.StatsRepositoryImpl] 的当日窗口、
     * [DaySummaryViewModel] 的「今天」判定同源，避免多套换算漂移。
     */
    private fun todayEpochDay(): Long =
        StatsCalculations.epochDayOfToday(zoneId, clock.currentTimeMillis())
}

/** 历史查询页阶段（页面据单一阶段字段渲染） */
enum class HistoryPhase {

    /** 取数中 */
    LOADING,

    /** 数据就绪（可能为空态，见 [HistoryUiState.isEmpty]） */
    READY,

    /** 无有效会话（未登录/已登出） */
    NO_SESSION,

    /** 家长会话未选定学生 */
    NO_STUDENT,

    /** 取数失败（越权/读取异常/入参非法），可重试 */
    ERROR,
}

/** 历史查询页状态（单数据流） */
data class HistoryUiState(
    val started: Boolean = false,
    val routeStudentId: Long = StatsDestination.ARG_STUDENT_ID_NONE,
    val phase: HistoryPhase = HistoryPhase.LOADING,
    val studentId: Long? = null,
    val studentName: String? = null,
    /**
     * 当前查询范围。缺省值经 `normalized()` 收敛，保证「状态里的 query」与「实际下发仓库的 query」
     * 在任何时刻都是同一口径（含 `start()` 之前的初始态，避免暴露未收敛范围）。
     */
    val query: HistoryQuery = HistoryQuery(0L).normalized(),
    /** 逐日盘点（按日期倒序，最近的一天在前） */
    val summaries: List<DaySummary> = emptyList(),
    /** 范围合计（无记录时为 null） */
    val total: DaySummary? = null,
    val errorMessage: String? = null,
    /** 范围选择提示（结束日早于开始日等） */
    val rangeError: String? = null,
) {

    /** 是否处于取数中 */
    val loading: Boolean get() = phase == HistoryPhase.LOADING

    /** 范围内是否无任何记录（空态） */
    val isEmpty: Boolean get() = phase == HistoryPhase.READY && summaries.isEmpty()

    /** 查询范围展示文案（单日展示 `yyyy-MM-dd`，范围展示 `起 ~ 止`） */
    val rangeText: String
        get() = if (query.startEpochDay == query.endEpochDay) {
            StatsCalculations.dateText(query.startEpochDay)
        } else {
            "${StatsCalculations.dateText(query.startEpochDay)} ~ ${StatsCalculations.dateText(query.endEpochDay)}"
        }

    /** 范围合计文案（无记录时为 null） */
    val totalText: String?
        get() = total?.let {
            "合计完成 ${it.completedCount} / ${it.totalCount} 项 · 暂停 ${it.pauseCount} 次 · " +
                StatsCalculations.durationText(it.pausedTotalMillis)
        }

    /** 逐日条目（日期文案 + 盘点，供 LazyColumn 直接渲染） */
    val rows: List<HistoryRow>
        get() = summaries.map { day -> HistoryRow(epochDay = day.epochDay, summary = day) }
}

/** 历史查询页逐日条目 */
data class HistoryRow(
    val epochDay: Long,
    val summary: DaySummary,
) {

    /** 日期文案（`yyyy-MM-dd`） */
    val dateText: String get() = LocalDate.ofEpochDay(epochDay).toString()

    /** 完成情况文案（如「已完成 3 / 5 项（60.0%）」） */
    val progressText: String
        get() {
            val percent = StatsCalculations.percentText(summary.completionRate)
            return "已完成 ${summary.completedCount} / ${summary.totalCount} 项（$percent）"
        }

    /** 暂停情况文案（如「暂停 2 次 · 12 分钟」） */
    val pauseText: String
        get() = "暂停 ${summary.pauseCount} 次 · ${StatsCalculations.durationText(summary.pausedTotalMillis)}"

    /**
     * 阶段打卡进度文案（当天涉及的阶段作业；当天无阶段作业时为 null）。
     *
     * 阶段作业只有一条作业项，作业项状态无法表达「某一天做没做」，
     * 因此历史行同时给出「当日完成情况」（[progressText]）与整段「打卡进度」。
     */
    val stageText: String?
        get() = summary.stages
            .takeIf { it.isNotEmpty() }
            ?.joinToString("，") { stage -> "「${stage.content}」${StatsErrorMessages.stageProgressText(stage)}" }
}

/**
 * 历史查询页一次性事件（Channel 投递）：
 * 本期只提供「进入某一天的盘点」，不提供历史条目直达单项详情的入口。
 */
sealed interface HistoryEvent {

    /** 打开某一天的当日盘点页 */
    data class OpenDaySummary(val epochDay: Long) : HistoryEvent
}