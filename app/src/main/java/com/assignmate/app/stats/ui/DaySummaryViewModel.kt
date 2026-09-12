package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.stats.data.StatsFailure
import com.assignmate.app.stats.data.StatsRepository
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.DaySummary
import com.assignmate.app.stats.domain.StatsCalculations
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * 当日盘点页 ViewModel：完成率（进度 + 百分比）、暂停次数、暂停总时长、暂停最久作业。
 *
 * 数据来源：[StatsRepository.summarizeDay]（按学生维度聚合 homework 清单与 timer 会话/暂停明细）。
 *
 * 状态流转（单数据流 [uiState] + 一次性事件 [events]）：
 * 1. 初始 [DaySummaryPhase.LOADING]；
 * 2. [start] 注入路由参数（幂等）→ 解析目标学生：
 *    学生会话固定取本人（防越权），家长会话取路由参数，均缺失则 [DaySummaryPhase.NO_SESSION] /
 *    [DaySummaryPhase.NO_STUDENT]；
 * 3. 目标学生确定后按**盘点日期**取数 → [DaySummaryPhase.READY]（数据为空时页面据
 *    [DaySummaryUiState.isEmpty] 展示空态）；
 * 4. 仓库失败（越权/读取异常）→ [DaySummaryPhase.ERROR]（文案由 [StatsErrorMessages] 映射），
 *    可经 [reload] 重试。
 *
 * 盘点日期口径：路由参数 `epochDay` 指定要盘点的自然日（历史查询页「查看这一天的盘点」据此进入），
 * 缺省/非法（[StatsDestination.ARG_EPOCH_DAY_TODAY]）回落「今天」；
 * 「今天」由可注入 [Clock] 与业务时区 [ZoneId] 经 [StatsCalculations.epochDayOfToday] 解析，
 * 与仓库的当日窗口换算（[StatsCalculations.dayStartMillisOf]）同源，避免跨零点/时区口径漂移。
 */
@HiltViewModel
class DaySummaryViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DaySummaryUiState())
    val uiState: StateFlow<DaySummaryUiState> = _uiState.asStateFlow()

    private val _events = Channel<DaySummaryEvent>(Channel.BUFFERED)
    val events: Flow<DaySummaryEvent> = _events.receiveAsFlow()

    /**
     * 页面入口：由路由参数 `stats/day/{studentId}?epochDay={epochDay}` 传入目标学生与要盘点的日期
     * （框架经 [StatsDestination.studentIdOf] / [StatsDestination.epochDayOf] 解析，
     * 学生传 0 表示按会话解析本人，日期传 [StatsDestination.ARG_EPOCH_DAY_TODAY] 表示「今天」）。
     *
     * 历史查询页「查看这一天的盘点」即经 [routeEpochDay] 传入所选日期，因此本页可展示任意历史日。
     * 幂等：重复调用（重组/配置变更）不会重复取数。
     */
    fun start(
        routeStudentId: Long,
        routeEpochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    ) {
        if (_uiState.value.started) {
            return
        }
        _uiState.update {
            it.copy(started = true, routeStudentId = routeStudentId, routeEpochDay = routeEpochDay)
        }
        load()
    }

    /** 重新加载（错误态重试入口；也用于从详情页返回后刷新） */
    fun reload() {
        if (!_uiState.value.started) {
            return
        }
        load()
    }

    /** 进入单项详情（主页面的「查看详情」回调；事件由页面转发为导航意图） */
    fun openItemDetail(homeworkId: Long) {
        if (homeworkId <= 0L) {
            return
        }
        viewModelScope.launch { _events.send(DaySummaryEvent.OpenItemDetail(homeworkId)) }
    }

    /** 进入历史查询（主页面的「查看历史」回调） */
    fun openHistory() {
        val studentId = _uiState.value.studentId ?: return
        viewModelScope.launch { _events.send(DaySummaryEvent.OpenHistory(studentId)) }
    }

    private fun load() {
        val routeStudentId = _uiState.value.routeStudentId
        val routeEpochDay = _uiState.value.routeEpochDay
        // 日期解析：哨兵/非法一律回落「今天」，保持既有行为（路由未带日期时与旧版一致）
        val today = todayEpochDay()
        val epochDay = routeEpochDay.takeIf { it >= 0L } ?: today
        _uiState.update {
            it.copy(phase = DaySummaryPhase.LOADING, epochDay = epochDay, todayEpochDay = today, errorMessage = null)
        }
        viewModelScope.launch {
            val session = authRepository.currentSession()
            val targetId = resolveStatsStudentId(session, routeStudentId)
            if (targetId == null) {
                // 会话失效与「家长未选定学生」是两类不同提示，分别置位供页面区分展示
                _uiState.update {
                    it.copy(
                        phase = if (session.role == null) {
                            DaySummaryPhase.NO_SESSION
                        } else {
                            DaySummaryPhase.NO_STUDENT
                        },
                    )
                }
                return@launch
            }
            when (val result = statsRepository.summarizeDay(targetId, epochDay)) {
                is StatsResult.Success -> _uiState.update {
                    it.copy(
                        phase = DaySummaryPhase.READY,
                        studentId = targetId,
                        studentName = authRepository.getStudent(targetId)?.name,
                        epochDay = result.data.epochDay,
                        summary = result.data,
                        errorMessage = null,
                    )
                }

                is StatsResult.Failure -> _uiState.update {
                    it.copy(
                        phase = DaySummaryPhase.ERROR,
                        studentId = targetId,
                        errorMessage = StatsErrorMessages.messageOf(result.reason),
                    )
                }
            }
        }
    }

    /** 「今天」的纪元日（直接复用领域层统一入口，与仓库当日窗口换算同源） */
    private fun todayEpochDay(): Long = StatsCalculations.epochDayOfToday(zoneId, clock.currentTimeMillis())
}

/** 当日盘点页阶段（页面据单一阶段字段渲染，避免多布尔位互相矛盾） */
enum class DaySummaryPhase {

    /** 取数中 */
    LOADING,

    /** 数据就绪（可能为空态，见 [DaySummaryUiState.isEmpty]） */
    READY,

    /** 无有效会话（未登录/已登出） */
    NO_SESSION,

    /** 家长会话未选定学生 */
    NO_STUDENT,

    /** 取数失败（越权/读取异常），可重试 */
    ERROR,
}

/** 当日盘点页状态（单数据流） */
data class DaySummaryUiState(
    val started: Boolean = false,
    /** 路由传入的目标学生（0 表示未指定，由会话解析） */
    val routeStudentId: Long = StatsDestination.ARG_STUDENT_ID_NONE,
    /** 路由传入的盘点日期（[StatsDestination.ARG_EPOCH_DAY_TODAY] 表示「今天」） */
    val routeEpochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    val phase: DaySummaryPhase = DaySummaryPhase.LOADING,
    val studentId: Long? = null,
    val studentName: String? = null,
    /** 实际盘点的自然日（UTC 纪元日）：缺省时为「今天」，历史入口进入时为所选日期 */
    val epochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    /** 当前自然日（由可注入时钟 + 业务时区解析），用于区分「今天」与历史日的标题文案 */
    val todayEpochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    val summary: DaySummary? = null,
    val errorMessage: String? = null,
) {

    /** 是否处于取数中 */
    val loading: Boolean get() = phase == DaySummaryPhase.LOADING

    /** 是否正在盘点「今天」（历史入口进入某历史日时为 false，标题据此显示具体日期） */
    val isToday: Boolean get() = epochDay == todayEpochDay

    /** 盘点日期文案（`yyyy-MM-dd`；日期尚未解析时为空串） */
    val dateText: String
        get() = epochDay.takeIf { it >= 0L }?.let { StatsCalculations.dateText(it) }.orEmpty()

    /**
     * 页面标题文案：「今天」与历史日两种口径（本页可按 epochDay 展示任意历史日，
     * 历史入口进入时必须显示具体日期，避免与「今天」混淆）。
     *
     * [titleText] / [loadingText] / [itemListTitle] / [emptyText] 四处文案共用 [isToday] 这一个分支，
     * 页面不再各自判断日期，口径不会漂移。
     */
    val titleText: String
        get() = if (isToday) "今天的作业盘点" else "$dateText 的作业盘点"

    /** 加载态文案：历史日展示该日期，与 [titleText] 同源 */
    val loadingText: String
        get() = if (isToday) "正在盘点今天的作业…" else "正在盘点 $dateText 的作业…"

    /** 当日作业清单卡标题（含项数）：历史日改用「这一天」，与 [titleText] 同源 */
    val itemListTitle: String
        get() {
            val scope = if (isToday) "今天" else "这一天"
            return "${scope}动过的作业（${summary?.totalCount ?: 0} 项）"
        }

    /** 空态文案：按盘点日期选择「今天 / 这一天」口径 */
    val emptyText: String
        get() = StatsErrorMessages.noDataText(isToday)

    /** 当日是否无作业纳入盘点口径（空态） */
    val isEmpty: Boolean get() = phase == DaySummaryPhase.READY && summary?.isEmpty == true

    /** 完成率展示文案（进度分子/分母） */
    val progressText: String
        get() = summary?.let { "已完成 ${it.completedCount} / ${it.totalCount} 项" } ?: "已完成 0 / 0 项"

    /** 完成率百分比文案（如 62.5%） */
    val percentText: String
        get() = summary?.let { StatsCalculations.percentText(it.completionRate) } ?: "0.0%"

    /** 完成率进度（0f..1f，供 LinearProgressIndicator 使用） */
    val progress: Float
        get() = (summary?.completionRate ?: 0.0).toFloat().coerceIn(0f, 1f)

    /** 暂停次数文案 */
    val pauseCountText: String
        get() = summary?.let { "暂停 ${it.pauseCount} 次" } ?: "暂停 0 次"

    /** 暂停总时长文案（如「12 分钟」） */
    val pausedTotalText: String
        get() = summary?.let { StatsCalculations.durationText(it.pausedTotalMillis) } ?: "不到 1 分钟"

    /** 暂停最久作业文案；当日无暂停时为 null（页面按需展示） */
    val mostPausedText: String?
        get() = summary?.mostPausedItem?.let { paused ->
            "「${paused.content}」暂停最久：${StatsCalculations.durationText(paused.pausedMillis)}（${paused.pauseCount} 次）"
        }
}

/** 当日盘点页一次性事件（Channel 投递，避免重组误触发导航） */
sealed interface DaySummaryEvent {

    /** 打开单项详情页 */
    data class OpenItemDetail(val homeworkId: Long) : DaySummaryEvent

    /** 打开历史查询页 */
    data class OpenHistory(val studentId: Long) : DaySummaryEvent
}