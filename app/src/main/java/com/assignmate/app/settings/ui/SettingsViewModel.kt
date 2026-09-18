package com.assignmate.app.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.settings.data.SettingsOcrRepository
import com.assignmate.app.settings.data.ThemeSettingsRepository
import com.assignmate.app.settings.domain.DataCleanupResult
import com.assignmate.app.settings.domain.DataCleanupService
import com.assignmate.app.settings.domain.OcrCleanupSnapshot
import com.assignmate.app.settings.domain.SettingsDenialReason
import com.assignmate.app.settings.domain.SettingsRoleGuard
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
 * 设置主页 ViewModel：OCR 配置状态摘要 + 当前主题档位 + 数据清理面板。
 *
 * 状态流转（单数据流 [uiState] + 一次性事件 [events]）：
 * 1. [start] 读会话判定分权：role 为 null -> 未登录；学生会话 -> 不可访问 OCR 配置（入口不渲染）；
 * 2. 家长会话：订阅 OCR 配置流给出「已配置 / 未配置」摘要，并订阅主题档位；
 * 3. 数据清理：订阅待清理条数（覆盖 4 种状态），条数为 0 时按钮置灰并展示空态文案；
 * 4. [onCleanupConfirmed] 执行清理（二次确认弹窗由页面渲染）→ 刷新计数 + 提示「已清理 N 条」。
 *
 * OCR 配置**内容**（地址/模型/密钥）不在本页读取，只读 [com.assignmate.app.core.domain.ocr.OcrConfig.isConfigured] 摘要，
 * 避免密钥明文长时间驻留在主页状态中。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val ocrRepository: SettingsOcrRepository,
    private val themeRepository: ThemeSettingsRepository,
    private val authRepository: AuthRepository,
    private val roleGuard: SettingsRoleGuard,
    /** 数据清理编排（统计 + 执行 + 条数回报）：由 DI 装配，便于单测直接注入替身 */
    val cleanup: DataCleanupService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    private var started = false

    /** 页面入口（幂等）：判定分权后建流，重复调用不重复订阅 */
    fun start() {
        if (started) {
            return
        }
        started = true
        viewModelScope.launch { loadAccess() }
        observeRecords()
        viewModelScope.launch { loadThemeMode() }
    }

    private suspend fun loadAccess() {
        val session = authRepository.currentSession()
        val canAccessOcr = SettingsRoleGuard.canShowOcrEntry(session)
        _uiState.update {
            it.copy(
                role = session.role,
                canAccessOcrConfig = canAccessOcr,
                accessDenied = !canAccessOcr,
            )
        }
        if (canAccessOcr) {
            observeOcrSummary()
        }
    }

    private suspend fun observeOcrSummary() {
        ocrRepository.observeConfig().collect { config ->
            _uiState.update { it.copy(ocrConfigured = config.isConfigured) }
        }
    }

    /**
     * 数据清理面板：先让清理用例建流（含首次重算），再把用例快照**持续**同步到页面状态。
     *
     * 为什么用 `launch` 而不是让 [DataCleanupService.start] 的返回值直接更新状态：
     * `start()` 内部会一直挂起订阅数据流（本身不返回），必须让它在独立协程里跑，
     * 页面状态改由用例快照流驱动，才能随库内变化（新增/清理/删除）自动刷新。
     */
    private fun observeRecords() {
        viewModelScope.launch { cleanup.start() }
        viewModelScope.launch {
            cleanup.snapshot.collect { snapshot ->
                _uiState.update {
                    it.copy(
                        pendingRecordCount = snapshot.recordCount,
                        cleanedRecordCount = cleanup.cleanedCount.value,
                    )
                }
            }
        }
    }

    private suspend fun loadThemeMode() {
        val mode = themeRepository.themeMode()
        _uiState.update { it.copy(themeMode = mode, themeModeLoaded = true) }
    }

    /** 请求清理：页面据此弹出二次确认弹窗（不在 ViewModel 弹窗） */
    fun onCleanupRequested() {
        val state = _uiState.value
        if (state.canCleanup) {
            _uiState.update { it.copy(confirmingCleanup = true) }
        }
    }

    /** 取消清理确认 */
    fun onCleanupDismissed() {
        _uiState.update { it.copy(confirmingCleanup = false) }
    }

    /** 确认清理：执行后刷新计数并提示「已清理 N 条」 */
    fun onCleanupConfirmed() {
        val state = _uiState.value
        if (state.cleaning || !state.canCleanup) {
            _uiState.update { it.copy(confirmingCleanup = false) }
            return
        }
        _uiState.update { it.copy(confirmingCleanup = false, cleaning = true) }
        viewModelScope.launch {
            when (val result = cleanup.clear()) {
                is DataCleanupResult.Executed -> {
                    // 条数以清理用例的快照/回报名单点取值，避免页面自行推算造成口径分叉
                    _uiState.update {
                        it.copy(
                            cleaning = false,
                            pendingRecordCount = cleanup.snapshot.value.recordCount,
                            cleanedRecordCount = cleanup.cleanedCount.value,
                        )
                    }
                    _events.send(
                        SettingsEvent.ShowMessage(
                            CLEANED_MESSAGE_PREFIX + cleanup.cleanedCount.value + " 条",
                        ),
                    )
                }

                is DataCleanupResult.Denied -> {
                    _uiState.update { it.copy(cleaning = false, accessDenied = true) }
                    _events.send(
                        SettingsEvent.ShowMessage(denialText(result.reason)),
                    )
                }
            }
        }
    }

    private fun denialText(reason: SettingsDenialReason): String = when (reason) {
        SettingsDenialReason.NOT_SIGNED_IN -> NOT_SIGNED_IN_HINT
        SettingsDenialReason.STUDENT_FORBIDDEN -> OCR_STUDENT_FORBIDDEN_HINT
    }

    companion object {

        /** 清理完成提示前缀（页面与测试共用，避免文案漂移） */
        const val CLEANED_MESSAGE_PREFIX: String = "已清理 "

        /** 未登录提示 */
        const val NOT_SIGNED_IN_HINT: String = "登录状态已失效，请重新登录"

        /** 学生会话访问 OCR 配置的拒绝文案 */
        const val OCR_STUDENT_FORBIDDEN_HINT: String = "学生账号无法查看或修改识别设置"

        /** 学生会话访问 OCR 配置页的整页拒绝文案 */
        const val OCR_PAGE_DENIED_HINT: String = "该设置仅家长账号可用，请用家长身份登录"
    }
}

/** 设置主页状态（单数据流） */
data class SettingsUiState(
    val role: Role? = null,
    /** OCR 配置是否已配置（依据 OcrConfig.isConfigured，家长会话才读取） */
    val ocrConfigured: Boolean = false,
    /** 是否有权访问 OCR 配置（学生/未登录为 false：入口不渲染，深链进页也拒绝） */
    val canAccessOcrConfig: Boolean = false,
    /** 是否处于分权拒绝态（页面据此提示，不渲染可操作入口） */
    val accessDenied: Boolean = false,
    /** 当前主题档位（缺失/非法存量值由 core 兜底「跟随系统」） */
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val themeModeLoaded: Boolean = false,
    /** 待清理任务条数（覆盖 4 种状态） */
    val pendingRecordCount: Int = 0,
    /** 最近一次清理实际删除的条数（0 = 尚未清理或清理时本就为空） */
    val cleanedRecordCount: Int = 0,
    /** 是否正在清理（按钮置灰，防重复点击） */
    val cleaning: Boolean = false,
    /** 是否展示二次确认弹窗 */
    val confirmingCleanup: Boolean = false,
) {

    /** 待清理内容是否为空（空态：按钮置灰 + 展示 [OcrCleanupSnapshot.EMPTY_HINT]） */
    val cleanupEmpty: Boolean get() = pendingRecordCount <= 0

    /** 是否可发起清理：有内容、未在清理、且未处于分权拒绝态 */
    val canCleanup: Boolean get() = !cleanupEmpty && !cleaning && !accessDenied

    /** 清理按钮文案 */
    val cleanupButtonText: String get() = if (cleaning) "清理中…" else "清理识别缓存"

    /** 面板计数文案 */
    val pendingCountText: String get() = "待清理 $pendingRecordCount 条识别任务"

    /** 二次确认弹窗文案（「将删除 N 条识别任务（含图片），不可恢复」） */
    val confirmMessage: String get() = OcrCleanupSnapshot(recordCount = pendingRecordCount).confirmMessage

    /** 空态文案 */
    val emptyHint: String get() = OcrCleanupSnapshot.EMPTY_HINT

    /** OCR 配置摘要文案 */
    val ocrSummaryText: String get() = if (ocrConfigured) "已配置" else "未配置"

    /** 最近一次清理结果文案（未清理过时为 null，页面据此决定是否展示） */
    val cleanedResultText: String?
        get() = if (cleanedRecordCount > 0) "已清理 $cleanedRecordCount 条" else null
}

/** 设置主页一次性事件 */
sealed interface SettingsEvent {

    /** 一次性提示（Snackbar） */
    data class ShowMessage(val message: String) : SettingsEvent
}