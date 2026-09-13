package com.assignmate.app.auth.data

import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.auth.domain.Student
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * auth 仓库接口：家长账号（注册/登录/登出）、学生档案 CRUD、验证码管理、
 * 学生进入校验与会话持久化（KeyValueStore 落盘 + 可观察 Flow），
 * 以及家长-学生归属校验（homework/timer/stats 共用的统一口径）。
 *
 * 实现契约：所有方法不抛业务异常，成败统一收敛为密封结果（reason 枚举机器可读，
 * 用户文案由 UI 层映射）；家长账号入库/查询前一律规范化（trim + 转小写，大小写不敏感），
 * 密码永不落明文（仅存哈希）。
 */
interface AuthRepository {

    // ---- 会话 ----
    /** 观察当前会话（角色/家长 id/学生 id），登出后回到 [SessionState.NONE] */
    fun observeSession(): Flow<SessionState>

    /** 读取当前会话快照 */
    suspend fun currentSession(): SessionState

    /** 登出：清除当前会话全部键（家长/学生均回到身份选择页） */
    suspend fun logout()

    // ---- 家长账号 ----
    /** 家长注册：账号唯一性校验 + 密码本地哈希存储；成功即建立家长会话 */
    suspend fun registerParent(account: String, password: String): ParentRegisterResult

    /** 家长登录：账号存在 + 密码哈希比对；成功即建立家长会话 */
    suspend fun loginParent(account: String, password: String): ParentLoginResult

    // ---- 学生档案（家长维度 CRUD） ----
    /** 按家长列出其全部学生（按创建时间升序） */
    suspend fun listStudents(parentId: Long): List<Student>

    /** 按家长新增学生（超 [AuthConstants.MAX_STUDENTS] 拦截），自动生成进入验证码 */
    suspend fun addStudent(parentId: Long, name: String): AddStudentResult

    /** 重命名学生 */
    suspend fun renameStudent(studentId: Long, newName: String): RenameStudentResult

    /** 删除学生档案 */
    suspend fun deleteStudent(studentId: Long): DeleteStudentResult

    /** 按 id 查询学生（用于会话学生端展示等；不存在返回 null） */
    suspend fun getStudent(studentId: Long): Student?

    // ---- 家长-学生归属校验（统一口径，供 homework/timer/stats 等业务模块共用） ----
    /**
     * 判断学生档案 [studentId] 是否归属于家长 [parentId]（即档案存在且 parentAccountId == parentId）。
     *
     * 本方法为跨模块归属校验的**唯一口径**：homework/timer/stats 等模块的「目标学生是否在当前会话
     * 可见范围内」判定（家长分支）应统一复用本方法，替代各自私有的 `canTargetStudent` 等价实现，
     * 避免归属口径出现分叉。学生会话场景请结合会话 [SessionState.studentId] 自行判定「仅限本人」。
     *
     * 契约：不抛业务异常，任何数据层异常/档案不存在均收敛为 false；[parentId] 或 [studentId] 非正直接返回 false。
     *
     * 默认实现基于 [getStudent]（测试替身与自定义实现零改动即语义一致）；
     * 生产实现 [AuthRepositoryImpl] 覆写为直连 StudentDao 的单次按主键查询。
     */
    suspend fun isStudentOwnedBy(parentId: Long, studentId: Long): Boolean {
        if (parentId <= 0L || studentId <= 0L) {
            return false
        }
        return try {
            getStudent(studentId)?.parentAccountId == parentId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 取家长 [parentId] 名下全部学生 id（供批量/列表场景一次取数，避免逐条调用
     * [isStudentOwnedBy] 造成 N 次查询）。
     *
     * 契约：不抛业务异常，异常/无学生均收敛为空集；[parentId] 非正直接返回空集。
     * 调用方可用 `studentId in ownedStudentIds(parentId)` 做批量归属过滤。
     *
     * 默认实现基于 [listStudents]（测试替身与自定义实现零改动即语义一致）；
     * 生产实现 [AuthRepositoryImpl] 覆写为直连 StudentDao 的单次家长维度查询。
     *
     * @return 该家长名下学生 id 集合（家长不存在或名下无学生时为空集）
     */
    suspend fun ownedStudentIds(parentId: Long): Set<Long> {
        if (parentId <= 0L) {
            return emptySet()
        }
        return try {
            listStudents(parentId).mapTo(mutableSetOf()) { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
    }

    // ---- 验证码管理 ----
    /** 重新生成某学生的进入验证码（同家长内唯一） */
    suspend fun resetStudentVerificationCode(studentId: Long): UpdateVerificationCodeResult

    /** 家长自定义修改某学生验证码（4-6 位数字 + 同家长内唯一） */
    suspend fun updateStudentVerificationCode(
        studentId: Long,
        newCode: String,
    ): UpdateVerificationCodeResult

    // ---- 学生进入校验 ----
    /** 学生凭“家长账号 + 验证码”进入：匹配成功建立学生会话（直达本人界面） */
    suspend fun enterAsStudent(
        parentAccount: String,
        verificationCode: String,
    ): StudentEnterResult
}

/** 家长注册结果 */
sealed class ParentRegisterResult {
    /** 注册成功（已建立家长会话） */
    data object Success : ParentRegisterResult()

    data class Failure(val reason: ParentRegisterFailure) : ParentRegisterResult()
}

/** 家长注册失败原因 */
enum class ParentRegisterFailure {
    /** 账号已存在（唯一性校验拦截） */
    ACCOUNT_ALREADY_EXISTS,

    /** 账号格式不合法 */
    INVALID_ACCOUNT,

    /** 密码不合法 */
    INVALID_PASSWORD,
}

/** 家长登录结果 */
sealed class ParentLoginResult {
    /** 登录成功（已建立家长会话） */
    data object Success : ParentLoginResult()

    data class Failure(val reason: ParentLoginFailure) : ParentLoginResult()
}

/** 家长登录失败原因 */
enum class ParentLoginFailure {
    /** 账号未注册 */
    ACCOUNT_NOT_FOUND,

    /** 密码错误 */
    WRONG_PASSWORD,

    /** 账号格式不合法 */
    INVALID_ACCOUNT,

    /** 密码格式不合法 */
    INVALID_PASSWORD,
}

/** 学生进入校验结果 */
sealed class StudentEnterResult {
    /** 校验成功：已建立学生会话，直达本人界面 */
    data class Success(val student: Student) : StudentEnterResult()

    data class Failure(val reason: StudentEnterFailure) : StudentEnterResult()
}

/** 学生进入失败原因 */
enum class StudentEnterFailure {
    /** 家长账号不存在 */
    PARENT_ACCOUNT_NOT_FOUND,

    /** 验证码格式不合法 */
    INVALID_VERIFICATION_CODE,

    /** 验证码与该家长名下学生不匹配 */
    CODE_MISMATCH,
}

/** 新增学生结果 */
sealed class AddStudentResult {
    /** 新增成功：学生已自动分配进入验证码（见 [Student.verificationCode]） */
    data class Success(val student: Student) : AddStudentResult()

    data class Failure(val reason: AddStudentFailure) : AddStudentResult()
}

/** 新增学生失败原因 */
enum class AddStudentFailure {
    /** 已达学生数量上限 */
    STUDENT_LIMIT_REACHED,

    /** 姓名不合法 */
    INVALID_NAME,

    /** 验证码生成失败（冲突规避达到防御性上限，实际极难触发） */
    CODE_GENERATION_FAILED,
}

/** 重命名学生结果 */
sealed class RenameStudentResult {
    data object Success : RenameStudentResult()

    data class Failure(val reason: RenameStudentFailure) : RenameStudentResult()
}

/** 重命名失败原因 */
enum class RenameStudentFailure {
    /** 学生不存在（可能已被删除） */
    STUDENT_NOT_FOUND,

    /** 姓名不合法 */
    INVALID_NAME,
}

/** 删除学生结果 */
sealed class DeleteStudentResult {
    data object Success : DeleteStudentResult()

    data class Failure(val reason: DeleteStudentFailure) : DeleteStudentResult()
}

/** 删除失败原因 */
enum class DeleteStudentFailure {
    /** 学生不存在（可能已被删除） */
    STUDENT_NOT_FOUND,
}

/** 验证码更新（重新生成 / 自定义修改）结果 */
sealed class UpdateVerificationCodeResult {
    /** 更新成功：携带最新生效的验证码 */
    data class Success(val newCode: String) : UpdateVerificationCodeResult()

    data class Failure(val reason: UpdateVerificationCodeFailure) : UpdateVerificationCodeResult()
}

/** 验证码更新失败原因 */
enum class UpdateVerificationCodeFailure {
    /** 学生不存在（可能已被删除） */
    STUDENT_NOT_FOUND,

    /** 新验证码格式不合法 */
    INVALID_CODE,

    /** 同家长名下已有学生使用该验证码 */
    CODE_ALREADY_USED,

    /** 验证码生成失败（冲突规避达到防御性上限） */
    CODE_GENERATION_FAILED,
}