package com.assignmate.app.homework.data

import com.assignmate.app.auth.data.AddStudentResult
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.DeleteStudentResult
import com.assignmate.app.auth.data.ParentLoginResult
import com.assignmate.app.auth.data.ParentRegisterResult
import com.assignmate.app.auth.data.RenameStudentResult
import com.assignmate.app.auth.data.StudentEnterResult
import com.assignmate.app.auth.data.UpdateVerificationCodeResult
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.auth.domain.Student
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 内存版 auth 仓库（单元测试替身）：实现 homework 仓库真正依赖的会话读取与学生会话查询。
 *
 * 家长账号/验证码等能力不属于 homework 模块测试范围，一律抛 [UnsupportedOperationException]，
 * 一旦被测代码误用会立刻暴露，避免"静默通过"的假绿灯。
 */
class FakeAuthRepository(
    initialSession: SessionState = SessionState.NONE,
) : AuthRepository {

    private val sessionFlow = MutableStateFlow(initialSession)
    private val students = mutableMapOf<Long, Student>()

    // ---- 测试辅助 ----

    /** 切换当前会话（同步通知 observeSession 订阅者） */
    fun setSession(session: SessionState) {
        sessionFlow.value = session
    }

    /** 登记学生档案（供 getStudent 返回姓名、家长归属校验使用） */
    fun addStudentProfile(
        id: Long,
        parentAccountId: Long,
        name: String = "学生$id",
    ) {
        students[id] = Student(
            id = id,
            parentAccountId = parentAccountId,
            name = name,
            verificationCode = "123456",
            createdAt = Instant.ofEpochMilli(0L),
        )
    }

    // ---- homework 依赖的能力 ----

    override fun observeSession(): Flow<SessionState> = sessionFlow

    override suspend fun currentSession(): SessionState = sessionFlow.value

    override suspend fun logout() {
        sessionFlow.value = SessionState.NONE
    }

    override suspend fun listStudents(parentId: Long): List<Student> =
        students.values.filter { it.parentAccountId == parentId }.sortedBy { it.id }

    override suspend fun getStudent(studentId: Long): Student? = students[studentId]

    // ---- 与 homework 无关的能力：误用即失败 ----

    override suspend fun registerParent(account: String, password: String): ParentRegisterResult =
        unsupported()

    override suspend fun loginParent(account: String, password: String): ParentLoginResult =
        unsupported()

    override suspend fun addStudent(parentId: Long, name: String): AddStudentResult = unsupported()

    override suspend fun renameStudent(studentId: Long, newName: String): RenameStudentResult =
        unsupported()

    override suspend fun deleteStudent(studentId: Long): DeleteStudentResult = unsupported()

    override suspend fun resetStudentVerificationCode(
        studentId: Long,
    ): UpdateVerificationCodeResult = unsupported()

    override suspend fun updateStudentVerificationCode(
        studentId: Long,
        newCode: String,
    ): UpdateVerificationCodeResult = unsupported()

    override suspend fun enterAsStudent(
        parentAccount: String,
        verificationCode: String,
    ): StudentEnterResult = unsupported()

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("homework 模块单测不涉及该 auth 能力")
}
