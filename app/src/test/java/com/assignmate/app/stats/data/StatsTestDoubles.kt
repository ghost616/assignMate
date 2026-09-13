package com.assignmate.app.stats.data

import com.assignmate.app.auth.data.AddStudentResult
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.DeleteStudentResult
import com.assignmate.app.auth.data.ParentLoginResult
import com.assignmate.app.auth.data.ParentRegisterResult
import com.assignmate.app.auth.data.RenameStudentResult
import com.assignmate.app.auth.data.StudentEnterResult
import com.assignmate.app.auth.data.UpdateVerificationCodeResult
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.auth.domain.Student
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkOrderResult
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.data.ReorderDirection
import com.assignmate.app.homework.data.ScheduleUpdateResult
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.data.TimerCompleteResult
import com.assignmate.app.timer.data.TimerPauseResult
import com.assignmate.app.timer.data.TimerRepository
import com.assignmate.app.timer.data.TimerResumeResult
import com.assignmate.app.timer.data.TimerStartResult
import com.assignmate.app.timer.domain.PauseRecord
import com.assignmate.app.timer.domain.TimerSession
import com.assignmate.app.timer.domain.TimerSessionDetail
import com.assignmate.app.timer.domain.TimerPhase
import com.assignmate.app.timer.domain.TimerSessionSummary
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * stats 模块测试替身集合：auth/计时/作业三个仓库的内存替身 + 可推进时钟 + 测试环境聚合。
 *
 * 设计取向（与 homework/timer 的测试替身一致）：
 * - 只实现 stats 真正依赖的**读**能力；写操作一律抛 [UnsupportedOperationException]，
 *   一旦被测代码误用会立刻暴露，避免「假绿灯」；
 * - 时刻由 [StatsMutableClock] 显式控制，业务时区固定 Asia/Shanghai，
 *   使「当日窗口 / 跨天裁剪 / 暂停折算」等口径在测试中完全确定。
 */

/**
 * 内存版 auth 仓库：只提供会话读取与学生档案（stats 只读聚合所需）。
 *
 * 归属校验（[AuthRepository.isStudentOwnedBy] / [AuthRepository.ownedStudentIds]）**刻意不覆写**，
 * 直接沿用接口默认实现（基于本替身的 [getStudent] / [listStudents]，语义与生产实现一致），
 * 既让被测仓库经统一口径取数（stats 不再自建等价实现），也顺带看护接口默认实现这一回退路径。
 */
internal class FakeStatsAuthRepository(
    initialSession: SessionState = SessionState(
        role = Role.STUDENT,
        parentId = StatsRepositoryTestEnv.PARENT_ID,
        studentId = StatsRepositoryTestEnv.STUDENT_ID,
    ),
) : AuthRepository {

    private var session: SessionState = initialSession
    private val students = mutableMapOf<Long, Student>()

    /** 切换当前会话 */
    fun setSession(newSession: SessionState) {
        session = newSession
    }

    /** 登记学生档案（家长归属用于越权校验） */
    fun addStudentProfile(id: Long, parentAccountId: Long, name: String = "学生$id") {
        students[id] = Student(
            id = id,
            parentAccountId = parentAccountId,
            name = name,
            verificationCode = "123456",
            createdAt = Instant.ofEpochMilli(0L),
        )
    }

    override fun observeSession(): Flow<SessionState> = flowOf(session)

    override suspend fun currentSession(): SessionState = session

    override suspend fun getStudent(studentId: Long): Student? = students[studentId]

    override suspend fun listStudents(parentId: Long): List<Student> =
        students.values.filter { it.parentAccountId == parentId }

    override suspend fun logout() {
        session = SessionState.NONE
    }

    // ---- stats 不涉及的写能力：误用即失败 ----

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
        throw UnsupportedOperationException("stats 模块单测不涉及该 auth 能力")
}

/**
 * 内存版计时仓库：只提供 stats 聚合所需的两项读能力
 * （[loadSessionsByStudent] / [loadPausesByHomework]），其余能力误用即失败。
 */
internal class FakeStatsTimerRepository : TimerRepository {

    private val sessions = mutableListOf<TimerSession>()
    private val pauses = mutableListOf<PauseRecord>()

    /** 读取失败开关：验证仓库把读取异常收敛为失败结果而非向上抛出 */
    var failReads: Boolean = false

    /** 登记一条执行会话 */
    fun putSession(session: TimerSession) {
        sessions += session
    }

    /** 登记一条暂停明细 */
    fun putPause(pause: PauseRecord) {
        pauses += pause
    }

    override suspend fun loadSessionsByStudent(studentId: Long): List<TimerSession> {
        if (failReads) {
            throw IllegalStateException("模拟数据库读取失败")
        }
        return sessions.filter { it.studentId == studentId }.sortedBy { it.startedAt }
    }

    override suspend fun loadPausesByHomework(homeworkId: Long): List<PauseRecord> {
        if (failReads) {
            throw IllegalStateException("模拟数据库读取失败")
        }
        return pauses.filter { it.homeworkId == homeworkId }.sortedBy { it.pauseStartAt }
    }

    override suspend fun loadSessionsByHomework(homeworkId: Long): List<TimerSession> =
        sessions.filter { it.homeworkId == homeworkId }.sortedBy { it.startedAt }

    override suspend fun loadSession(sessionId: Long): TimerSession? = unsupported()

    override suspend fun loadSessionDetail(sessionId: Long): TimerSessionDetail? = unsupported()

    override suspend fun findActiveSession(studentId: Long): TimerSession? = unsupported()

    override suspend fun findActiveSessionByHomework(homeworkId: Long): TimerSession? = unsupported()

    override suspend fun findLatestSession(homeworkId: Long): TimerSession? = unsupported()

    override suspend fun loadPauses(sessionId: Long): List<PauseRecord> = unsupported()

    override suspend fun summarize(sessionId: Long): TimerSessionSummary? = unsupported()

    override suspend fun startSession(homeworkId: Long, sessionRole: Role): TimerStartResult =
        unsupported()

    override suspend fun pauseSession(sessionId: Long): TimerPauseResult = unsupported()

    override suspend fun resumeSession(sessionId: Long): TimerResumeResult = unsupported()

    override suspend fun completeSession(sessionId: Long, sessionRole: Role): TimerCompleteResult =
        unsupported()

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("stats 模块单测不涉及该 timer 能力")
}

/** 内存版作业仓库：只提供清单与单项读取，其余能力误用即失败 */
internal class FakeStatsHomeworkRepository : HomeworkRepository {

    private val items = linkedMapOf<Long, HomeworkItem>()

    /** 读取失败开关：验证仓库把读取异常收敛为失败结果而非向上抛出 */
    var failReads: Boolean = false

    /** 登记作业项 */
    fun put(item: HomeworkItem) {
        items[item.id] = item
    }

    override suspend fun listHomework(studentId: Long): List<HomeworkItem> {
        if (failReads) {
            throw IllegalStateException("模拟数据库读取失败")
        }
        return items.values
            .filter { it.studentId == studentId }
            .sortedWith(compareBy<HomeworkItem> { it.priority }.thenBy { it.createdAt })
    }

    override suspend fun getHomework(homeworkId: Long): HomeworkItem? {
        if (failReads) {
            throw IllegalStateException("模拟数据库读取失败")
        }
        return items[homeworkId]
    }

    override fun observeHomework(studentId: Long): Flow<List<HomeworkItem>> =
        flowOf(items.values.filter { it.studentId == studentId })

    // ---- stats 不涉及的写能力：误用即失败 ----

    override suspend fun addHomework(
        template: HomeworkTemplate,
        studentId: Long,
    ): AddHomeworkResult = unsupported()

    override suspend fun reorderHomework(
        homeworkId: Long,
        direction: ReorderDirection,
        sessionRole: Role,
    ): HomeworkOrderResult = unsupported()

    override suspend fun moveHomeworkTo(
        homeworkId: Long,
        targetIndex: Int,
        sessionRole: Role,
    ): HomeworkOrderResult = unsupported()

    override suspend fun updateSchedule(
        homeworkId: Long,
        startTime: Instant,
        estimatedMinutes: Int,
        sessionRole: Role,
    ): ScheduleUpdateResult = unsupported()

    override suspend fun clearSchedule(
        homeworkId: Long,
        sessionRole: Role,
    ): HomeworkOperationResult = unsupported()

    override suspend fun updateContent(
        homeworkId: Long,
        content: String,
        sessionRole: Role,
    ): HomeworkOperationResult = unsupported()

    override suspend fun updateTemplate(
        homeworkId: Long,
        type: HomeworkType,
        stageRange: StageRange?,
        deadline: Instant?,
        sessionRole: Role,
    ): HomeworkOperationResult = unsupported()

    override suspend fun deleteHomework(
        homeworkId: Long,
        sessionRole: Role,
    ): HomeworkOperationResult = unsupported()

    override suspend fun markPending(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        unsupported()

    override suspend fun startProgress(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        unsupported()

    override suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        unsupported()

    override suspend fun reopen(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
        unsupported()

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("stats 模块单测不涉及该 homework 能力")
}

/** 可推进时钟：固定起点，用例可显式推进以构造暂停/跨天场景 */
internal class StatsMutableClock(initialMillis: Long) : Clock {

    private val current = AtomicLong(initialMillis)

    override fun currentTimeMillis(): Long = current.get()

    /** 推进 [millis] 毫秒 */
    fun advance(millis: Long) {
        current.addAndGet(millis)
    }

    /** 直接设置当前时刻 */
    fun set(millis: Long) {
        current.set(millis)
    }
}

/**
 * stats 仓库测试环境：内存替身 + 真实 [StatsRepositoryImpl]（被测对象）。
 * 业务时区固定 Asia/Shanghai，固定时钟起点为 [FIXED_MILLIS]（2023-11-14 00:09:00 +08:00）。
 */
internal class StatsRepositoryTestEnv(
    initialSession: SessionState = SessionState(
        role = Role.STUDENT,
        parentId = PARENT_ID,
        studentId = STUDENT_ID,
    ),
) {

    val clock = StatsMutableClock(FIXED_MILLIS)
    val authRepository = FakeStatsAuthRepository(initialSession)
    val timerRepository = FakeStatsTimerRepository()
    val homeworkRepository = FakeStatsHomeworkRepository()

    /** 被测对象：真实实现 + 内存替身 + 固定时钟/时区 */
    val repository: StatsRepository = StatsRepositoryImpl(
        homeworkRepository = homeworkRepository,
        timerRepository = timerRepository,
        authRepository = authRepository,
        clock = clock,
        zoneId = ZONE,
    )

    init {
        authRepository.addStudentProfile(id = STUDENT_ID, parentAccountId = PARENT_ID, name = "小明")
        authRepository.addStudentProfile(
            id = OTHER_STUDENT_ID,
            parentAccountId = OTHER_PARENT_ID,
            name = "小红",
        )
    }

    companion object {

        const val PARENT_ID = 1L
        const val OTHER_PARENT_ID = 9L
        const val STUDENT_ID = 2L
        const val OTHER_STUDENT_ID = 3L

        /** 固定时钟：2023-11-14 00:09:00 +08:00 */
        const val FIXED_MILLIS = 1_699_891_740_000L

        /** 业务时区（与生产 homework 模块绑定口径一致） */
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 2023-11-14 00:00:00 +08:00 */
        const val DAY_START_MILLIS = 1_699_891_200_000L

        /** 2023-11-14 的 UTC 纪元日 */
        const val DAY_EPOCH = 19_675L

        /** 一分钟（毫秒） */
        const val MINUTE = 60_000L

        /** 当日窗口内第 [minutes] 分钟对应的时刻 */
        fun at(minutes: Long): Long = DAY_START_MILLIS + minutes * MINUTE
    }
}

/** 构造作业项测试数据（不经数据库） */
internal fun statsTestHomework(
    id: Long,
    content: String = "作业$id",
    status: HomeworkStatus = HomeworkStatus.PENDING,
    estimatedMinutes: Int? = 30,
    priority: Int = 100,
    studentId: Long = StatsRepositoryTestEnv.STUDENT_ID,
): HomeworkItem = HomeworkItem(
    id = id,
    parentAccountId = StatsRepositoryTestEnv.PARENT_ID,
    studentId = studentId,
    content = content,
    type = HomeworkType.TODAY,
    stageRange = null,
    deadline = null,
    priority = priority,
    startTime = Instant.ofEpochMilli(StatsRepositoryTestEnv.DAY_START_MILLIS),
    estimatedMinutes = estimatedMinutes,
    status = status,
    createdByRole = CreatorRole.PARENT,
    createdAt = Instant.ofEpochMilli(StatsRepositoryTestEnv.DAY_START_MILLIS),
)

/** 构造执行会话测试数据（默认按是否已结束推导阶段：未结束 RUNNING，已结束 FINISHED） */
internal fun statsTestSession(
    sessionId: Long,
    homeworkId: Long,
    startedAtMillis: Long,
    finishedAtMillis: Long? = null,
    studentId: Long = StatsRepositoryTestEnv.STUDENT_ID,
    phase: TimerPhase = if (finishedAtMillis == null) TimerPhase.RUNNING else TimerPhase.FINISHED,
): TimerSession = TimerSession(
    id = sessionId,
    homeworkId = homeworkId,
    studentId = studentId,
    parentAccountId = StatsRepositoryTestEnv.PARENT_ID,
    startedAt = Instant.ofEpochMilli(startedAtMillis),
    finishedAt = finishedAtMillis?.let { Instant.ofEpochMilli(it) },
    pausedTotalMillis = 0L,
    pauseCount = 0,
    phase = phase,
)

/** 构造暂停明细测试数据 */
internal fun statsTestPause(
    id: Long,
    sessionId: Long,
    homeworkId: Long,
    startMillis: Long,
    endMillis: Long? = null,
): PauseRecord = PauseRecord(
    id = id,
    sessionId = sessionId,
    homeworkId = homeworkId,
    pauseStartAt = Instant.ofEpochMilli(startMillis),
    pauseEndAt = endMillis?.let { Instant.ofEpochMilli(it) },
)