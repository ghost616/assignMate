package com.assignmate.app.stats.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.stats.data.StatsRepository
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.ItemDetail
import com.assignmate.app.stats.domain.StatsCalculations
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 单项详情页 ViewModel：预估时长 / 实际时长 / 暂停时长 + 困难度侧面评估提示。
 *
 * 数据来源：[StatsRepository.itemDetail]（该作业全部执行会话的已用时长合计 + 暂停明细 +
 * [com.assignmate.app.stats.domain.DifficultyAssessor] 侧面评估）。
 *
 * 状态流转（单数据流 [uiState]）：
 * 1. 初始 [ItemDetailPhase.LOADING]；
 * 2. [start] 注入路由参数（幂等）→ 解析目标学生（学生会话固定本人，防越权）；
 * 3. 成功 → [ItemDetailPhase.READY]；无执行记录时同样为 READY，
 *    页面据 [ItemDetailUiState.hasExecution] 展示「尚未开始/暂无数据」；
 * 4. 失败（作业不存在/越权/读取异常）→ [ItemDetailPhase.ERROR]，可经 [reload] 重试。
 *
 * 展示口径：实际时长/暂停时长统一经 [StatsCalculations.durationText] 换算，
 * 困难度提示由领域层给出（本层不做二次判断，避免规则漂移）。
 */
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ItemDetailUiState())
    val uiState: StateFlow<ItemDetailUiState> = _uiState.asStateFlow()

    /**
     * 页面入口：由路由参数 `stats/item/{studentId}/{homeworkId}` 传入目标学生与作业。
     * 幂等：重复调用（重组/配置变更）不会重复取数。
     */
    fun start(routeStudentId: Long, homeworkId: Long) {
        if (_uiState.value.started) {
            return
        }
        _uiState.update {
            it.copy(started = true, routeStudentId = routeStudentId, homeworkId = homeworkId)
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
        _uiState.update { it.copy(phase = ItemDetailPhase.LOADING, errorMessage = null) }
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
            when (val result = statsRepository.itemDetail(state.homeworkId)) {
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
}

/** 单项详情页阶段（页面据单一阶段字段渲染） */
enum class ItemDetailPhase {

    /** 取数中 */
    LOADING,

    /** 数据就绪（可能无执行记录，见 [ItemDetailUiState.hasExecution]） */
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
    val phase: ItemDetailPhase = ItemDetailPhase.LOADING,
    val studentId: Long? = null,
    val detail: ItemDetail? = null,
    val errorMessage: String? = null,
) {

    /** 是否处于取数中 */
    val loading: Boolean get() = phase == ItemDetailPhase.LOADING

    /** 是否有执行记录（false 时页面展示「尚未开始/暂无数据」） */
    val hasExecution: Boolean get() = detail?.hasExecution == true

    /** 作业内容（无数据时回退占位文案） */
    val contentText: String get() = detail?.content ?: "作业详情"

    /** 作业状态标签 */
    val statusText: String get() = detail?.status?.label ?: ""

    /** 预估时长文案（未设定时展示 [StatsErrorMessages.ESTIMATED_UNSET]） */
    val estimatedText: String
        get() = detail?.estimatedText ?: StatsErrorMessages.ESTIMATED_UNSET

    /** 实际时长文案（无执行记录时展示占位提示） */
    val elapsedText: String
        get() = when {
            detail == null -> StatsErrorMessages.NOT_STARTED
            !hasExecution -> StatsErrorMessages.NOT_STARTED
            else -> StatsCalculations.durationText(detail.elapsedMillis)
        }

    /** 暂停时长文案（无执行记录时展示占位提示） */
    val pausedText: String
        get() = when {
            detail == null -> StatsErrorMessages.NOT_STARTED
            !hasExecution -> StatsErrorMessages.NOT_STARTED
            else -> StatsCalculations.durationText(detail.pausedTotalMillis)
        }

    /** 执行会话数文案 */
    val sessionCountText: String get() = "${detail?.sessionCount ?: 0} 次"

    /** 困难度等级标签 */
    val difficultyLabel: String get() = detail?.difficulty?.label ?: ""

    /** 困难度侧面评估提示（无执行记录时为「尚未开始」文案） */
    val assessmentHint: String get() = detail?.assessmentHint ?: StatsErrorMessages.NOT_STARTED
}