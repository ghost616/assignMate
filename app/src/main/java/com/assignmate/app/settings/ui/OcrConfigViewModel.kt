package com.assignmate.app.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.settings.data.OcrConfigSaveResult
import com.assignmate.app.settings.data.SettingsOcrRepository
import com.assignmate.app.settings.domain.OcrApiKeyMask
import com.assignmate.app.settings.domain.OcrConfigField
import com.assignmate.app.settings.domain.OcrLogSink
import com.assignmate.app.settings.domain.SettingsDenialReason
import com.assignmate.app.settings.domain.SettingsFeature
import com.assignmate.app.settings.domain.SettingsOcrValidator
import com.assignmate.app.settings.domain.SettingsRoleGuard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * OCR 厂商配置页 ViewModel：服务地址 / 模型名称 / API 密钥 / 启用开关四项表单。
 *
 * 状态流转（单数据流 [uiState] + 一次性事件 [events]）：
 * 1. [start] 分权判定（**页面层保险之外的第二次判定**）：role 为 null -> 未登录；学生会话 -> 拒绝，
 *    两种情况都不订阅配置流、不渲染表单（学生无法读取密钥，也无法保存）；
 * 2. 家长会话：读取 core 的 OcrConfigStore 当前值预填表单，密钥默认**掩码显示**；
 * 3. 保存前用 [SettingsOcrValidator]（底层复用 core 的 OcrConfig.validationIssues）校验，
 *    问题文案**就地渲染**到对应输入框（不只依赖一次性 Snackbar，与 HomeworkTimeSetScreen 约定一致）；
 * 4. 校验通过 -> 经 [SettingsOcrRepository] 落盘 -> Snackbar 提示保存成功。
 *
 * 安全：日志只输出 [SettingsDenialReason] 等非敏感信息，**绝不**输出密钥明文（见 [OcrLogSink] 约定）。
 * 本页不做真实网络请求测试、不做图片识别（能力边界见需求）。
 */
@HiltViewModel
class OcrConfigViewModel @Inject constructor(
    private val repository: SettingsOcrRepository,
    private val guard: SettingsRoleGuard,
    private val logSink: OcrLogSink,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OcrConfigUiState())
    val uiState: StateFlow<OcrConfigUiState> = _uiState.asStateFlow()

    private val _events = Channel<OcrConfigEvent>(Channel.BUFFERED)
    val events: Flow<OcrConfigEvent> = _events.receiveAsFlow()

    private var started = false

    /** 页面入口（幂等）：先做分权判定，再读取当前配置 */
    fun start() {
        if (started) {
            return
        }
        started = true
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val denial = guard.denialReason(SettingsFeature.OCR_CONFIG)
        if (denial != null) {
            logSink.log(TAG, "拒绝访问 OCR 配置页：$denial")
            _uiState.update {
                it.copy(
                    loading = false,
                    accessDenied = true,
                    denialReason = denial,
                    accessDeniedHint = denialHint(denial),
                )
            }
            return
        }
        val config = repository.observeConfig().first()
        _uiState.update {
            it.copy(
                loading = false,
                apiBaseUrl = config.apiBaseUrl,
                modelName = config.modelName,
                apiKey = config.apiKey,
                enabled = config.enabled,
            )
        }
    }

    // ---- 表单编辑 ----

    fun onApiBaseUrlChange(value: String) {
        _uiState.update {
            it.copy(apiBaseUrl = value, fieldErrors = it.fieldErrors.without(OcrConfigField.API_BASE_URL))
        }
    }

    fun onModelNameChange(value: String) {
        _uiState.update {
            it.copy(modelName = value, fieldErrors = it.fieldErrors.without(OcrConfigField.MODEL_NAME))
        }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update {
            it.copy(apiKey = value, fieldErrors = it.fieldErrors.without(OcrConfigField.API_KEY))
        }
    }

    fun onEnabledChange(value: Boolean) {
        _uiState.update {
            it.copy(enabled = value, fieldErrors = it.fieldErrors.without(OcrConfigField.ENABLED))
        }
    }

    /** 明文/掩码切换：仅影响展示，不影响保存的明文值 */
    fun onToggleApiKeyVisibility() {
        _uiState.update { it.copy(apiKeyVisible = !it.apiKeyVisible) }
    }

    // ---- 保存 ----

    fun onSave() {
        val state = _uiState.value
        if (state.loading || state.saving || state.accessDenied) {
            return
        }
        val config = state.toConfig()
        val errors = SettingsOcrValidator.fieldErrors(config)
        if (errors.isNotEmpty()) {
            // 就地渲染校验问题（每项问题都对应到具体输入框），不依赖一次性 Snackbar
            _uiState.update { it.copy(fieldErrors = errors) }
            return
        }
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val result = repository.save(config)) {
                is OcrConfigSaveResult.Saved -> {
                    _uiState.update { it.copy(saving = false, savedForCurrentSession = true) }
                    logSink.log(TAG, "OCR 配置保存成功")
                    _events.send(OcrConfigEvent.SaveSucceeded(SAVE_SUCCESS_MESSAGE))
                }

                is OcrConfigSaveResult.Denied -> {
                    logSink.log(TAG, "OCR 配置保存被拒绝：${result.reason}")
                    _uiState.update {
                        it.copy(
                            saving = false,
                            accessDenied = true,
                            denialReason = result.reason,
                            accessDeniedHint = denialHint(result.reason),
                        )
                    }
                    _events.send(OcrConfigEvent.ShowMessage(denialHint(result.reason)))
                }
            }
        }
    }

    private fun denialHint(reason: SettingsDenialReason): String = when (reason) {
        SettingsDenialReason.NOT_SIGNED_IN -> SettingsViewModel.NOT_SIGNED_IN_HINT
        SettingsDenialReason.STUDENT_FORBIDDEN -> SettingsViewModel.OCR_PAGE_DENIED_HINT
    }

    private fun Map<OcrConfigField, String>.without(field: OcrConfigField): Map<OcrConfigField, String> =
        if (containsKey(field)) filterKeys { it != field } else this

    private companion object {
        const val TAG = "OcrConfigViewModel"

        /** 保存成功提示（唯一来源，页面与测试共用） */
        const val SAVE_SUCCESS_MESSAGE: String = "识别设置已保存"
    }
}

/** OCR 配置页状态（单数据流） */
data class OcrConfigUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    /** 分权拒绝（未登录/学生会话）：整页拒绝态，不渲染表单 */
    val accessDenied: Boolean = false,
    val denialReason: SettingsDenialReason? = null,
    val accessDeniedHint: String = "",
    val apiBaseUrl: String = "",
    val modelName: String = "",
    /** 密钥明文（仅内存持有，用于保存；页面默认按 [displayApiKey] 掩码展示） */
    val apiKey: String = "",
    /** 密钥是否明文展示（默认掩码） */
    val apiKeyVisible: Boolean = false,
    val enabled: Boolean = false,
    /** 分区校验问题：字段 -> 就地提示文案（源自 core 的 OcrConfig.validationIssues） */
    val fieldErrors: Map<OcrConfigField, String> = emptyMap(),
    /** 本次会话内是否已成功保存过（返回主页后摘要会随配置流刷新） */
    val savedForCurrentSession: Boolean = false,
) {

    /** 表单是否可编辑（加载完成且未被拒绝） */
    val editable: Boolean get() = !loading && !accessDenied

    /** 保存按钮可用性 */
    val canSave: Boolean get() = editable && !saving

    /** 保存按钮文案 */
    val saveButtonText: String get() = if (saving) "保存中…" else "保存设置"

    /**
     * 密钥输入框的展示值：掩码态返回 `sk-****abcd` 形式的掩码，
     * 明文态返回原值；空密钥返回空串（不伪造凭据）。
     */
    val displayApiKey: String
        get() = if (apiKeyVisible) apiKey else OcrApiKeyMask.mask(apiKey)

    /** 密钥输入框旁的切换按钮文案 */
    val apiKeyToggleText: String get() = if (apiKeyVisible) "隐藏" else "显示"

    /** 启用开关的就地提示（未启用时提示「识别功能未启用」，与 core 校验文案同源） */
    val enabledHint: String? get() = fieldErrors[OcrConfigField.ENABLED]

    /** 服务地址就地提示 */
    val apiBaseUrlError: String? get() = fieldErrors[OcrConfigField.API_BASE_URL]

    /** 模型名称就地提示 */
    val modelNameError: String? get() = fieldErrors[OcrConfigField.MODEL_NAME]

    /** 密钥就地提示 */
    val apiKeyError: String? get() = fieldErrors[OcrConfigField.API_KEY]

    /** 是否存在任何校验问题 */
    val hasValidationIssues: Boolean get() = fieldErrors.isNotEmpty()

    /** 当前表单对应的领域配置（校验与保存共用同一投影，避免两处拼装口径漂移） */
    fun toConfig(): OcrConfig = OcrConfig(
        apiBaseUrl = apiBaseUrl.trim(),
        modelName = modelName.trim(),
        apiKey = apiKey.trim(),
        enabled = enabled,
    )

    /** 校验问题文案汇总（按 core 问题枚举声明顺序，供无障碍播报/测试断言） */
    val validationMessages: List<String>
        get() = listOf(
            apiBaseUrlError,
            modelNameError,
            apiKeyError,
            enabledHint,
        ).filterNotNull()
}

/** OCR 配置页一次性事件 */
sealed interface OcrConfigEvent {

    /** 保存成功（页面提示后停留本页，便于继续调整） */
    data class SaveSucceeded(val message: String) : OcrConfigEvent

    /** 一次性提示（校验失败之外的情形，如分权拒绝） */
    data class ShowMessage(val message: String) : OcrConfigEvent
}
