package com.assignmate.app.stats.ui

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
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.stats.data.StatsFailure
import com.assignmate.app.stats.data.StatsRepository
import com.assignmate.app.stats.data.StatsResult
import com.assignmate.app.stats.domain.DaySummary
import com.assignmate.app.stats.domain.DifficultyLevel
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.ItemDetail
import com.assignmate.app.stats.domain.PausedHomework
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * stats **UI 层**测试替身：可控统计仓库 + 最小 auth 仓库。
 *
 * 与 `stats/data/StatsTestDoubles.kt` 的分工：数据层替身用于验证**真实仓库实现**的聚合口径，
 * 本文件用于验证 **ViewModel 的状态机与导航意图**——因此仓库返回的是一段段「预置结果」，
 * 并记录调用参数，使「幂等 start / 不重复取数 / 重试」等断言可以精确到调用次数。
 */

/** 预置结果的统计仓库替身：每次查询取队首结果并记录调用参数 */
internal class FakeStatsRepository : StatsRepository {

    /** summarizeDay 结果队列（空时回退 [dayResult]） */
    val dayQueue = ArrayDeque<StatsResult<DaySummary>>()

    /** itemDetail 结果队列（空时回退 [detailResult]） */
    val detailQueue = ArrayDeque<StatsResult<ItemDetail>>()

    /** history 结果队列（空时回退 [historyResult]） */
    val historyQueue = ArrayDeque<StatsResult<List<DaySummary>>>()

    /** 缺省结果（未入队时使用） */
    var dayResult: StatsResult<DaySummary> = StatsResult.Success(daySummary())
    var detailResult: StatsResult<ItemDetail> = StatsResult.Success(itemDetail())
    var historyResult: StatsResult<List<DaySummary>> = StatsResult.Success(emptyList())

    /** 调用记录：验证「幂等 start 不重复取数」与「重试再次取数」 */
    val dayCalls = mutableListOf<Pair<Long, Long>>()
    val detailCalls = mutableListOf<Long>()
    val historyCalls = mutableListOf<Pair<Long, HistoryQuery>>()

    override suspend fun summarizeDay(studentId: Long, epochDay: Long): StatsResult<DaySummary> {
        dayCalls += studentId to epochDay
        return dayQueue.removeFirstOrNull() ?: dayResult
    }

    override suspend fun itemDetail(homeworkId: Long): StatsResult<ItemDetail> {
        detailCalls += homeworkId
        return detailQueue.removeFirstOrNull() ?: detailResult
    }

    override suspend fun history(
        studentId: Long,
        query: HistoryQuery,
    ): StatsResult<List<DaySummary>> {
        historyCalls += studentId to query
        return historyQueue.removeFirstOrNull() ?: historyResult
    }
}

/** 最小 auth 仓库：只提供会话与学生档案读取（UI 层解析目标学生所需） */
internal class FakeStatsUiAuthRepository(
    initialSession: SessionState = STUDENT_SESSION,
) : AuthRepository {

    private var session: SessionState = initialSession
    private val students = mutableMapOf<Long, Student>()

    fun setSession(newSession: SessionState) {
        session = newSession
    }

    /** 登记学生档案（家长归属用于会话解析） */
    fun addStudentProfile(id: Long, parentAccountId: Long, name: String) {
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

    // ---- UI 层测试不涉及的写能力：误用即失败（避免假绿灯） ----

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
        throw UnsupportedOperationException("stats UI 层单测不涉及该 auth 能力")

    companion object {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L
        const val OTHER_STUDENT_ID = 3L

        /** 学生会话：固定取本人 */
        val STUDENT_SESSION = SessionState(
            role = Role.STUDENT,
            parentId = PARENT_ID,
            studentId = STUDENT_ID,
        )

        /** 家长会话：需显式指定路由学生 */
        val PARENT_SESSION = SessionState(role = Role.PARENT, parentId = PARENT_ID, studentId = null)
    }
}

/** 构造当日盘点结果（默认：1/2 完成、暂停 1 次 2 分钟、最久暂停为「语文生字」） */
internal fun daySummary(
    epochDay: Long = DAY_EPOCH,
    totalCount: Int = 2,
    completedCount: Int = 1,
    pauseCount: Int = 1,
    pausedTotalMillis: Long = 2 * MINUTE,
    mostPausedItem: PausedHomework? = PausedHomework(
        homeworkId = 1L,
        content = "语文生字",
        pausedMillis = 2 * MINUTE,
        pauseCount = 1,
    ),
): DaySummary = DaySummary(
    epochDay = epochDay,
    totalCount = totalCount,
    completedCount = completedCount,
    pauseCount = pauseCount,
    pausedTotalMillis = pausedTotalMillis,
    mostPausedItem = mostPausedItem,
)

/** 构造单项详情结果（[sessionCount] 为 0 即「尚未开始」） */
internal fun itemDetail(
    homeworkId: Long = 1L,
    content: String = "数学口算",
    status: HomeworkStatus = HomeworkStatus.COMPLETED,
    estimatedMinutes: Int? = 30,
    elapsedMillis: Long = 45 * MINUTE,
    pausedTotalMillis: Long = 10 * MINUTE,
    pauseCount: Int = 2,
    sessionCount: Int = 1,
    difficulty: DifficultyLevel = DifficultyLevel.SLOW,
    assessmentHint: String = "比预估慢较多，中途暂停 2 次",
): ItemDetail = ItemDetail(
    homeworkId = homeworkId,
    content = content,
    studentId = FakeStatsUiAuthRepository.STUDENT_ID,
    status = status,
    estimatedMinutes = estimatedMinutes,
    elapsedMillis = elapsedMillis,
    pausedTotalMillis = pausedTotalMillis,
    pauseCount = pauseCount,
    sessionCount = sessionCount,
    difficulty = difficulty,
    assessmentHint = assessmentHint,
)

/** 失败结果工厂 */
internal fun <T> statsFailure(reason: StatsFailure): StatsResult<T> = StatsResult.Failure(reason)

/** 2023-11-14 的 UTC 纪元日（与数据层测试夹具一致） */
internal const val DAY_EPOCH = 19_675L

/** 一分钟（毫秒） */
internal const val MINUTE = 60_000L