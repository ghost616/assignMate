package com.assignmate.app.stats.domain

import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import java.time.Instant
import java.time.ZoneId

/**
 * stats 领域测试公共夹具：固定业务时区 + 构造作业/会话/暂停事实的工厂函数。
 *
 * 全部为纯数据构造（不接数据库、不涉及协程），与 [StatsCalculations] 的纯函数口径一致：
 * 时刻一律用 epoch 毫秒显式传入，避免测试依赖真实时钟。
 */
internal object StatsTestData {

    /** 业务时区（与生产 homework 模块绑定口径一致，测试固定为上海） */
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2023-11-14 00:00:00 +08:00 的 epoch 毫秒（当日窗口起点） */
    const val DAY_20231114_START = 1_699_891_200_000L

    /** 2023-11-14 的 UTC 纪元日 */
    const val DAY_20231114_EPOCH = 19_675L

    /** 一分钟（毫秒） */
    const val MINUTE = 60_000L

    /** 2023-11-14 当日 00:09:00 +08:00（窗口内时刻，便于构造会话时刻） */
    fun at(minutesIntoDay: Long): Long = DAY_20231114_START + minutesIntoDay * MINUTE

    /** 构造作业项 */
    fun homework(
        id: Long,
        content: String = "作业$id",
        status: HomeworkStatus = HomeworkStatus.PENDING,
        estimatedMinutes: Int? = 30,
        priority: Int = 100,
        studentId: Long = 2L,
        createdAtMillis: Long = DAY_20231114_START,
    ): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = 1L,
        studentId = studentId,
        content = content,
        type = HomeworkType.TODAY,
        stageRange = null,
        deadline = null,
        priority = priority,
        startTime = Instant.ofEpochMilli(createdAtMillis),
        estimatedMinutes = estimatedMinutes,
        status = status,
        createdByRole = CreatorRole.PARENT,
        createdAt = Instant.ofEpochMilli(createdAtMillis),
    )

    /** 构造会话事实（含若干暂停段） */
    fun session(
        sessionId: Long,
        homeworkId: Long,
        startedAtMillis: Long,
        finishedAtMillis: Long? = null,
        pauses: List<PauseFact> = emptyList(),
        studentId: Long = 2L,
    ): TimerSessionFacts = TimerSessionFacts(
        sessionId = sessionId,
        homeworkId = homeworkId,
        studentId = studentId,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        pauses = pauses,
    )

    /** 构造已结束的暂停段 */
    fun pause(startMillis: Long, endMillis: Long): PauseFact =
        PauseFact(pauseStartAtMillis = startMillis, pauseEndAtMillis = endMillis)

    /** 构造未结束的暂停段（暂停中） */
    fun ongoingPause(startMillis: Long): PauseFact =
        PauseFact(pauseStartAtMillis = startMillis, pauseEndAtMillis = null)
}