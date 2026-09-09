package com.assignmate.app.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Student
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
 * 学生端首页占位 ViewModel：读取当前学生会话并展示“当前学生姓名”等会话信息；
 * 真实作业清单待 homework 模块填充（届时按 session.parentId/studentId 取数）。
 */
@HiltViewModel
class StudentHomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudentHomeUiState())
    val uiState: StateFlow<StudentHomeUiState> = _uiState.asStateFlow()

    private val _events = Channel<StudentHomeEvent>(Channel.BUFFERED)
    val events: Flow<StudentHomeEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { restore() }
    }

    private suspend fun restore() {
        val session = authRepository.currentSession()
        val studentId = session.studentId
        val parentId = session.parentId
        if (!session.isStudent || studentId == null || parentId == null) {
            // 非学生会话打开本页：展示引导并允许退出
            _uiState.update { it.copy(loading = false, missingSession = true) }
            return
        }
        val student: Student? = authRepository.getStudent(studentId)
        _uiState.update {
            it.copy(
                loading = false,
                parentId = parentId,
                studentId = studentId,
                studentName = student?.name,
            )
        }
    }

    fun onLogoutClick() {
        viewModelScope.launch {
            authRepository.logout()
            _events.send(StudentHomeEvent.LoggedOut)
        }
    }
}

/** 学生端首页状态（单数据流） */
data class StudentHomeUiState(
    val loading: Boolean = true,
    val missingSession: Boolean = false,
    val parentId: Long? = null,
    val studentId: Long? = null,
    val studentName: String? = null,
)

/** 学生端首页一次性事件 */
sealed interface StudentHomeEvent {

    /** 已登出：回到身份选择页 */
    data object LoggedOut : StudentHomeEvent
}