package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.stats.data.StatsRepository
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.ItemDetail
import com.assignmate.app.stats.domain.StatsCalculations
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 单项详情页 ViewModel：**指定某一天**的预估时长 / 实际时长 / 暂停时长 + 当天状态 + 困难度侧面评估提示。
 *
 * 数据来源：[StatsRepository.itemDetail]（该作业**这一天的作业每天详情** +
 * [com.assignmate.app.stats.domain.DifficultyAssessor] 侧面评估 + 阶段打卡进度）。
 *
 * 状态流转（单数据流 [uiState]）：
 * 1. 初始 [ItemDetailPhase.LOADING]；
 * 2. [start] 注入路由参数（幂等）→ 解析目标学生（学生会话固定本人，防越权）
 *    与查看日期（缺省/非法回落「今天」，与当日盘点页同一套日期口径）；
 * 3. 成功 → [ItemDetailPhase.READY]；当天无执行痕迹时同样为 READY，
 *    页面据 [ItemDetailUiState.hasExecution] 展示「尚未开始/暂无数据」；
 * 4. 失败（作业不存在/越权/读取异常）→ [ItemDetailPhase.ERROR]，可经 [reload] 重试。
 *
 * 日期口径：详情按自然日取值（阶段作业的每一天彼此独立），页面文案按「今天 / 历史日」两种口径
 * 由 [ItemDetailUiState] 投影输出（与当日盘点页同一套约定）；「今天」由可注入 [Clock] 与业务时区
 * [ZoneId] 经 [StatsCalculations.epochDayOfToday] 解析，与仓库口径同源。
 */
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock,
    private val zoneId: ZoneId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ItemDetailUiState())
    val uiState: StateFlow<ItemDetailUiState> = _uiState.asStateFlow()

    /**
     * 页面入口：由页面入参传入目标学生、作业与要查看的自然日
     * （缺省 [StatsDestination.ARG_EPOCH_DAY_TODAY] 表示「今天」；framework 接线可注入历史日）。
     * 幂等：重复调用（重组/配置变更）不会重复取数。
     */
    fun start(
        routeStudentId: Long,
        homeworkId: Long,
        routeEpochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    ) {
        if (_uiState.value.started) {
            return
        }
        _uiState.update {
            it.copy(
                started = true,
                routeStudentId = routeStudentId,
                homeworkId = homeworkId,
                routeEpochDay = routeEpochDay,
            )
        }
        load()
    }

    /** 重新加载（错误态重试） */
    fun reload() {
        if (!_uiState.value.started) {
            return
        }
        load()
    }

    private fun load() {
        val state = _uiState.value
        // 日期解析：哨兵/非法一律回落「今天」，保持既有行为（路由未带日期时与旧版一致）
        val today = todayEpochDay()
        val epochDay = state.routeEpochDay.takeIf { it >= 0L } ?: today
        _uiState.update {
            it.copy(phase = ItemDetailPhase.LOADING, epochDay = epochDay, todayEpochDay = today, errorMessage = null)
        }
        viewModelScope.launch {
            val session = authRepository.currentSession()
            val targetId = resolveStatsStudentId(session, state.routeStudentId)
            if (targetId == null) {
                _uiState.update {
                    it.copy(
                        phase = if (session.role == null) {
                            ItemDetailPhase.NO_SESSION
                        } else {
                            ItemDetailPhase.NO_STUDENT
                        },
                    )
                }
                return@launch
            }
            when (val result = statsRepository.itemDetail(state.homeworkId, epochDay)) {
                is StatsResult.Success -> _uiState.update {
                    it.copy(
                        phase = ItemDetailPhase.READY,
                        studentId = targetId,
                        detail = result.data,
                        errorMessage = null,
                    )
                }

                is StatsResult.Failure -> _uiState.update {
                    it.copy(
                        phase = ItemDetailPhase.ERROR,
                        studentId = targetId,
                        errorMessage = StatsErrorMessages.messageOf(result.reason),
                    )
                }
            }
        }
    }

    /** 「今天」的纪元日（直接复用领域层统一入口，与仓库口径同源） */
    private fun todayEpochDay(): Long = StatsCalculations.epochDayOfToday(zoneId, clock.currentTimeMillis())
}

/** 单项详情页阶段（页面据单一阶段字段渲染） */
enum class ItemDetailPhase {

    /** 取数中 */
    LOADING,

    /** 数据就绪（当天可能无执行痕迹，见 [ItemDetailUiState.hasExecution]） */
    READY,

    /** 无有效会话（未登录/已登出） */
    NO_SESSION,

    /** 家长会话未选定学生 */
    NO_STUDENT,

    /** 取数失败（作业不存在/越权/读取异常），可重试 */
    ERROR,
}

/** 单项详情页状态（单数据流） */
data class ItemDetailUiState(
    val started: Boolean = false,
    val routeStudentId: Long = StatsDestination.ARG_STUDENT_ID_NONE,
    val homeworkId: Long = StatsDestination.ARG_HOMEWORK_ID_NONE,
    /** 路由传入的查看日期（[StatsDestination.ARG_EPOCH_DAY_TODAY] 表示「今天」） */
    val routeEpochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    val phase: ItemDetailPhase = ItemDetailPhase.LOADING,
    val studentId: Long? = null,
    /** 实际查看的自然日（业务时区纪元日）：缺省时为「今天」 */
    val epochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    /** 当前自然日（由可注入时钟 + 业务时区解析），用于区分「今天」与历史日文案 */
    val todayEpochDay: Long = StatsDestination.ARG_EPOCH_DAY_TODAY,
    val detail: ItemDetail? = null,
    val errorMessage: String? = null,
) {

    /** 是否处于取数中 */
    val loading: Boolean get() = phase == ItemDetailPhase.LOADING

    /** 当天是否有执行痕迹（false 时页面展示「尚未开始/暂无数据」） */
    val hasExecution: Boolean get() = detail?.hasExecution == true

    /** 作业内容（无数据时回退占位文案） */
    val contentText: String get() = detail?.content ?: "作业详情"

    /** 是否正在查看「今天」（查看历史日时为 false，文案改用具体日期） */
    val isToday: Boolean get() = epochDay == todayEpochDay

    /** 查看日期文案（`yyyy-MM-dd`；日期尚未解析时为空串） */
    val dateText: String
        get() = epochDay.takeIf { it >= 0L }?.let { StatsCalculations.dateText(it) }.orEmpty()

    /**
     * 日期口径短语：「今天」或具体日期。
     * [loadingText] / [timeCardTitle] / [noteText] 三处文案共用本属性，页面不再自行判断日期。
     */
    private val scopeText: String get() = if (isToday) "今天" else dateText

    /** 加载态文案：与 [scopeText] 同源，历史日不出现「今天」措辞 */
    val loadingText: String get() = "正在读取${scopeText}的用时…"

    /** 用时卡标题：明确「哪一天」的用时（阶段作业每天分别查看，不再标注「累计」） */
    val timeCardTitle: String get() = "${scopeText}的用时情况"

    /** 口径说明文案：与 [timeCardTitle] 同源，避免家长误读为累计/整段用时 */
    val noteText: String get() = "只统计${scopeText}的用时（阶段作业每天分别计算）"

    /** 当天状态标签 */
    val statusText: String get() = detail?.dayStatus?.let { StatsErrorMessages.dayStatusLabel(it) }.orEmpty()

    /** 阶段打卡进度文案（非阶段作业或尚无详情时为 null） */
    val stageProgressText: String?
        get() = detail?.stageProgress?.let { StatsErrorMessages.stageProgressText(it) }

    /** 预估时长文案（未设定时展示 [StatsErrorMessages.ESTIMATED_UNSET]） */
    val estimatedText: String
        get() = detail?.estimatedText ?: StatsErrorMessages.ESTIMATED_UNSET

    /** 当天实际时长文案（无执行痕迹时展示占位提示） */
    val elapsedText: String
        get() = when {
            detail == null -> StatsErrorMessages.NOT_STARTED
            !hasExecution -> StatsErrorMessages.NOT_STARTED
            else -> StatsCalculations.durationText(detail.elapsedMillis)
        }

    /** 当天暂停时长文案（无执行痕迹时展示占位提示） */
    val pausedText: String
        get() = when {
            detail == null -> StatsErrorMessages.NOT_STARTED
            !hasExecution -> StatsErrorMessages.NOT_STARTED
            else -> StatsCalculations.durationText(detail.pausedTotalMillis)
        }

    /** 困难度等级标签 */
    val difficultyLabel: String get() = detail?.difficulty?.label.orEmpty()

    /** 困难度侧面评估提示（无执行痕迹时为「尚未开始」文案） */
    val assessmentHint: String get() = detail?.assessmentHint ?: StatsErrorMessages.NOT_STARTED
}
