package com.assignmate.app.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 身份选择页 ViewModel：静态双入口（我是家长/我是学生）之外，
 * 启动时检查本地持久化会话——若上次未登出则直接恢复对应首页，
 * 实现“杀进程后重进 App 自动回到原身份”的会话恢复体验。
 */
@HiltViewModel
class RoleSelectViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _events = Channel<RoleSelectEvent>(Channel.BUFFERED)
    val events: Flow<RoleSelectEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            when (authRepository.currentSession().role) {
                Role.PARENT -> _events.send(RoleSelectEvent.ResumeParentHome)
                Role.STUDENT -> _events.send(RoleSelectEvent.ResumeStudentHome)
                null -> Unit // 无会话：停留在身份选择页
            }
        }
    }
}

/** 身份选择页一次性事件 */
sealed interface RoleSelectEvent {

    /** 检测到家长会话，恢复家长主界面 */
    data object ResumeParentHome : RoleSelectEvent

    /** 检测到学生会话，恢复学生端首页 */
    data object ResumeStudentHome : RoleSelectEvent
}