package com.assignmate.app.timer.data

import com.assignmate.app.auth.data.FakeKeyValueStore
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.db.FakeHomeworkDailyRecordDao
import com.assignmate.app.core.data.db.HomeworkDailyRecordRepositoryImpl
import com.assignmate.app.core.data.db.entity.HomeworkItemEntity
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import com.assignmate.app.homework.data.FakeAuthRepository
import com.assignmate.app.homework.data.FakeHomeworkItemDao
import com.assignmate.app.homework.data.HomeworkRepositoryImpl
import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * timer 测试环境：聚合内存版计时 DAO 与固定可推进时钟，并复用 homework 模块的内存替身
 * （[FakeHomeworkItemDao] / [FakeAuthRepository] / [MutableClock]）与**真实** homework 仓库实现，
 * 从而验证「作业置进行中 / 置已完成」这一跨模块同步语义与生产一致（避免手写替身导致口径漂移）。
 *
 * 逐日归属（本模块新增）：[dailyRecordRepository] 为 core 契约的真实实现 + 内存 DAO，
 * 同时注入给 homework 仓库与 timer 仓库——因此「状态流转写当天详情」与「计时写归属日详情」
 * 两条链路在测试中与生产一致，可断言跨天时最终留下的每天详情。
 *
 * 默认会话为学生会话（本人名下作业）；家长/无会话场景分别经 [useParentSession] / [logout] 切换。
 */
class TimerTestEnv(
    initialSession: SessionState = SessionState(
        role = Role.STUDENT,
        parentId = PARENT_ID,
        studentId = STUDENT_ID,
    ),
) {

    /** 可推进时钟：默认固定在 [FIXED_MILLIS]，用于构造暂停时长等时间敏感场景 */
    val clock = MutableClock(FIXED_MILLIS)

    val timerSessionDao = FakeTimerSessionDao()
    val pauseRecordDao = FakePauseRecordDao()
    val homeworkDao = FakeHomeworkItemDao()
    val authRepository = FakeAuthRepository(initialSession)

    /** 「作业每天详情」内存 DAO 与 core 契约实现（timer/homework 两个仓库共用同一份数据） */
    val dailyRecordDao = FakeHomeworkDailyRecordDao()
    val dailyRecordRepository: HomeworkDailyRecordRepository =
        HomeworkDailyRecordRepositoryImpl(dailyRecordDao, ZONE)

    init {
        // homework 仓库的家长归属围栏经 auth 的 isStudentOwnedBy 判定，故需登记学生档案
        // （timer 场景固定「家长 PARENT_ID 名下学生 STUDENT_ID」，与 seedHomework 的归属一致）
        authRepository.addStudentProfile(id = STUDENT_ID, parentAccountId = PARENT_ID)
    }

    /** 真实 homework 仓库（内存 DAO + 内存 auth + 同一时钟 + 同一每天详情仓库）：作业状态同步口径与生产一致 */
    val homeworkRepository = HomeworkRepositoryImpl(homeworkDao, authRepository, clock, ZONE, dailyRecordRepository)

    /** 事务边界替身：直接执行并记录调用次数（断言仓库把多次写收敛进一个事务） */
    val transactionRunner = FakeTimerTransactionRunner()

    val repository = TimerRepositoryImpl(
        timerSessionDao = timerSessionDao,
        pauseRecordDao = pauseRecordDao,
        homeworkRepository = homeworkRepository,
        authRepository = authRepository,
        clock = clock,
        transactionRunner = transactionRunner,
        dailyRecordRepository = dailyRecordRepository,
    )

    // ---- timer-B 依赖（提醒 / 语音 / 权限）：默认替身，用例可按需覆写 ----

    /** 闹钟调度替身：记录每次设置/取消调用的参数，断言「触发时刻与作业绑定」 */
    val alarmScheduler = FakeAlarmScheduler()

    /** TTS 引擎替身：记录播报文案序列 */
    val ttsPlayer = FakeTextToSpeechPlayer()

    /** 播报开关与超时去重的落盘替身（内存 KeyValueStore） */
    val keyValueStore = FakeKeyValueStore()

    /** 语音播报门面（真实实现 + 替身引擎，验证清洗与静默降级链路） */
    val voiceGuide: TimerVoiceGuide = TtsTimerVoiceGuide(ttsPlayer)

    val voiceSettings: TimerVoiceSettings = DataStoreTimerVoiceSettings(keyValueStore)

    val overduePromptStore: TimerOverduePromptStore = DataStoreTimerOverduePromptStore(keyValueStore)

    /** 休息起点持久化（真实实现 + 内存 KeyValueStore）：支撑休息页幂等重建用例 */
    val restStartStore: TimerRestStartStore = DataStoreTimerRestStartStore(keyValueStore)

    /** 逐日提醒的已设登记表（真实实现 + 内存 KeyValueStore）：支撑阶段作业「范围变更/删除后取消」用例 */
    val reminderScheduleStore: TimerReminderScheduleStore =
        DataStoreTimerReminderScheduleStore(keyValueStore)

    /** 到点提醒协调器（真实实现 + 替身闹钟 + 同一业务时区与登记表）：重设/取消口径与生产一致 */
    val reminderCoordinator = HomeworkReminderCoordinator(
        homeworkRepository = homeworkRepository,
        alarmScheduler = alarmScheduler,
        clock = clock,
        zoneId = ZONE,
        scheduleStore = reminderScheduleStore,
    )

    /** 权限状态（用例可修改后再构造 ViewModel，用于覆盖引导文案分支） */
    var permissionStatus = TimerPermissionStatus(
        notificationsGranted = true,
        exactAlarmGranted = true,
    )

    /** 权限状态查询替身（fun interface，直接用 lambda 读取可变的 [permissionStatus]） */
    val permissionChecker: TimerPermissionChecker = TimerPermissionChecker { permissionStatus }

    /** 推进时钟（毫秒），用于构造暂停时长、超时等场景 */
    fun advance(millis: Long) {
        clock.advance(millis)
    }

    /** 切换为家长会话（无 studentId，家长侧场景） */
    fun useParentSession() {
        authRepository.setSession(
            SessionState(role = Role.PARENT, parentId = PARENT_ID, studentId = null),
        )
    }

    /** 退出登录（无会话场景） */
    fun logout() {
        authRepository.setSession(SessionState.NONE)
    }

    /**
     * 写入一条作业项并返回领域模型（默认：当天作业、待完成、预估 30 分钟、日期为当前固定时钟所在日）。
     *
     * @param studentId 归属学生，可与会话学生不同以构造「他人名下作业」的越权场景
     * @param type 作业类型：STAGE 时按 [stageRange] 与 [stageDailyTime] 编码 deadline（每日截止时刻）
     * @param stageStartEpochDay 阶段起始日（仅 STAGE 使用，默认「今天」，与 homework 编码口径一致）
     */
    suspend fun seedHomework(
        status: HomeworkStatus = HomeworkStatus.PENDING,
        studentId: Long = STUDENT_ID,
        content: String = "语文第 3 课生字",
        estimatedMinutes: Int? = 30,
        startTime: Instant? = null,
        deadline: Instant? = null,
        type: HomeworkType = HomeworkType.TODAY,
        stageRange: StageRange? = null,
        stageDailyTime: LocalTime? = null,
        stageStartEpochDay: Long = todayEpochDay(),
    ): HomeworkItem {
        val resolvedDeadline = if (type == HomeworkType.STAGE && stageDailyTime != null) {
            HomeworkDailyDeadlineCodec.encodeStageDaily(stageStartEpochDay, stageDailyTime)
        } else {
            deadline
        }
        val entity = HomeworkItemEntity(
            parentAccountId = PARENT_ID,
            studentId = studentId,
            content = content,
            type = type.name,
            stageRange = stageRange?.name,
            deadline = resolvedDeadline,
            priority = PRIORITY,
            startTime = startTime,
            estimatedMinutes = estimatedMinutes,
            status = status.name,
            createdByRole = CreatorRole.PARENT.name,
            createdAt = Instant.ofEpochMilli(clock.currentTimeMillis()),
        )
        val id = homeworkDao.insert(entity)
        return requireNotNull(homeworkRepository.getHomework(id))
    }

    /** 业务自然日口径的「今天」（与 core / homework 同一折算口径） */
    fun todayEpochDay(): Long = dailyRecordRepository.epochDayOf(clock.currentTimeMillis())

    /**
     * 写入一条**阶段作业**（1 条作业项 + 每日截止时刻编码进 deadline），
     * 供「逐日归属」与「每日到点提醒」用例使用。
     *
     * @param startEpochDay 阶段起始日（覆盖区间 = 起始日 + [stageRange].days 天）
     * @param dailyTime 每日截止时刻（到点提醒的钟面）
     */
    suspend fun stageHomework(
        startEpochDay: Long = todayEpochDay(),
        stageRange: StageRange = StageRange.ONE_WEEK,
        dailyTime: LocalTime = LocalTime.of(21, 0),
        status: HomeworkStatus = HomeworkStatus.PENDING,
        content: String = "阶段作业$startEpochDay",
        estimatedMinutes: Int? = 30,
    ): HomeworkItem = seedHomework(
        status = status,
        content = content,
        estimatedMinutes = estimatedMinutes,
        type = HomeworkType.STAGE,
        stageRange = stageRange,
        stageDailyTime = dailyTime,
        stageStartEpochDay = startEpochDay,
    )

    /** 某作业的全部每天详情（按自然日升序），供逐日归属与提醒用例断言 */
    suspend fun dailyRecordsOf(homeworkId: Long): List<HomeworkDailyRecord> =
        dailyRecordRepository.loadByHomework(homeworkId)

    companion object {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L

        /** 首条作业的优先级（清单按优先级升序，数值越小越靠前） */
        const val PRIORITY = 100

        /** 固定时钟：2023-11-14T22:13:20Z（与 auth/homework 测试一致，便于跨模块心智一致） */
        const val FIXED_MILLIS = 1_700_000_000_000L

        /** 业务时区（与生产 homework 模块绑定口径一致） */
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

/**
 * 构造作业项测试数据（不经数据库，供计时纯函数与仓库错误分支测试使用）。
 *
 * 默认：学生会话名下、当天作业、待完成、未排定时间；需要超时判定场景时显式传入
 * [startTimeMillis] / [estimatedMinutes] / [deadlineMillis]；
 * 需要阶段作业的每日提醒场景时传入 [type] = STAGE 与 [stageDailyTime]（deadline 按每日时刻编码）。
 */
internal fun timerTestHomework(
    id: Long,
    studentId: Long = TimerTestEnv.STUDENT_ID,
    parentAccountId: Long = TimerTestEnv.PARENT_ID,
    content: String = "作业$id",
    status: HomeworkStatus = HomeworkStatus.PENDING,
    priority: Int = TimerTestEnv.PRIORITY,
    createdAtMillis: Long = TimerTestEnv.FIXED_MILLIS,
    startTimeMillis: Long? = null,
    estimatedMinutes: Int? = null,
    deadlineMillis: Long? = null,
    type: HomeworkType = HomeworkType.TODAY,
    stageRange: StageRange? = null,
    stageStartEpochDay: Long = 0L,
    stageDailyTime: LocalTime? = null,
): HomeworkItem = HomeworkItem(
    id = id,
    parentAccountId = parentAccountId,
    studentId = studentId,
    content = content,
    type = type,
    stageRange = stageRange,
    deadline = if (type == HomeworkType.STAGE && stageDailyTime != null) {
        HomeworkDailyDeadlineCodec.encodeStageDaily(stageStartEpochDay, stageDailyTime)
    } else {
        deadlineMillis?.let { Instant.ofEpochMilli(it) }
    },
    priority = priority,
    startTime = startTimeMillis?.let { Instant.ofEpochMilli(it) },
    estimatedMinutes = estimatedMinutes,
    status = status,
    createdByRole = CreatorRole.PARENT,
    createdAt = Instant.ofEpochMilli(createdAtMillis),
)
