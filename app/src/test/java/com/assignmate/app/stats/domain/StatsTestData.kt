package com.assignmate.app.stats.domain

import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
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
 * stats 领域测试公共夹具：固定业务时区 + 构造作业项与**作业每天详情**的工厂函数。
 *
 * 全部为纯数据构造（不接数据库、不涉及协程），与 [StatsCalculations] 的纯函数口径一致：
 * 日期一律用业务自然日（epochDay）显式传入，时刻用 epoch 毫秒显式传入，避免测试依赖真实时钟。
 *
 * 注意（阶段作业「一条作业项 + 每天详情」口径）：作业项只提供内容/类型/优先级/预估，
 * 「某一天做没做、用了多久」一律由每天详情（[dayRecord]）表达——测试数据也照此构造。
 */
internal object StatsTestData {

    /** 业务时区（与生产 homework 模块绑定口径一致，测试固定为上海） */
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2023-11-14 00:00:00 +08:00 的 epoch 毫秒（用于构造详情里的时刻字段） */
    const val DAY_20231114_START = 1_699_891_200_000L

    /** 2023-11-14 的业务自然日（纪元日） */
    const val DAY_20231114_EPOCH = 19_675L

    /** 一分钟（毫秒） */
    const val MINUTE = 60_000L

    /** 2023-11-14 当日第 [minutesIntoDay] 分钟对应的时刻（构造 startedAt/finishedAt 用） */
    fun at(minutesIntoDay: Long): Long = DAY_20231114_START + minutesIntoDay * MINUTE

    /** 指定自然日的纪元日（相对 2023-11-14 偏移，便于写「前一天/后一天」） */
    fun day(offsetDays: Long): Long = DAY_20231114_EPOCH + offsetDays

    /** 构造当天作业项（统计口径不看作业项状态，默认给「已记录」） */
    fun homework(
        id: Long,
        content: String = "作业$id",
        status: HomeworkStatus = HomeworkStatus.RECORDED,
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

    /** 构造阶段作业项（一条作业项覆盖整段；起始日与每日截止时刻编码在 deadline 里，与生产一致） */
    fun stageHomework(
        id: Long,
        content: String = "阶段作业$id",
        estimatedMinutes: Int? = 30,
        priority: Int = 100,
        studentId: Long = 2L,
        stageRange: StageRange = StageRange.ONE_WEEK,
        startEpochDay: Long = DAY_20231114_EPOCH,
        createdAtMillis: Long = DAY_20231114_START,
    ): HomeworkItem = homework(
        id = id,
        content = content,
        estimatedMinutes = estimatedMinutes,
        priority = priority,
        studentId = studentId,
        createdAtMillis = createdAtMillis,
    ).copy(
        type = HomeworkType.STAGE,
        stageRange = stageRange,
        deadline = HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, LocalTime.of(21, 0)),
    )

    /**
     * 构造「阶段起始日无法从 deadline 编码还原」的阶段作业（历史脏值：deadline 为空）：
     * 起始日只能按调用方注入的**业务时区**回退到「作业创建日」（[HomeworkItem.stageStartEpochDayOr]），
     * 是验证「业务时区透传」是否真的生效的专用夹具（换时区即换覆盖区间）。
     */
    fun stageHomeworkWithFallbackStart(
        id: Long,
        content: String = "阶段作业$id",
        stageRange: StageRange = StageRange.ONE_WEEK,
        createdAtMillis: Long = DAY_20231114_START,
    ): HomeworkItem = stageHomework(
        id = id,
        content = content,
        stageRange = stageRange,
        createdAtMillis = createdAtMillis,
    ).copy(deadline = null)

    /** 构造某作业某自然日的每天详情（统计的唯一时间维度） */
    fun dayRecord(
        homeworkId: Long,
        epochDay: Long = DAY_20231114_EPOCH,
        studentId: Long = 2L,
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
        createdAtMillis = DAY_20231114_START,
    )
}
