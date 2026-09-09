package com.assignmate.app.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.ParentRegisterFailure
import com.assignmate.app.auth.data.ParentRegisterResult
import com.assignmate.app.auth.domain.AuthValidators
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
 * 家长注册页 ViewModel：账号/密码/确认密码本地校验 + 注册（仓库内做账号唯一性
 * 与密码哈希落库）。注册成功即已建立家长会话，事件直达家长主界面。
 */
@HiltViewModel
class ParentRegisterViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ParentRegisterUiState())
    val uiState: StateFlow<ParentRegisterUiState> = _uiState.asStateFlow()

    private val _events = Channel<ParentRegisterEvent>(Channel.BUFFERED)
    val events: Flow<ParentRegisterEvent> = _events.receiveAsFlow()

    fun onAccountChange(value: String) {
        _uiState.update { it.copy(account = value, accountError = null, formError = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, passwordError = null) }
    }

    fun onConfirmPasswordChange(value: String) {
        _uiState.update { it.copy(confirmPassword = value, confirmPasswordError = null) }
    }

    fun onTogglePasswordVisible() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun onRegister() {
        val current = _uiState.value
        if (current.submitting) {
            return
        }
        val accountError = AuthValidators.parentAccountError(current.account)
        val passwordError = AuthValidators.passwordError(current.password)
        val confirmError = AuthValidators.confirmPasswordError(
            current.confirmPassword,
            current.password,
        )
        if (accountError != null || passwordError != null || confirmError != null) {
            _uiState.update {
                it.copy(
                    accountError = accountError?.userMessage,
                    passwordError = passwordError?.userMessage,
                    confirmPasswordError = confirmError?.userMessage,
                )
            }
            return
        }
        _uiState.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            val result = authRepository.registerParent(current.account.trim(), current.password)
            when (result) {
                is ParentRegisterResult.Success -> {
                    _uiState.update { it.copy(submitting = false) }
                    _events.send(ParentRegisterEvent.Registered)
                }

                is ParentRegisterResult.Failure -> {
                    _uiState.update {
                        it.copy(submitting = false, formError = result.reason.toUserMessage())
                    }
                    if (result.reason == ParentRegisterFailure.ACCOUNT_ALREADY_EXISTS) {
                        _events.send(ParentRegisterEvent.NavigateToLogin)
                    }
                }
            }
        }
    }
}

/** 家长注册页界面状态（单数据流） */
data class ParentRegisterUiState(
    val account: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val accountError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val formError: String? = null,
)

/** 家长注册页一次性事件 */
sealed interface ParentRegisterEvent {

    /** 注册成功：进入家长主界面 */
    data object Registered : ParentRegisterEvent

    /** 账号已存在：引导回登录页 */
    data object NavigateToLogin : ParentRegisterEvent
}