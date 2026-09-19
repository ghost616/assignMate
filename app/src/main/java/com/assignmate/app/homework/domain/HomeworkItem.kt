package com.assignmate.app.homework.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 作业项领域模型（data 层与 core 的 homework_item 表互转）。
 *
 * 时间语义（两类截止时间刻意不同，编码见 [HomeworkDailyDeadlineCodec]）：
 * - 当天作业（[HomeworkType.TODAY]）：[deadline] 是**绝对时刻**（日期 + 时刻），语义不变；
 * - 阶段作业（[HomeworkType.STAGE]）：[deadline] 只承载**每日截止时刻**（time-of-day），
 *   语义为「阶段范围内每天到这个时刻截止」；[stageStartEpochDay] 与 [stageLastEpochDay] 给出该阶段的覆盖日；
 * - [startTime] 开始时间与 [estimatedMinutes] 预估时长共同构成「已排定时间段」
 *   [startTime, startTime + estimatedMinutes]，用于时间段防冲突校验；
 * - [estimatedFinishedAt] 为推导值，便于展示与冲突排查，不落库。
 *
 * 权限语义：[createdByRole] 记录录入者角色，配合当前会话角色由
 * [HomeworkValidators.canModify] / [HomeworkValidators.canDelete] 判定可操作性。
 */
data class HomeworkItem(
    val id: Long,
    val parentAccountId: Long,
    val studentId: Long,
    val content: String,
    val type: HomeworkType,
    val stageRange: StageRange?,
    val deadline: Instant?,
    val priority: Int,
    val startTime: Instant?,
    val estimatedMinutes: Int?,
    val status: HomeworkStatus,
    val createdByRole: CreatorRole,
    val createdAt: Instant,
) {

    /** 已排定的预计完成时刻（未排定开始时间时为 null） */
    val estimatedFinishedAt: Instant?
        get() = startTime?.let { start ->
            estimatedMinutes?.let { minutes -> start.plus(Duration.ofMinutes(minutes.toLong())) }
        }

    /** 是否为阶段作业 */
    val isStage: Boolean get() = type == HomeworkType.STAGE

    /**
     * 每日截止时刻：阶段作业取 [deadline] 解码出的 time-of-day；
     * 当天作业返回 null（其 [deadline] 是绝对时刻，没有「每日时刻」语义）。
     */
    val dailyDeadlineTime: LocalTime?
        get() = if (isStage) {
            deadline?.toEpochMilli()?.let(HomeworkDailyDeadlineCodec::decodeStageDaily)
        } else {
            null
        }

    /**
     * 阶段覆盖的起始日（epochDay）：阶段作业自 [deadline] 编码中还原（与写入时一致，
     * 用户改过阶段范围也会整体平移），当天作业返回 null。
     *
     * 兜底说明：入参 deadline 可能是尚未编码的「每日时刻载体」（其时刻落在纪元日 0）或历史脏值，
     * 此时返回 null，由调用方经 [stageStartEpochDayOr] 传入业务时区回退到「作业创建日」——
     * 本属性**刻意不依赖 [ZoneId.systemDefault]**（避免业务时区口径漂移）。
     */
    val stageStartEpochDay: Long?
        get() = if (isStage) {
            deadline?.toEpochMilli()
                ?.let(HomeworkDailyDeadlineCodec::stageStartEpochDay)
                ?.takeIf { it >= 0L }
        } else {
            null
        }

    /**
     * 阶段覆盖的起始日（带回退）：编码可还原时用编码值，否则回退到**作业创建日**
     * （业务上「阶段自创建日起算」，与仓库写每天详情的口径一致），时区由调用方注入。
     */
    fun stageStartEpochDayOr(zoneId: ZoneId): Long? =
        if (isStage) stageStartEpochDay ?: createdEpochDay(zoneId) else null

    /**
     * 该作业在某一天（[epochDay]，业务时区口径）的**绝对截止时刻**；该天不产生截止约束时返回 null。
     *
     * 类型层面统一两种语义，避免各消费方各自解释同一列：
     * - 当天作业（TODAY）：[deadline] 本身即日期 + 时刻的绝对时刻，**任何一天都取该绝对时刻**；
     * - 阶段作业（STAGE）：[deadline] 只承载每日时刻，故取「[epochDay] 当天 + 每日时刻」的绝对时刻，
     *   并**委托**阶段侧取数的唯一底层入口 [StageDayRecords.dailyDeadlineOf]。
     *
     * **「未设每日截止时刻」的统一口径 = 该天不产生约束（返回 null）**，全链一致：
     * - 校验侧：[HomeworkValidators.validateScheduleWithinItemDeadline] 遇空每日时刻直接放行
     *   （学生录入的阶段作业可留空，故允许「23:00 开始 600 分钟」这类跨日排定）；
     * - 取数侧：本方法与计时侧 `TimerCalculations.absoluteDeadlineMillisOf` 都经
     *   [StageDayRecords.dailyDeadlineOf] 取阶段作业的当日截止瞬时，缺每日时刻即 null，
     *   因此计时不会「次日 00:00 起即判超时」，与校验侧的放行结论一致（不再一边放一边罚）。
     * 历史上的 `END_OF_DAY`（把缺省折算为当天 23:59:59.999）已删除：它虽声称「无额外约束」，
     * 但取数结果非空，仍是与计时侧不同的第二个口径。
     *
     * 分工说明：**校验**一律走 [HomeworkValidators.validateScheduleWithinItemDeadline]；
     * 本方法与计时侧的取数方法只负责「该天的绝对截止瞬时是多少」，不承担合法性判定。
     *
     * @param zoneId 业务时区（自然日与钟面值折算的唯一口径，禁止 UTC 毫秒折算）
     */
    fun absoluteDeadlineAt(epochDay: Long, zoneId: ZoneId): Instant? =
        if (isStage) {
            StageDayRecords.dailyDeadlineOf(this, epochDay, zoneId)
        } else {
            deadline
        }

    /**
     * 阶段覆盖的最后一天（epochDay）= 起始日 + [StageRange.days] - 1（进度分母 M 的来源），
     * 非阶段作业或脏数据返回 null。
     */
    val stageLastEpochDay: Long?
        get() = stageRange?.let { range ->
            stageStartEpochDay?.let { start -> start + range.days - 1L }
        }

    /**
     * 阶段覆盖天数（进度分母 M）：阶段作业为 [StageRange.days]，非阶段作业为 0。
     * 注意这是「阶段整体分母」，与「已过去的天数」不同（后者见 [StageDayRecords]）。
     */
    val stageCoveredDays: Int
        get() = if (isStage) stageRange?.days ?: 0 else 0

    /** 是否已排定开始时间（清单据此区分「待排定/已排定」） */
    val isScheduled: Boolean get() = startTime != null

    /** 是否处于进行中（timer 后续使用） */
    val isInProgress: Boolean get() = status == HomeworkStatus.IN_PROGRESS

    /** 作业创建日（epochDay，业务时区口径）：阶段起始日兜底与「当天作业归属日」的唯一口径 */
    fun createdEpochDay(zoneId: ZoneId): Long =
        HomeworkValidators.epochDayOf(createdAt.toEpochMilli(), zoneId)
}