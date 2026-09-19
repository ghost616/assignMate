package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.homework.domain.HomeworkDayState
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.StageDayRecords
import com.assignmate.app.homework.domain.StageProgressReport
import com.assignmate.app.homework.domain.HomeworkValidators
import java.time.ZoneId

/**
 * 清单页的**角色口径**与分组（纯函数，无 IO、无时钟依赖，可独立单测）。
 *
 * 学生：只看**当天**——当天作业 + 今天落在阶段范围内的阶段作业；
 *       阶段开始前不显示、阶段结束后移出学生当天清单。
 * 家长：看**全部**——已完成的用折叠分组归入「已完成历史」；
 *       阶段已结束但仍有未完成天的归「已结束」并标注未完成天数。
 *
 * 「已完成」判定统一由**作业每天详情**推导（阶段作业：全部覆盖日完成；当天作业：状态为已完成）。
 */
object HomeworkListRoleScope {

    // ---- 角色口径 ----

    /**
     * 某学生作业清单在 [todayEpochDay] 对 [role] 的可见范围（学生只看当天、家长看全部）。
     *
     * 会话缺失（role = null）时返回空表（页面另有「会话失效」整页提示）。
     * 可见性判定只依赖「阶段覆盖区间 + 作业创建日」，不需要每天详情，
     * 故不接收详情访问器（此前传入的 recordsOf 是未被使用的死参数，本轮清理）。
     */
    fun visibleForDay(
        items: List<HomeworkItem>,
        role: Role?,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): List<HomeworkItem> = when (role) {
        Role.PARENT -> items
        Role.STUDENT -> items.filter { item -> isVisibleForStudent(item, todayEpochDay, zoneId) }
        null -> emptyList()
    }

    /**
     * 学生当天是否可见：阶段作业要求今天落在阶段覆盖范围内（阶段开始前不显示、阶段结束后移出）；
     * 当天作业仅在其归属日（创建日，业务时区口径）出现，与仓库写每天详情的口径一致。
     *
     * 阶段可见性统一委托 [StageDayRecords.isVisibleOnDay]（起始日经编码还原、失败回退创建日），
     * 时区由调用方注入，避免 systemDefault 造成业务口径漂移。
     */
    private fun isVisibleForStudent(
        item: HomeworkItem,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): Boolean = if (item.isStage) {
        StageDayRecords.isVisibleOnDay(item, todayEpochDay, zoneId)
    } else {
        item.createdEpochDay(zoneId) == todayEpochDay
    }

    // ---- 分组 ----

    /**
     * 家长视角的三段分组：
     * - [HomeworkListGroups.main]：进行区（当天/阶段进行中、未完成、未结束）；
     * - [HomeworkListGroups.completedHistory]：已完成（全部天完成）→ 页面折叠入「已完成历史」；
     * - [HomeworkListGroups.ended]：阶段已结束但仍有未完成天 → 「已结束」分组（标注未完成天数）。
     *
     * 学生视角不使用分组（只看当天，全部落在 main）。
     *
     * @param zoneId 业务时区：透传给阶段进度推导，保证「阶段起始日无法从编码还原时」的回退
     * （历史载体/脏值 → 创建日）也走注入时区。**刻意不带默认值**——默认 `systemDefault()`
     * 会让「覆写 core 唯一业务时区绑定」对本页失效，形成第二个口径入口。
     */
    fun group(
        items: List<HomeworkItem>,
        recordsOf: (Long) -> List<HomeworkDailyRecord>,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): HomeworkListGroups {
        val main = mutableListOf<HomeworkItem>()
        val completed = mutableListOf<HomeworkItem>()
        val ended = mutableListOf<HomeworkItem>()
        val missedCounts = mutableMapOf<Long, Int>()
        items.forEach { item ->
            val records = recordsOf(item.id)
            val progress = StageDayRecords.progressOf(item, records, todayEpochDay, zoneId)
            if (progress != null) {
                missedCounts[item.id] = progress.missedDays
            }
            when {
                isFullyCompleted(item, records, todayEpochDay, zoneId) -> completed += item
                progress != null && progress.isEndedWithMissedDays -> ended += item
                else -> main += item
            }
        }
        return HomeworkListGroups(
            main = main,
            completedHistory = completed,
            ended = ended,
            endedMissedDayCounts = missedCounts,
        )
    }

    /**
     * 是否「全部天完成」：
     * - 阶段作业：阶段进度覆盖日全部完成（每天详情口径，分母 M = [HomeworkItem.stageCoveredDays]）；
     * - 当天作业：作业状态为 [HomeworkStatus.COMPLETED]。
     *
     * @param zoneId 业务时区（阶段起始日回退口径，透传给 [StageDayRecords.progressOf]；
     *   刻意不带默认值，避免第二个时区口径入口）
     */
    fun isFullyCompleted(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): Boolean = if (item.isStage) {
        StageDayRecords.progressOf(item, records, todayEpochDay, zoneId)?.isAllCompleted == true
    } else {
        item.status == HomeworkStatus.COMPLETED
    }

    /**
     * 阶段作业的未完成天数（缺卡天数）；非阶段作业返回 0。
     *
     * @param zoneId 业务时区（阶段起始日回退口径，透传给 [StageDayRecords.progressOf]；
     *   刻意不带默认值，避免第二个时区口径入口）
     */
    fun missedDayCount(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): Int = StageDayRecords.progressOf(item, records, todayEpochDay, zoneId)?.missedDays ?: 0

    // ---- 行状态投影 ----

    /**
     * 清单行的「今日状态」与阶段进度（均由作业每天详情推导）：
     * - 阶段作业：今日状态取今天在阶段内的状态投影（已完成/未完成/待完成/未开始），
     *   并给出阶段进度（已打卡 N/M 天）与「今天是否还能完成」；
     * - 当天作业：今日状态由作业状态列映射，无阶段进度。
     */
    fun rowTimeline(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): HomeworkRowTimeline {
        if (!item.isStage) {
            return HomeworkRowTimeline(
                todayState = item.status.toDayState(),
                stageProgress = null,
                isTodayActionable = item.status == HomeworkStatus.PENDING ||
                    item.status == HomeworkStatus.IN_PROGRESS,
                dailyDeadlineText = null,
                coverageText = null,
            )
        }
        val outcome = StageDayRecords.todayOutcome(item, records, todayEpochDay, zoneId)
        val progress = StageDayRecords.progressOf(item, records, todayEpochDay, zoneId)
        return HomeworkRowTimeline(
            todayState = outcome?.state,
            stageProgress = progress,
            isTodayActionable = StageDayRecords.isTodayActionable(item, records, todayEpochDay, zoneId),
            dailyDeadlineText = StageDayRecords.dailyDeadlineText(item),
            coverageText = item.stageStartEpochDayOr(zoneId)?.let { start ->
                StageDayRecords.coverageText(start, item.stageCoveredDays)
            },
            dailyDeadlineAtMillis = StageDayRecords.dailyDeadlineOf(item, todayEpochDay, zoneId)
                ?.toEpochMilli(),
        )
    }
}

/** 家长视角分组结果；[endedMissedDayCounts] 供「已结束」分组标注未完成天数（键 = 作业 id） */
data class HomeworkListGroups(
    val main: List<HomeworkItem>,
    val completedHistory: List<HomeworkItem>,
    val ended: List<HomeworkItem>,
    val endedMissedDayCounts: Map<Long, Int> = emptyMap(),
)

/**
 * 清单行的每日维度信息（「今日状态」+ 阶段进度文案）。
 *
 * @param todayState 今日状态（当天作业由状态列映射；阶段作业由每天详情推导；无会话/非阶段缺数据时为 null）
 * @param stageProgress 阶段进度报告（非阶段作业为 null）
 * @param isTodayActionable 今天是否仍可完成（阶段作业：今天在阶段内且未完成未缺卡）
 * @param dailyDeadlineText 阶段作业的每日截止时刻文案（如「每天 21:00 截止」）
 * @param coverageText 阶段覆盖区间文案
 * @param dailyDeadlineAtMillis 当天到点时刻（阶段作业，仅用于展示提示）
 */
data class HomeworkRowTimeline(
    val todayState: HomeworkDayState?,
    val stageProgress: StageProgressReport?,
    val isTodayActionable: Boolean,
    val dailyDeadlineText: String? = null,
    val coverageText: String? = null,
    val dailyDeadlineAtMillis: Long? = null,
) {

    /** 阶段进度文案（非阶段作业为 null） */
    val progressText: String? get() = stageProgress?.progressText

    /** 今日状态文案（如「今日：已完成」） */
    val todayStateText: String? get() = todayState?.let { state -> "今日：${state.label}" }
}

/** 作业状态列 → 今日状态（当天作业口径；阶段作业不用本映射） */
internal fun HomeworkStatus.toDayState(): HomeworkDayState = when (this) {
    HomeworkStatus.COMPLETED -> HomeworkDayState.COMPLETED
    HomeworkStatus.IN_PROGRESS -> HomeworkDayState.PENDING
    HomeworkStatus.PENDING -> HomeworkDayState.PENDING
    HomeworkStatus.RECORDED -> HomeworkDayState.NOT_ARRIVED
}
