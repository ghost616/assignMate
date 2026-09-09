package com.assignmate.app.auth.data

import com.assignmate.app.auth.domain.AuthConstants
import com.assignmate.app.auth.domain.AuthValidators
import com.assignmate.app.auth.domain.PasswordHasher
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.auth.domain.Student
import com.assignmate.app.auth.domain.VerificationCodeGenerator
import com.assignmate.app.core.data.db.dao.ParentAccountDao
import com.assignmate.app.core.data.db.dao.StudentDao
import com.assignmate.app.core.data.db.entity.ParentAccountEntity
import com.assignmate.app.core.data.db.entity.StudentEntity
import com.assignmate.app.core.domain.prefs.KeyValueStore
import com.assignmate.app.core.domain.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * [AuthRepository] 默认实现：基于 core 的 ParentAccountDao/StudentDao（Room）与
 * KeyValueStore（DataStore）完成持久化；会话键统一落 KeyValueStore 并提供可观察 Flow。
 *
 * 关键规则（对应领域校验，防绕过 UI 直接调用）：
 * - 家长账号统一规范化（trim + 转小写）后用于格式校验/唯一性查询/入库/登录与进入匹配，
 *   使 "Parent001" 与 "parent001" 视为同一账号，杜绝大小写造成重复注册与登录歧义；
 * - 注册前查账号唯一性；密码经 [PasswordHasher] 哈希后存储，严禁明文落库；
 * - 新增学生前拦截超出 [AuthConstants.MAX_STUDENTS]；验证码同家长内唯一；
 * - 学生进入以“家长账号 + 验证码”组合查询，未知组合一律 [StudentEnterFailure.CODE_MISMATCH]。
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val parentAccountDao: ParentAccountDao,
    private val studentDao: StudentDao,
    private val keyValueStore: KeyValueStore,
    private val clock: Clock,
) : AuthRepository {

    // ---- 会话 ----

    override fun observeSession(): Flow<SessionState> =
        combine(
            keyValueStore.observeString(KEY_ROLE).map(Role::fromName),
            keyValueStore.observeString(KEY_PARENT_ID).map { it?.toLongOrNull() },
            keyValueStore.observeString(KEY_STUDENT_ID).map { it?.toLongOrNull() },
        ) { role, parentId, studentId ->
            SessionState(role = role, parentId = parentId, studentId = studentId)
        }

    override suspend fun currentSession(): SessionState =
        SessionState(
            role = Role.fromName(keyValueStore.getString(KEY_ROLE)),
            parentId = keyValueStore.getString(KEY_PARENT_ID)?.toLongOrNull(),
            studentId = keyValueStore.getString(KEY_STUDENT_ID)?.toLongOrNull(),
        )

    override suspend fun logout() {
        keyValueStore.remove(KEY_ROLE)
        keyValueStore.remove(KEY_PARENT_ID)
        keyValueStore.remove(KEY_STUDENT_ID)
    }

    // ---- 家长账号 ----

    override suspend fun registerParent(
        account: String,
        password: String,
    ): ParentRegisterResult {
        val accountValue = normalizeAccount(account)
        if (AuthValidators.parentAccountError(accountValue) != null) {
            return ParentRegisterResult.Failure(ParentRegisterFailure.INVALID_ACCOUNT)
        }
        if (AuthValidators.passwordError(password) != null) {
            return ParentRegisterResult.Failure(ParentRegisterFailure.INVALID_PASSWORD)
        }
        if (parentAccountDao.existsByAccount(accountValue)) {
            return ParentRegisterResult.Failure(ParentRegisterFailure.ACCOUNT_ALREADY_EXISTS)
        }
        val entity = ParentAccountEntity(
            account = accountValue,
            passwordHash = PasswordHasher.hash(password),
            createdAt = now(),
        )
        val parentId = parentAccountDao.insert(entity)
        persistParentSession(parentId)
        return ParentRegisterResult.Success
    }

    override suspend fun loginParent(
        account: String,
        password: String,
    ): ParentLoginResult {
        val accountValue = normalizeAccount(account)
        if (AuthValidators.parentAccountError(accountValue) != null) {
            return ParentLoginResult.Failure(ParentLoginFailure.INVALID_ACCOUNT)
        }
        if (AuthValidators.passwordError(password) != null) {
            return ParentLoginResult.Failure(ParentLoginFailure.INVALID_PASSWORD)
        }
        val entity = parentAccountDao.findByAccount(accountValue)
            ?: return ParentLoginResult.Failure(ParentLoginFailure.ACCOUNT_NOT_FOUND)
        if (!PasswordHasher.verify(password, entity.passwordHash)) {
            return ParentLoginResult.Failure(ParentLoginFailure.WRONG_PASSWORD)
        }
        persistParentSession(entity.id)
        return ParentLoginResult.Success
    }

    // ---- 学生档案 ----

    override suspend fun listStudents(parentId: Long): List<Student> =
        studentDao.findByParentAccountId(parentId).map { it.toDomain() }

    override suspend fun addStudent(
        parentId: Long,
        name: String,
    ): AddStudentResult {
        val nameValue = name.trim()
        if (AuthValidators.studentNameError(nameValue) != null) {
            return AddStudentResult.Failure(AddStudentFailure.INVALID_NAME)
        }
        val existing = studentDao.findByParentAccountId(parentId)
        if (!AuthValidators.canAddStudent(existing.size)) {
            return AddStudentResult.Failure(AddStudentFailure.STUDENT_LIMIT_REACHED)
        }
        val takenCodes = existing.mapTo(mutableSetOf()) { it.verificationCode }
        val newCode = generateUniqueCode(takenCodes)
            ?: return AddStudentResult.Failure(AddStudentFailure.CODE_GENERATION_FAILED)
        val createdAt = now()
        val entity = StudentEntity(
            parentAccountId = parentId,
            name = nameValue,
            verificationCode = newCode,
            createdAt = createdAt,
        )
        val id = studentDao.insert(entity)
        return AddStudentResult.Success(
            Student(
                id = id,
                parentAccountId = parentId,
                name = nameValue,
                verificationCode = newCode,
                createdAt = createdAt,
            ),
        )
    }

    override suspend fun renameStudent(
        studentId: Long,
        newName: String,
    ): RenameStudentResult {
        val entity = studentDao.findById(studentId)
            ?: return RenameStudentResult.Failure(RenameStudentFailure.STUDENT_NOT_FOUND)
        val nameValue = newName.trim()
        if (AuthValidators.studentNameError(nameValue) != null) {
            return RenameStudentResult.Failure(RenameStudentFailure.INVALID_NAME)
        }
        studentDao.update(entity.copy(name = nameValue))
        return RenameStudentResult.Success
    }

    override suspend fun deleteStudent(studentId: Long): DeleteStudentResult {
        val entity = studentDao.findById(studentId)
            ?: return DeleteStudentResult.Failure(DeleteStudentFailure.STUDENT_NOT_FOUND)
        studentDao.delete(entity)
        return DeleteStudentResult.Success
    }

    override suspend fun getStudent(studentId: Long): Student? =
        studentDao.findById(studentId)?.toDomain()

    // ---- 验证码管理 ----

    override suspend fun resetStudentVerificationCode(
        studentId: Long,
    ): UpdateVerificationCodeResult {
        val entity = studentDao.findById(studentId)
            ?: return UpdateVerificationCodeResult.Failure(
                UpdateVerificationCodeFailure.STUDENT_NOT_FOUND,
            )
        val siblingCodes = studentDao.findByParentAccountId(entity.parentAccountId)
            .asSequence()
            .filter { it.id != studentId }
            .mapTo(mutableSetOf()) { it.verificationCode }
        val newCode = generateUniqueCode(siblingCodes)
            ?: return UpdateVerificationCodeResult.Failure(
                UpdateVerificationCodeFailure.CODE_GENERATION_FAILED,
            )
        studentDao.update(entity.copy(verificationCode = newCode))
        return UpdateVerificationCodeResult.Success(newCode)
    }

    override suspend fun updateStudentVerificationCode(
        studentId: Long,
        newCode: String,
    ): UpdateVerificationCodeResult {
        val entity = studentDao.findById(studentId)
            ?: return UpdateVerificationCodeResult.Failure(
                UpdateVerificationCodeFailure.STUDENT_NOT_FOUND,
            )
        val codeValue = newCode.trim()
        if (AuthValidators.verificationCodeError(codeValue) != null) {
            return UpdateVerificationCodeResult.Failure(UpdateVerificationCodeFailure.INVALID_CODE)
        }
        val usedBySibling = studentDao.findByParentAccountId(entity.parentAccountId)
            .any { it.id != studentId && it.verificationCode == codeValue }
        if (usedBySibling) {
            return UpdateVerificationCodeResult.Failure(
                UpdateVerificationCodeFailure.CODE_ALREADY_USED,
            )
        }
        studentDao.update(entity.copy(verificationCode = codeValue))
        return UpdateVerificationCodeResult.Success(codeValue)
    }

    // ---- 学生进入校验 ----

    override suspend fun enterAsStudent(
        parentAccount: String,
        verificationCode: String,
    ): StudentEnterResult {
        val codeValue = verificationCode.trim()
        if (AuthValidators.verificationCodeError(codeValue) != null) {
            return StudentEnterResult.Failure(StudentEnterFailure.INVALID_VERIFICATION_CODE)
        }
        val parent = parentAccountDao.findByAccount(normalizeAccount(parentAccount))
            ?: return StudentEnterResult.Failure(StudentEnterFailure.PARENT_ACCOUNT_NOT_FOUND)
        val entity = studentDao.findByParentAccountIdAndVerificationCode(parent.id, codeValue)
            ?: return StudentEnterResult.Failure(StudentEnterFailure.CODE_MISMATCH)
        persistStudentSession(parent.id, entity.id)
        return StudentEnterResult.Success(entity.toDomain())
    }

    // ---- 私有工具 ----

    /** 账号统一规范化：去首尾空白 + 转小写，保证账号大小写不敏感（ASCII 转换不影响长度） */
    private fun normalizeAccount(account: String): String = account.trim().lowercase()

    /** 生成与同家长已有验证码不冲突的新验证码（防御性尝试上限见常量） */
    private fun generateUniqueCode(takenCodes: Set<String>): String? {
        repeat(AuthConstants.VERIFICATION_CODE_GENERATE_MAX_ATTEMPTS) {
            val candidate = VerificationCodeGenerator.generateCode()
            if (candidate !in takenCodes) {
                return candidate
            }
        }
        return null
    }

    private suspend fun persistParentSession(parentId: Long) {
        keyValueStore.putString(KEY_ROLE, Role.PARENT.name)
        keyValueStore.putString(KEY_PARENT_ID, parentId.toString())
        keyValueStore.remove(KEY_STUDENT_ID)
    }

    private suspend fun persistStudentSession(parentId: Long, studentId: Long) {
        keyValueStore.putString(KEY_ROLE, Role.STUDENT.name)
        keyValueStore.putString(KEY_PARENT_ID, parentId.toString())
        keyValueStore.putString(KEY_STUDENT_ID, studentId.toString())
    }

    private fun now(): Instant = Instant.ofEpochMilli(clock.currentTimeMillis())

    private fun StudentEntity.toDomain(): Student =
        Student(
            id = id,
            parentAccountId = parentAccountId,
            name = name,
            verificationCode = verificationCode,
            createdAt = createdAt,
        )

    private companion object {
        const val KEY_ROLE = "auth_session.role"
        const val KEY_PARENT_ID = "auth_session.parent_id"
        const val KEY_STUDENT_ID = "auth_session.student_id"
    }
}