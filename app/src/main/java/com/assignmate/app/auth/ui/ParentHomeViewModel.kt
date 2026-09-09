package com.assignmate.app.auth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assignmate.app.auth.data.AddStudentFailure
import com.assignmate.app.auth.data.AddStudentResult
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.DeleteStudentResult
import com.assignmate.app.auth.data.RenameStudentFailure
import com.assignmate.app.auth.data.RenameStudentResult
import com.assignmate.app.auth.data.UpdateVerificationCodeFailure
import com.assignmate.app.auth.data.UpdateVerificationCodeResult
import com.assignmate.app.auth.domain.AuthConstants
import com.assignmate.app.auth.domain.AuthValidators
import com.assignmate.app.auth.domain.Role
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
 * 家长主界面 ViewModel：学生档案列表 + 添加/改名/删除 + 验证码查看/重置/自定义 +
 * 登出；学生列表始终以家长会话 id 为维度刷新（仓库侧同步兜底上限/唯一性校验）。
 */
@HiltViewModel
class ParentHomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ParentHomeUiState())
    val uiState: StateFlow<ParentHomeUiState> = _uiState.asStateFlow()

    private val _events = Channel<ParentHomeEvent>(Channel.BUFFERED)
    val events: Flow<ParentHomeEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { restore() }
    }

    /** 初始化：读取家长会话并加载学生列表；非家长会话则提示退出 */
    private suspend fun restore() {
        val session = authRepository.currentSession()
        val parentId = session.parentId
        if (!session.isParent || parentId == null) {
            _uiState.update { it.copy(loading = false) }
            _events.send(ParentHomeEvent.SessionExpired)
            return
        }
        _uiState.update { it.copy(loading = false, parentId = parentId) }
        refreshStudents(parentId)
    }

    private suspend fun refreshStudents(parentId: Long) {
        val students = authRepository.listStudents(parentId)
        _uiState.update { it.copy(students = students) }
    }

    // ---- 添加学生 ----

    fun onAddClick() {
        val current = _uiState.value
        if (current.students.size >= AuthConstants.MAX_STUDENTS) {
            viewModelScope.launch {
                _events.send(
                    ParentHomeEvent.ShowMessage(
                        "最多只能添加 ${AuthConstants.MAX_STUDENTS} 名学生",
                    ),
                )
            }
            return
        }
        _uiState.update {
            it.copy(addDialogVisible = true, addName = "", addNameError = null)
        }
    }

    fun onAddNameChange(value: String) {
        _uiState.update { it.copy(addName = value, addNameError = null) }
    }

    fun onAddDismiss() {
        _uiState.update { it.copy(addDialogVisible = false, addName = "", addNameError = null) }
    }

    fun onAddConfirm() {
        val current = _uiState.value
        val parentId = current.parentId ?: return
        if (current.adding) {
            return
        }
        val nameError = AuthValidators.studentNameError(current.addName)
        if (nameError != null) {
            _uiState.update { it.copy(addNameError = nameError.userMessage) }
            return
        }
        _uiState.update { it.copy(adding = true) }
        viewModelScope.launch {
            val result = authRepository.addStudent(parentId, current.addName)
            when (result) {
                is AddStudentResult.Success -> {
                    _uiState.update {
                        it.copy(adding = false, addDialogVisible = false, addName = "")
                    }
                    refreshStudents(parentId)
                    _events.send(
                        ParentHomeEvent.ShowMessage(
                            "已添加「${result.student.name}」，进入验证码：${result.student.verificationCode}",
                        ),
                    )
                }

                is AddStudentResult.Failure -> {
                    _uiState.update { it.copy(adding = false) }
                    when (result.reason) {
                        AddStudentFailure.INVALID_NAME -> _uiState.update {
                            it.copy(addNameError = result.reason.toUserMessage())
                        }

                        else -> {
                            _uiState.update { it.copy(addDialogVisible = false, addName = "") }
                            _events.send(ParentHomeEvent.ShowMessage(result.reason.toUserMessage()))
                        }
                    }
                }
            }
        }
    }

    // ---- 改名 ----

    fun onRenameClick(student: Student) {
        _uiState.update {
            it.copy(renameTarget = student, renameName = student.name, renameError = null)
        }
    }

    fun onRenameNameChange(value: String) {
        _uiState.update { it.copy(renameName = value, renameError = null) }
    }

    fun onRenameDismiss() {
        _uiState.update { it.copy(renameTarget = null, renameName = "", renameError = null) }
    }

    fun onRenameConfirm() {
        val target = _uiState.value.renameTarget ?: return
        val newName = _uiState.value.renameName
        if (_uiState.value.renaming) {
            return
        }
        val nameError = AuthValidators.studentNameError(newName)
        if (nameError != null) {
            _uiState.update { it.copy(renameError = nameError.userMessage) }
            return
        }
        _uiState.update { it.copy(renaming = true) }
        viewModelScope.launch {
            val parentId = _uiState.value.parentId
            val result = authRepository.renameStudent(target.id, newName)
            when (result) {
                is RenameStudentResult.Success -> {
                    _uiState.update { it.copy(renaming = false, renameTarget = null) }
                    parentId?.let { refreshStudents(it) }
                    _events.send(ParentHomeEvent.ShowMessage("已改名为「${newName.trim()}」"))
                }

                is RenameStudentResult.Failure -> {
                    _uiState.update { it.copy(renaming = false) }
                    if (result.reason == RenameStudentFailure.STUDENT_NOT_FOUND) {
                        _uiState.update { it.copy(renameTarget = null) }
                        parentId?.let { refreshStudents(it) }
                    } else {
                        _uiState.update { it.copy(renameError = result.reason.toUserMessage()) }
                    }
                }
            }
        }
    }

    // ---- 删除 ----

    fun onDeleteClick(student: Student) {
        _uiState.update { it.copy(deleteTarget = student) }
    }

    fun onDeleteDismiss() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun onDeleteConfirm() {
        val target = _uiState.value.deleteTarget ?: return
        if (_uiState.value.deleting) {
            return
        }
        _uiState.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val parentId = _uiState.value.parentId
            val result = authRepository.deleteStudent(target.id)
            when (result) {
                is DeleteStudentResult.Success -> {
                    _uiState.update { it.copy(deleting = false, deleteTarget = null) }
                    parentId?.let { refreshStudents(it) }
                    _events.send(ParentHomeEvent.ShowMessage("已删除「${target.name}」"))
                }

                is DeleteStudentResult.Failure -> {
                    _uiState.update { it.copy(deleting = false, deleteTarget = null) }
                    parentId?.let { refreshStudents(it) }
                    _events.send(ParentHomeEvent.ShowMessage(result.reason.toUserMessage()))
                }
            }
        }
    }

    // ---- 验证码查看/重置/自定义 ----

    fun onCodeClick(student: Student) {
        _uiState.update {
            it.copy(codeTarget = student, codeInput = student.verificationCode, codeError = null)
        }
    }

    fun onCodeInputChange(value: String) {
        _uiState.update { it.copy(codeInput = value, codeError = null) }
    }

    fun onCodeDismiss() {
        _uiState.update {
            it.copy(codeTarget = null, codeInput = "", codeError = null, codeBusy = false)
        }
    }

    /** 一键随机生成（仓库内保证同家长唯一），成功后立即生效并关闭弹窗 */
    fun onRegenerateCode() {
        val target = _uiState.value.codeTarget ?: return
        if (_uiState.value.codeBusy) {
            return
        }
        _uiState.update { it.copy(codeBusy = true) }
        viewModelScope.launch {
            val parentId = _uiState.value.parentId
            val result = authRepository.resetStudentVerificationCode(target.id)
            when (result) {
                is UpdateVerificationCodeResult.Success -> {
                    _uiState.update { it.copy(codeBusy = false, codeTarget = null) }
                    parentId?.let { refreshStudents(it) }
                    _events.send(
                        ParentHomeEvent.ShowMessage(
                            "「${target.name}」的新进入验证码：${result.newCode}",
                        ),
                    )
                }

                is UpdateVerificationCodeResult.Failure -> {
                    _uiState.update { it.copy(codeBusy = false, codeTarget = null) }
                    parentId?.let { refreshStudents(it) }
                    _events.send(ParentHomeEvent.ShowMessage(result.reason.toUserMessage()))
                }
            }
        }
    }

    /** 家长自定义验证码（4-6 位数字，同家长唯一） */
    fun onSaveCustomCode() {
        val target = _uiState.value.codeTarget ?: return
        val input = _uiState.value.codeInput
        if (_uiState.value.codeBusy) {
            return
        }
        val codeError = AuthValidators.verificationCodeError(input)
        if (codeError != null) {
            _uiState.update { it.copy(codeError = codeError.userMessage) }
            return
        }
        _uiState.update { it.copy(codeBusy = true) }
        viewModelScope.launch {
            val parentId = _uiState.value.parentId
            val result = authRepository.updateStudentVerificationCode(target.id, input)
            when (result) {
                is UpdateVerificationCodeResult.Success -> {
                    _uiState.update { it.copy(codeBusy = false, codeTarget = null) }
                    parentId?.let { refreshStudents(it) }
                    _events.send(
                        ParentHomeEvent.ShowMessage(
                            "「${target.name}」的进入验证码已更新：${result.newCode}",
                        ),
                    )
                }

                is UpdateVerificationCodeResult.Failure -> {
                    _uiState.update { it.copy(codeBusy = false) }
                    if (result.reason == UpdateVerificationCodeFailure.STUDENT_NOT_FOUND) {
                        _uiState.update { it.copy(codeTarget = null) }
                        parentId?.let { refreshStudents(it) }
                    } else {
                        _uiState.update { it.copy(codeError = result.reason.toUserMessage()) }
                    }
                }
            }
        }
    }

    // ---- 其它 ----

    /** “进入某学生作业界面”占位：作业功能待 homework 模块落地 */
    fun onEnterHomework(student: Student) {
        viewModelScope.launch {
            _events.send(
                ParentHomeEvent.ShowMessage(
                    "「${student.name}」的作业界面即将上线，敬请期待",
                ),
            )
        }
    }

    fun onLogoutClick() {
        viewModelScope.launch {
            authRepository.logout()
            _events.send(ParentHomeEvent.LoggedOut)
        }
    }
}

/** 家长主界面状态（单数据流） */
data class ParentHomeUiState(
    val loading: Boolean = true,
    val parentId: Long? = null,
    val students: List<Student> = emptyList(),
    // 添加学生弹窗
    val addDialogVisible: Boolean = false,
    val addName: String = "",
    val addNameError: String? = null,
    val adding: Boolean = false,
    // 改名弹窗
    val renameTarget: Student? = null,
    val renameName: String = "",
    val renameError: String? = null,
    val renaming: Boolean = false,
    // 删除确认
    val deleteTarget: Student? = null,
    val deleting: Boolean = false,
    // 验证码弹窗
    val codeTarget: Student? = null,
    val codeInput: String = "",
    val codeError: String? = null,
    val codeBusy: Boolean = false,
)

/** 家长主界面一次性事件 */
sealed interface ParentHomeEvent {

    /** 一次性提示（Snackbar/对话框关闭后的确认信息） */
    data class ShowMessage(val message: String) : ParentHomeEvent

    /** 会话失效（非家长角色打开本页） */
    data object SessionExpired : ParentHomeEvent

    /** 已登出：回到身份选择页 */
    data object LoggedOut : ParentHomeEvent
}