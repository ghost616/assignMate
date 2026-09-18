package com.assignmate.app.settings

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
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrConfigStore
import com.assignmate.app.core.domain.ocr.OcrImageFileCleaner
import com.assignmate.app.core.domain.ocr.PendingOcrRepository
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.core.domain.prefs.ThemePreferenceStore
import com.assignmate.app.settings.domain.OcrLogSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * settings 模块单测替身集合（纯 JVM，不依赖 Android/Room）。
 *
 * 设计取向：替身只实现被测代码真正依赖的能力，其余能力一律抛 [UnsupportedOperationException]，
 * 一旦被测代码误用会立刻暴露，避免「静默通过」的假绿灯。
 */

/** 记录型日志出口：不打印内容，只保存日志行，供「日志不含密钥明文」断言使用 */
class RecordingOcrLogSink : OcrLogSink {

    val lines = mutableListOf<Pair<String, String>>()

    override fun log(tag: String, message: String) {
        lines += tag to message
    }

    /** 全部日志行拼接文本（断言「不含某敏感值」的搜索目标） */
    fun joined(): String = lines.joinToString(separator = "\n") { (tag, message) -> "$tag $message" }
}

/**
 * 内存版 OCR 配置存储（core [OcrConfigStore] 替身）：
 * 与 DataStore 实现同语义（覆盖式保存 + 可观察），密钥以「透明加密替身」原样持有，便于断言保存往返值。
 */
class FakeOcrConfigStore(initial: OcrConfig = OcrConfig()) : OcrConfigStore {

    private val state = MutableStateFlow(initial)

    /** 当前落盘值 */
    val saved: OcrConfig get() = state.value

    /** save 调用次数（断言「被拒绝时未触达存储」） */
    var saveCount: Int = 0
        private set

    override val config: Flow<OcrConfig> = state

    override suspend fun save(config: OcrConfig) {
        saveCount += 1
        state.value = config
    }

    override suspend fun clear() {
        state.value = OcrConfig()
    }
}

/**
 * 内存版待重试任务存储（core [PendingOcrRepository] 替身）：可写入任意状态的记录，供统计与清理断言。
 */
class FakePendingOcrRepository : PendingOcrRepository {

    private val tasks = mutableListOf<PendingOcrTask>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    /** 库内记录快照 */
    fun snapshot(): List<PendingOcrTask> = tasks.toList()

    /** 直接登记一条记录（返回自增 id） */
    fun insert(
        path: String,
        status: PendingOcrStatus = PendingOcrStatus.PENDING,
        createdAtMillis: Long = tasks.size.toLong() + 1L,
    ): Long {
        val id = nextId++
        tasks += PendingOcrTask(
            id = id,
            localImagePath = path,
            createdAtMillis = createdAtMillis,
            status = status,
        )
        revision.value += 1
        return id
    }

    override suspend fun add(task: PendingOcrTask): Long = insert(task.localImagePath, task.status)

    override fun observePending(): Flow<List<PendingOcrTask>> =
        revision.map { tasks.filter { it.status == PendingOcrStatus.PENDING } }

    override suspend fun loadByStatus(status: PendingOcrStatus): List<PendingOcrTask> =
        tasks.filter { it.status == status }

    override suspend fun updateStatus(id: Long, status: PendingOcrStatus, retryCount: Int) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) {
            tasks[index] = tasks[index].copy(status = status, retryCount = retryCount)
            revision.value += 1
        }
    }

    override suspend fun remove(id: Long) {
        tasks.removeAll { it.id == id }
        revision.value += 1
    }

    override suspend fun clearAll(): Set<String> {
        val paths = tasks.mapTo(mutableSetOf()) { it.localImagePath }
        tasks.clear()
        revision.value += 1
        return paths
    }
}

/** 图片文件删除替身：记录被请求删除的路径（断言「图片确实被删」） */
class RecordingOcrImageFileCleaner : OcrImageFileCleaner {

    val deletedPaths = mutableListOf<String>()

    override suspend fun delete(path: String) {
        deletedPaths += path
    }
}

/** 内存版主题偏好存储（core [ThemePreferenceStore] 替身）：三档读写与观察 */
class FakeThemePreferenceStore(initial: ThemeMode = ThemeMode.DEFAULT) : ThemePreferenceStore {

    private val state = MutableStateFlow(initial)

    /** 当前落盘档位（断言持久化往返） */
    val stored: ThemeMode get() = state.value

    /** setThemeMode 调用次数（断言被拒绝时未落盘） */
    var setCount: Int = 0
        private set

    override fun observeThemeMode(): Flow<ThemeMode> = state

    override suspend fun themeMode(): ThemeMode = state.value

    override suspend fun setThemeMode(mode: ThemeMode) {
        setCount += 1
        state.value = mode
    }
}

/**
 * 内存版 auth 仓库替身：settings 只依赖会话读取与登出；
 * 账户/验证码等无关能力抛异常，防止被测代码误用。
 */
class FakeSettingsAuthRepository(
    initialSession: SessionState = SessionState(role = null),
) : AuthRepository {

    private val sessionFlow = MutableStateFlow(initialSession)

    fun setSession(session: SessionState) {
        sessionFlow.value = session
    }

    override fun observeSession(): Flow<SessionState> = sessionFlow

    override suspend fun currentSession(): SessionState = sessionFlow.value

    override suspend fun logout() {
        sessionFlow.value = SessionState(role = null)
    }

    override suspend fun getStudent(studentId: Long): Student? = null

    override suspend fun listStudents(parentId: Long): List<Student> = emptyList()

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
        throw UnsupportedOperationException("settings 模块单测不涉及该 auth 能力")
}