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
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkOrderResult
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.data.ReorderDirection
import com.assignmate.app.homework.data.ScheduleUpdateResult
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * stats 模块测试替身集合：auth / 作业 / 作业每天详情 三个仓库的内存替身 + 可推进时钟 + 测试环境聚合。
 *
 * 设计取向（与 homework/timer 的测试替身一致）：
 * - 只实现 stats 真正依赖的**读**能力；写操作一律抛 [UnsupportedOperationException]，
 *   一旦被测代码误用会立刻暴露，避免「假绿灯」；
 * - 时间维度只在**作业每天详情**上：stats 不再读取计时会话，故本文件不再提供 timer 替身，
 *   改由 [FakeStatsDailyRecordRepository] 提供按天详情（业务自然日 epochDay 显式落库）。
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
 * 内存版**作业每天详情**仓库：stats 聚合的唯一时间数据源
 * （[loadByHomework] / [loadByStudentAndDay] / [find]），其余能力误用即失败。
 *
 * 观察版（observe*）按接口实现返回当前快照流，生产链路不消费（stats 只做一次性读快照）。
 */
internal class FakeStatsDailyRecordRepository : HomeworkDailyRecordRepository {

    private val records = mutableListOf<HomeworkDailyRecord>()

    /** 读取失败开关：验证仓库把读取异常收敛为失败结果而非向上抛出 */
    var failReads: Boolean = false

    /** 登记一条每天详情 */
    fun put(record: HomeworkDailyRecord) {
        records += record
    }

    override fun observeByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): Flow<List<HomeworkDailyRecord>> = flowOf(byStudentAndDay(studentId, epochDay))

    override suspend fun loadByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): List<HomeworkDailyRecord> {
        failIfNeeded()
        return byStudentAndDay(studentId, epochDay)
    }

    override suspend fun find(homeworkId: Long, epochDay: Long): HomeworkDailyRecord? {
        failIfNeeded()
        return records.firstOrNull { it.homeworkId == homeworkId && it.epochDay == epochDay }
    }

    override suspend fun loadByHomework(homeworkId: Long): List<HomeworkDailyRecord> {
        failIfNeeded()
        return records.filter { it.homeworkId == homeworkId }.sortedBy { it.epochDay }
    }

    override fun observeByHomework(homeworkId: Long): Flow<List<HomeworkDailyRecord>> =
        flowOf(records.filter { it.homeworkId == homeworkId }.sortedBy { it.epochDay })

    // ---- stats 不涉及的写能力：误用即失败 ----

    override suspend fun upsert(record: HomeworkDailyRecord): Long = unsupported()

    override suspend fun upsertStatus(
        homeworkId: Long,
        studentId: Long,
        epochDay: Long,
        status: HomeworkDayStatus,
        nowMillis: Long,
    ): Long = unsupported()

    override suspend fun updateStatus(id: Long, status: HomeworkDayStatus): Unit = unsupported()

    override suspend fun updateExecution(
        id: Long,
        startedAtMillis: Long?,
        estimatedMinutes: Int?,
        actualMinutes: Int?,
        pauseCount: Int,
        pausedTotalMinutes: Int,
        finishedAtMillis: Long?,
    ): Unit = unsupported()

    override suspend fun deleteByHomework(homeworkId: Long): Int = unsupported()

    /** 业务自然日折算：与生产实现同口径（按注入的业务时区折算，不用 UTC 毫秒） */
    override fun epochDayOf(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(StatsRepositoryTestEnv.ZONE).toLocalDate().toEpochDay()

    private fun byStudentAndDay(studentId: Long, epochDay: Long): List<HomeworkDailyRecord> =
        records
            .filter { it.studentId == studentId && it.epochDay == epochDay }
            .sortedBy { it.homeworkId }

    private fun failIfNeeded() {
        if (failReads) {
            throw IllegalStateException("模拟数据库读取失败")
        }
    }

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("stats 模块单测不涉及该每天详情写能力")
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

/** 可推进时钟：固定起点，用例可显式推进（仓库据它解析「今天」） */
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
 * 业务时区默认固定 Asia/Shanghai，固定时钟为 [FIXED_MILLIS]（2023-11-14 00:09:00 +08:00，即基准日 [DAY_EPOCH]）。
 *
 * [zoneId] 可覆写：**这正是「覆写 core 唯一业务时区来源」在单测里的等价形态**——
 * 覆写后仓库的「今天」、当天作业归属日、阶段可见性与阶段打卡进度必须一起切换
 * （见 `StatsBusinessZoneRepositoryTest`）。
 */
internal class StatsRepositoryTestEnv(
    initialSession: SessionState = SessionState(
        role = Role.STUDENT,
        parentId = PARENT_ID,
        studentId = STUDENT_ID,
    ),
    /** 业务时区（默认与生产 homework 模块绑定口径一致；跨时区用例传入覆写值） */
    val zoneId: ZoneId = ZONE,
) {

    val clock = StatsMutableClock(FIXED_MILLIS)
    val authRepository = FakeStatsAuthRepository(initialSession)
    val dailyRecordRepository = FakeStatsDailyRecordRepository()
    val homeworkRepository = FakeStatsHomeworkRepository()

    /** 被测对象：真实实现 + 内存替身 + 固定时钟/业务时区 */
    val repository: StatsRepository = StatsRepositoryImpl(
        homeworkRepository = homeworkRepository,
        dailyRecordRepository = dailyRecordRepository,
        authRepository = authRepository,
        clock = clock,
        zoneId = zoneId,
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

        /** 2023-11-14 的业务自然日（纪元日） */
        const val DAY_EPOCH = 19_675L

        /** 一分钟（毫秒） */
        const val MINUTE = 60_000L

        /** 当日窗口内第 [minutes] 分钟对应的时刻（构造 startedAt/finishedAt 用） */
        fun at(minutes: Long): Long = DAY_START_MILLIS + minutes * MINUTE

        /** 相对基准日的自然日偏移 */
        fun day(offsetDays: Long): Long = DAY_EPOCH + offsetDays
    }
}

/** 构造作业项测试数据（不经数据库；[createdAtMillis] 决定当天作业的「归属日」） */
internal fun statsTestHomework(
    id: Long,
    content: String = "作业$id",
    status: HomeworkStatus = HomeworkStatus.RECORDED,
    estimatedMinutes: Int? = 30,
    priority: Int = 100,
    studentId: Long = StatsRepositoryTestEnv.STUDENT_ID,
    type: HomeworkType = HomeworkType.TODAY,
    stageRange: StageRange? = null,
    createdAtMillis: Long = StatsRepositoryTestEnv.DAY_START_MILLIS,
    deadline: Instant? = null,
): HomeworkItem = HomeworkItem(
    id = id,
    parentAccountId = StatsRepositoryTestEnv.PARENT_ID,
    studentId = studentId,
    content = content,
    type = type,
    stageRange = stageRange,
    deadline = deadline,
    priority = priority,
    startTime = Instant.ofEpochMilli(createdAtMillis),
    estimatedMinutes = estimatedMinutes,
    status = status,
    createdByRole = CreatorRole.PARENT,
    createdAt = Instant.ofEpochMilli(createdAtMillis),
)

/**
 * 构造阶段作业项测试数据（一条作业项覆盖整段）：
 * 起始日与每日截止时刻按生产口径编码进 deadline，故 [HomeworkItem.stageStartEpochDay] /
 * [HomeworkItem.stageCoveredDays] 可正常还原（「今天是否落在阶段范围内」由它们决定）。
 */
internal fun statsTestStageHomework(
    id: Long,
    content: String = "阶段作业$id",
    estimatedMinutes: Int? = 30,
    priority: Int = 100,
    studentId: Long = StatsRepositoryTestEnv.STUDENT_ID,
    stageRange: StageRange = StageRange.ONE_WEEK,
    startEpochDay: Long = StatsRepositoryTestEnv.DAY_EPOCH,
): HomeworkItem = statsTestHomework(
    id = id,
    content = content,
    estimatedMinutes = estimatedMinutes,
    priority = priority,
    studentId = studentId,
    type = HomeworkType.STAGE,
    stageRange = stageRange,
    createdAtMillis = StatsRepositoryTestEnv.DAY_START_MILLIS,
    deadline = HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, LocalTime.of(21, 0)),
)

/** 构造作业每天详情测试数据（stats 的唯一时间数据源） */
internal fun statsTestDayRecord(
    homeworkId: Long,
    epochDay: Long = StatsRepositoryTestEnv.DAY_EPOCH,
    studentId: Long = StatsRepositoryTestEnv.STUDENT_ID,
    status: HomeworkDayStatus = HomeworkDayStatus.NOT_STARTED,
    startedAtMillis: Long? = null,
    estimatedMinutes: Int? = null,
    actualMinutes: Int? = null,
    pauseCount: Int = 0,
    pausedTotalMinutes: Int = 0,
    finishedAtMillis: Long? = null,
): HomeworkDailyRecord = HomeworkDailyRecord(
    id = 0L,
    homeworkId = homeworkId,
    studentId = studentId,
    epochDay = epochDay,
    status = status,
    startedAtMillis = startedAtMillis,
    estimatedMinutes = estimatedMinutes,
    actualMinutes = actualMinutes,
    pauseCount = pauseCount,
    pausedTotalMinutes = pausedTotalMinutes,
    finishedAtMillis = finishedAtMillis,
    createdAtMillis = StatsRepositoryTestEnv.DAY_START_MILLIS,
)
