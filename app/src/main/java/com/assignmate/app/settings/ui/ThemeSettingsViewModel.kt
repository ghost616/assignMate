package com.assignmate.app.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.settings.data.ThemeSettingsRepository
import com.assignmate.app.settings.data.ThemeSettingsResult
import com.assignmate.app.settings.domain.EyeCareTheme
import com.assignmate.app.settings.domain.SettingsDenialReason
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
 * 护眼设置页 ViewModel：三档主题单选（跟随系统 / 护眼浅色 / 护眼夜间）。
 *
 * 状态流转（单数据流 [uiState] + 一次性事件 [events]）：
 * 1. [start] 读取当前档位（缺失/非法存量值由 core 兜底「跟随系统」）并订阅档位流；
 * 2. [onModeSelected] 选中即**先本地即时生效**（更新状态，界面立刻切换），再异步落盘；
 * 3. 落盘被拒（无有效会话）时回滚为落盘前的档位并提示，避免「界面显示已切换、重启又变回去」。
 *
 * 分权：护眼设置家长与学生均可访问（学生端首页入口），仅未登录会话写入被拒。
 */
@HiltViewModel
class ThemeSettingsViewModel @Inject constructor(
    private val repository: ThemeSettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThemeSettingsUiState())
    val uiState: StateFlow<ThemeSettingsUiState> = _uiState.asStateFlow()

    private val _events = Channel<ThemeSettingsEvent>(Channel.BUFFERED)
    val events: Flow<ThemeSettingsEvent> = _events.receiveAsFlow()

    private var started = false

    /** 页面入口（幂等）：读取并订阅档位 */
    fun start() {
        if (started) {
            return
        }
        started = true
        viewModelScope.launch {
            val mode = repository.themeMode()
            _uiState.update { it.copy(loading = false, selectedMode = mode) }
            repository.observeThemeMode().collect { observed ->
                _uiState.update { it.copy(selectedMode = observed) }
            }
        }
    }

    /**
     * 选中档位：即时生效（先写状态）+ 持久化。
     *
     * 重复点击同一档位直接忽略，避免无意义写入与多余重组。
     */
    fun onModeSelected(mode: ThemeMode) {
        val state = _uiState.value
        // 加载中不响应；重复点击当前档位直接忽略，避免无意义写入
        if (state.loading || state.selectedMode == mode) {
            return
        }
        val previous = state.selectedMode
        _uiState.update { it.copy(selectedMode = mode, saving = true, accessDenied = false) }
        viewModelScope.launch {
            when (val result = repository.setThemeMode(mode)) {
                is ThemeSettingsResult.Saved ->
                    _uiState.update { it.copy(saving = false) }

                is ThemeSettingsResult.Denied -> {
                    // 未登录：回滚到落盘前档位，避免「界面已切换但重启还原」的错觉
                    _uiState.update {
                        it.copy(
                            saving = false,
                            selectedMode = previous,
                            accessDenied = true,
                            accessDeniedHint = denialHint(result.reason),
                        )
                    }
                    _events.send(ThemeSettingsEvent.ShowMessage(denialHint(result.reason)))
                }
            }
        }
    }

    private fun denialHint(reason: SettingsDenialReason): String = when (reason) {
        SettingsDenialReason.NOT_SIGNED_IN -> SettingsViewModel.NOT_SIGNED_IN_HINT
        SettingsDenialReason.STUDENT_FORBIDDEN -> SettingsViewModel.OCR_STUDENT_FORBIDDEN_HINT
    }
}

/** 护眼设置页状态（单数据流） */
data class ThemeSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    /** 当前选中档位（即时生效的来源；实际生效由根主题按此档位解析深浅色） */
    val selectedMode: ThemeMode = ThemeMode.DEFAULT,
    val accessDenied: Boolean = false,
    val accessDeniedHint: String = "",
) {

    /** 三档选项（页面按顺序渲染三行单选） */
    val modes: List<ThemeMode> get() = EyeCareTheme.MODES

    /** 是否存在有效选中项（加载完成后恒为 true，保留给页面渲染分支使用） */
    val hasSelection: Boolean get() = !loading

    /** 当前档位文案（如「跟随系统」） */
    val currentModeText: String get() = selectedMode.label

    /** 当前档位下是否固定夜间（null 表示跟随系统，页面据此给出补充说明） */
    val darkThemeOverride: Boolean? get() = EyeCareTheme.darkThemeOverride(selectedMode)
}

/** 护眼设置页一次性事件 */
sealed interface ThemeSettingsEvent {

    /** 一次性提示（如无会话导致写入被拒） */
    data class ShowMessage(val message: String) : ThemeSettingsEvent
}