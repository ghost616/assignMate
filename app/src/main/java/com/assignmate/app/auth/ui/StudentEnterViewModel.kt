package com.assignmate.app.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.StudentEnterResult
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
 * 学生进入页 ViewModel：输入“家长账号 + 验证码”，经 [AuthRepository.enterAsStudent]
 * 校验成功后建立学生会话并直达本人界面（STUDENT_HOME）。
 */
@HiltViewModel
class StudentEnterViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudentEnterUiState())
    val uiState: StateFlow<StudentEnterUiState> = _uiState.asStateFlow()

    private val _events = Channel<StudentEnterEvent>(Channel.BUFFERED)
    val events: Flow<StudentEnterEvent> = _events.receiveAsFlow()

    fun onParentAccountChange(value: String) {
        _uiState.update {
            it.copy(parentAccount = value, parentAccountError = null, formError = null)
        }
    }

    fun onVerificationCodeChange(value: String) {
        _uiState.update {
            it.copy(verificationCode = value, codeError = null, formError = null)
        }
    }

    fun onEnter() {
        val current = _uiState.value
        if (current.submitting) {
            return
        }
        val accountError = AuthValidators.parentAccountError(current.parentAccount)
        val codeError = AuthValidators.verificationCodeError(current.verificationCode)
        if (accountError != null || codeError != null) {
            _uiState.update {
                it.copy(
                    parentAccountError = accountError?.userMessage,
                    codeError = codeError?.userMessage,
                )
            }
            return
        }
        _uiState.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            val result = authRepository.enterAsStudent(
                parentAccount = current.parentAccount.trim(),
                verificationCode = current.verificationCode.trim(),
            )
            when (result) {
                is StudentEnterResult.Success -> {
                    _uiState.update { it.copy(submitting = false) }
                    _events.send(StudentEnterEvent.Entered(result.student.name))
                }

                is StudentEnterResult.Failure -> {
                    _uiState.update {
                        it.copy(submitting = false, formError = result.reason.toUserMessage())
                    }
                }
            }
        }
    }
}

/** 学生进入页界面状态（单数据流） */
data class StudentEnterUiState(
    val parentAccount: String = "",
    val verificationCode: String = "",
    val submitting: Boolean = false,
    val parentAccountError: String? = null,
    val codeError: String? = null,
    val formError: String? = null,
)

/** 学生进入页一次性事件 */
sealed interface StudentEnterEvent {

    /** 校验成功（携带学生姓名便于后续展示）：进入学生端首页 */
    data class Entered(val studentName: String) : StudentEnterEvent
}