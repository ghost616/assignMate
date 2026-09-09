package com.assignmate.app.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.ParentLoginResult
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
 * 家长登录页 ViewModel：本地账号密码校验 + [AuthRepository.loginParent]。
 * 提交期间置 submitting 防重复点击；失败统一收敛为可读文案（账号/密码错误不区分提示）。
 */
@HiltViewModel
class ParentLoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ParentLoginUiState())
    val uiState: StateFlow<ParentLoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<ParentLoginEvent>(Channel.BUFFERED)
    val events: Flow<ParentLoginEvent> = _events.receiveAsFlow()

    fun onAccountChange(value: String) {
        _uiState.update {
            it.copy(account = value, accountError = null, formError = null)
        }
    }

    fun onPasswordChange(value: String) {
        _uiState.update {
            it.copy(password = value, passwordError = null, formError = null)
        }
    }

    fun onTogglePasswordVisible() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun onLogin() {
        val current = _uiState.value
        if (current.submitting) {
            return
        }
        val accountError = AuthValidators.parentAccountError(current.account)
        val passwordError = AuthValidators.passwordError(current.password)
        if (accountError != null || passwordError != null) {
            _uiState.update {
                it.copy(
                    accountError = accountError?.userMessage,
                    passwordError = passwordError?.userMessage,
                )
            }
            return
        }
        _uiState.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            val result = authRepository.loginParent(current.account.trim(), current.password)
            when (result) {
                is ParentLoginResult.Success -> {
                    _uiState.update { it.copy(submitting = false) }
                    _events.send(ParentLoginEvent.LoginSuccess)
                }

                is ParentLoginResult.Failure -> {
                    _uiState.update {
                        it.copy(submitting = false, formError = result.reason.toUserMessage())
                    }
                }
            }
        }
    }

    fun onNavigateToRegister() {
        viewModelScope.launch { _events.send(ParentLoginEvent.NavigateToRegister) }
    }
}

/** 家长登录页界面状态（单数据流） */
data class ParentLoginUiState(
    val account: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val submitting: Boolean = false,
    val accountError: String? = null,
    val passwordError: String? = null,
    val formError: String? = null,
)

/** 家长登录页一次性事件 */
sealed interface ParentLoginEvent {

    /** 跳转注册页 */
    data object NavigateToRegister : ParentLoginEvent

    /** 登录成功：进入家长主界面 */
    data object LoginSuccess : ParentLoginEvent
}