package com.assignmate.app.homework.domain

import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 单个自然日的状态投影（清单行「今日状态」与阶段进度文案的唯一来源）。
 *
 * - [NOT_ARRIVED] 未到：该天尚未到来（阶段范围内的将来日）；
 * - [PENDING] 待完成：该天是当天或将来日，尚未完成，**仍可完成**；
 * - [COMPLETED] 已完成：该天已有完成记录；
 * - [MISSED] 未完成（缺卡）：该天在阶段范围内**已过去**且未完成，**不可补做**。
 */
enum class HomeworkDayState {

    /** 尚未到来的阶段内日子 */
    NOT_ARRIVED,

    /** 待完成（当天或将来日，仍可完成） */
    PENDING,

    /** 已完成 */
    COMPLETED,

    /** 未完成（缺卡，且不可补做） */
    MISSED,
    ;

    /** 是否落在「已结束且未完成」的缺卡态 */
    val isMissed: Boolean get() = this == MISSED

    /** 是否仍可完成（当天到点后仍可完成，只有跨入次日才判为未完成） */
    val isActionable: Boolean get() = this == PENDING

    /** 用户可读文案 */
    val label: String
        get() = when (this) {
            NOT_ARRIVED -> "未开始"
            PENDING -> "待完成"
            COMPLETED -> "已完成"
            MISSED -> "未完成"
        }
}

/**
 * 某一天的状态判定结果（清单行与阶段进度共用）。
 *
 * @param epochDay 该天的业务自然日
 * @param state 该天的状态投影
 * @param isToday 是否为「当天」
 */
data class HomeworkDayOutcome(
    val epochDay: Long,
    val state: HomeworkDayState,
    val isToday: Boolean,
)

/**
 * 阶段进度报告：由「作业每天详情」推导（分母 M = [StageRange.days]）。
 *
 * @param coveredDays 阶段覆盖天数（分母 M）
 * @param elapsedDays 阶段内已过去的天数（含当天）
 * @param completedDays 已打卡天数（分母内的完成记录）
 * @param missedDays 缺卡天数（阶段内已过去且未完成）
 * @param remainingDays 阶段内尚未到来的天数
 * @param isEnded 阶段是否已整体结束（最后一天已过去）
 */
data class StageProgressReport(
    val coveredDays: Int,
    val elapsedDays: Int,
    val completedDays: Int,
    val missedDays: Int,
    val remainingDays: Int,
    val isEnded: Boolean,
) {

    /** 进度文案：「已打卡 N/M 天」 */
    val progressText: String get() = "阶段进度：已打卡 $completedDays/$coveredDays 天"

    /** 是否全部覆盖日都已完成（可作为「已完成」折叠分组的判定条件之一） */
    val isAllCompleted: Boolean get() = coveredDays > 0 && completedDays >= coveredDays

    /** 阶段已结束但仍有未完成的天 */
    val isEndedWithMissedDays: Boolean get() = isEnded && missedDays > 0
}

/**
 * 「作业每天详情」的业务推导（纯函数，无 IO、无时钟依赖，可独立单测）。
 *
 * 缺卡口径（需求）：
 * - 阶段范围内**已过去**且未完成的天 → 记为 [HomeworkDayState.MISSED]（未完成）且**不可补做**；
 * - **当天**即便已过每日截止时刻仍可完成（[HomeworkDayState.PENDING]），**跨入次日**才判为未完成。
 *
 * 进度口径：分母 M = 阶段覆盖天数（[StageRange.days]），分子 = 每天详情中已完成的天数。
 *
 * 业务时区（`zoneId`）是**必填参数、刻意不带默认值**：本对象的各推导入口都要用业务时区把
 * 「阶段起始日 / 归属日」折算成自然日，若给 `ZoneId.systemDefault()` 默认值，它就会成为
 * 「静默第二时区入口」——覆写 core 的唯一业务时区绑定后，凡漏传的调用点都会静默按系统时区
 * 回退。去掉默认值后漏传变成**编译错误**，调用方只能显式传入注入的业务时区
 * （homework 侧由 DI / 构造注入提供，本模块不得自行取 `systemDefault()`）。
 */
object StageDayRecords {

    /**
     * 判定 [epochDay] 在某阶段作业下的状态。
     *
     * @param startEpochDay 阶段起始日（[HomeworkItem.stageStartEpochDay]）
     * @param coveredDays 阶段覆盖天数（分母 M）
     * @param todayEpochDay 业务自然日口径的「今天」
     * @param isCompleted 该天是否已有完成记录（来自每天详情）
     */
    fun stateOf(
        epochDay: Long,
        startEpochDay: Long,
        coveredDays: Int,
        todayEpochDay: Long,
        isCompleted: Boolean,
    ): HomeworkDayState = when {
        isCompleted -> HomeworkDayState.COMPLETED
        !isWithinCoverage(epochDay, startEpochDay, coveredDays) -> HomeworkDayState.NOT_ARRIVED
        epochDay < todayEpochDay -> HomeworkDayState.MISSED
        else -> HomeworkDayState.PENDING
    }

    /** [epochDay] 是否落在阶段覆盖区间内（含首尾） */
    fun isWithinCoverage(epochDay: Long, startEpochDay: Long, coveredDays: Int): Boolean =
        coveredDays > 0 && epochDay >= startEpochDay && epochDay <= startEpochDay + coveredDays - 1L

    /**
     * 阶段作业在 [epochDay] 的状态投影：缺卡只对「已过去」的天成立，
     * 当天到点后仍返回 [HomeworkDayState.PENDING]。
     *
     * @param records 该作业的全部每天详情（可含阶段范围外的历史记录，内部按覆盖区间过滤）
     * @param zoneId 业务时区（**必填**）：仅在阶段起始日无法从编码还原时用于回退到创建日；
     *   调用方必须显式传入注入的业务时区，不得依赖 `systemDefault()`
     */
    fun outcomeFor(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        epochDay: Long,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): HomeworkDayOutcome? {
        val start = item.stageStartEpochDayOr(zoneId) ?: return null
        val covered = item.stageCoveredDays
        if (covered <= 0) {
            return null
        }
        val state = stateOf(
            epochDay = epochDay,
            startEpochDay = start,
            coveredDays = covered,
            todayEpochDay = todayEpochDay,
            isCompleted = records.isCompleted(epochDay),
        )
        return HomeworkDayOutcome(epochDay = epochDay, state = state, isToday = epochDay == todayEpochDay)
    }

    /**
     * 阶段当天详情状态（清单行「今日状态」）：阶段作业取今天在阶段内的状态投影，
     * 非阶段作业返回 null（用作业自身状态列表达，避免两套口径）。
     */
    fun todayOutcome(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): HomeworkDayOutcome? = if (item.isStage) {
        outcomeFor(item, records, epochDay = todayEpochDay, todayEpochDay = todayEpochDay, zoneId = zoneId)
    } else {
        null
    }

    /** 阶段进度报告（分母 M = 覆盖天数；非阶段作业返回 null） */
    fun progressOf(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): StageProgressReport? {
        val start = item.stageStartEpochDayOr(zoneId) ?: return null
        val covered = item.stageCoveredDays
        if (covered <= 0) {
            return null
        }
        val coveredDays = (0 until covered.toLong()).map { offset -> start + offset }
        val completed = coveredDays.count { day -> records.isCompleted(day) }
        val elapsed = coveredDays.count { day -> day <= todayEpochDay }
        val missed = coveredDays.count { day -> day < todayEpochDay && !records.isCompleted(day) }
        val remaining = coveredDays.count { day -> day > todayEpochDay }
        val isEnded = todayEpochDay > coveredDays.last()
        return StageProgressReport(
            coveredDays = covered,
            elapsedDays = elapsed,
            completedDays = completed,
            missedDays = missed,
            remainingDays = remaining,
            isEnded = isEnded,
        )
    }

    /**
     * 阶段作业「今天是否还能完成」：必须在阶段覆盖范围内，且该天既未完成也未缺卡。
     * 已过去且未完成的天**不可补做**（[HomeworkDayState.MISSED]）。
     */
    fun isTodayActionable(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): Boolean = todayOutcome(item, records, todayEpochDay, zoneId)?.state?.isActionable ?: false

    /**
     * 阶段开始前不显示给学生；阶段结束后移出学生当天清单。
     * 家长侧不做此过滤（家长看全部 + 分组）。
     *
     * 起始日经 [HomeworkItem.stageStartEpochDayOr] 解析：编码可还原时用编码值，
     * 否则回退到业务时区口径的创建日（不依赖 systemDefault）。
     */
    fun isVisibleOnDay(
        item: HomeworkItem,
        epochDay: Long,
        zoneId: ZoneId,
    ): Boolean {
        if (!item.isStage) {
            return true
        }
        val start = item.stageStartEpochDayOr(zoneId) ?: return true
        return isWithinCoverage(epochDay, start, item.stageCoveredDays)
    }

    /**
     * 阶段作业是否在 [epochDay] 已完成（唯一口径：每天详情里 status = COMPLETED）。
     * 非阶段作业返回 false（由作业状态列表达）。
     */
    fun isCompletedOn(item: HomeworkItem, records: List<HomeworkDailyRecord>, epochDay: Long): Boolean =
        item.isStage && records.isCompleted(epochDay)

    /**
     * 阶段作业某天的**绝对截止瞬时**（当天到点后仍可完成，仅用于展示提示）。
     *
     * 这是阶段作业「绝对截止时刻」取数的**唯一底层入口**：homework 的
     * [HomeworkItem.absoluteDeadlineAt] 与计时侧 `TimerCalculations.absoluteDeadlineMillisOf`
     * 都委托此处，避免同一列被两处各自解释。
     *
     * 缺每日截止时刻（学生录入的阶段作业可留空）或脏值 → 返回 null，语义为
     * **该天不产生截止约束**（计时不判超时、校验直接放行，两侧结论一致）；
     * 不得再用「当天 23:59:59.999」之类的非空兜底（那会形成第二个口径）。
     */
    fun dailyDeadlineOf(item: HomeworkItem, epochDay: Long, zoneId: ZoneId): Instant? {
        val time = item.dailyDeadlineTime ?: return null
        return HomeworkDailyDeadlineCodec.instantAt(epochDay, time, zoneId)
    }

    /** 阶段每日截止时刻文案（如「每天 21:00 截止」） */
    fun dailyDeadlineText(item: HomeworkItem): String? =
        item.dailyDeadlineTime?.let { time -> "每天 ${formatTimeOfDay(time)} 截止" }

    /** 阶段覆盖区间文案（如「覆盖：2026-09-10 至 2026-09-16（每天到点截止）」） */
    fun coverageText(startEpochDay: Long, coveredDays: Int): String {
        val start = LocalDate.ofEpochDay(startEpochDay)
        val end = LocalDate.ofEpochDay(startEpochDay + (coveredDays - 1L).coerceAtLeast(0L))
        return "覆盖：$start 至 $end（每天到点截止）"
    }

    /** 每日时刻展示（HH:mm） */
    fun formatTimeOfDay(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)
}

/** 该天是否已有完成记录（唯一口径：每天详情里 status = COMPLETED） */
internal fun List<HomeworkDailyRecord>.isCompleted(epochDay: Long): Boolean =
    any { record -> record.epochDay == epochDay && record.status == HomeworkDayStatus.COMPLETED }
