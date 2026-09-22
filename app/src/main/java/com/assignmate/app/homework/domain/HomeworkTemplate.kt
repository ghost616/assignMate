package com.assignmate.app.homework.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 录入模板：描述「这次要录入什么作业」，由 [HomeworkValidators.validateTemplate] 校验后
 * 经 [toItems] 落库为**恰好 1 条** [HomeworkItem]（阶段作业同样只产出 1 条，见下）。
 *
 * 展开规则：
 * - 当天作业（[type] = TODAY）：落在 [startEpochDay] 当天，产出 1 条作业项；
 * - 阶段作业（[type] = STAGE）：必须带 [stageRange]，**同样只产出 1 条**作业项——
 *   阶段作业是「一个在阶段内每天都要完成的作业项」，不是「每天各一条作业」；
 *   [stageRange] 只用于计算阶段起止日（[coveredEpochDays]/[lastEpochDay]）与阶段进度的分母 M。
 *
 * 截止时间语义（两类刻意不同）：
 * - TODAY：[deadline] 为绝对时刻（日期 + 时刻），可空；
 * - STAGE：[deadline] **只填时刻**（time-of-day），语义为「阶段范围内每天到这个时刻截止」；
 *   落库时经 [HomeworkDailyDeadlineCodec] 把「阶段起始日 + 每日时刻」编码进 deadline 列，
 *   使其可唯一还原且不依赖创建时刻反推。
 *
 * 约束（见 [HomeworkValidators]）：
 * - 阶段作业必须选阶段范围；家长录入（[creatorRole] = PARENT）的阶段作业必须设每日截止时刻；
 * - 家长录入的作业必须填内容（学生录入允许留空，由学生在完成时自行填写）。
 *
 * 时区口径：[zoneId] 是**业务时区**，由调用方传入 core 提供的唯一业务时区绑定
 * （生产侧注入，测试侧显式传入），本类**不提供 `ZoneId.systemDefault()` 默认值**——
 * 否则覆写 core 绑定后，模板侧仍会以系统时区兜底，形成第二个口径入口。
 */
data class HomeworkTemplate(
    val content: String,
    val type: HomeworkType,
    val stageRange: StageRange? = null,
    val deadline: Instant? = null,
    val creatorRole: CreatorRole,
    val startEpochDay: Long,
    val zoneId: ZoneId,
) {

    /** 起始日（阶段作业为阶段首日） */
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startEpochDay)

    /**
     * 阶段覆盖的全部自然日（epochDay 升序）：当天作业 1 天，阶段作业 [StageRange.days] 天。
     *
     * 注意：这是**覆盖日集合**（用于起止日、进度分母与每日详情对齐），
     * 不是「要落库的条目数」——[toItems] 永远只产出 1 条。
     */
    fun coveredEpochDays(): List<Long> = if (type == HomeworkType.STAGE) {
        stageRange?.coveredEpochDays(startEpochDay) ?: listOf(startEpochDay)
    } else {
        listOf(startEpochDay)
    }

    /** 阶段覆盖的最后一天（当天作业即 [startEpochDay] 本身） */
    fun lastEpochDay(): Long =
        if (type == HomeworkType.STAGE) {
            stageRange?.lastEpochDay(startEpochDay) ?: startEpochDay
        } else {
            startEpochDay
        }

    /** 结束日（便于 UI 展示「覆盖至 X 月 X 日」） */
    val endDate: LocalDate get() = LocalDate.ofEpochDay(lastEpochDay())

    /** 阶段覆盖天数（进度分母 M）：阶段作业为 [StageRange.days]，当天作业为 1 */
    val coveredDays: Int get() = coveredEpochDays().size

    /** 阶段作业的每日截止时刻（[deadline] 解码；当天作业或脏值返回 null） */
    val dailyDeadlineTime: LocalTime?
        get() = if (type == HomeworkType.STAGE) {
            deadline?.toEpochMilli()?.let(HomeworkDailyDeadlineCodec::decodeStageDaily)
        } else {
            null
        }

    /**
     * 落库 deadline 取值：
     * - TODAY：原样（绝对时刻语义不变）；
     * - STAGE：由「阶段起始日 + 每日时刻」编码而来（入参 [deadline] 承载的即每日时刻）。
     */
    private fun persistedDeadline(): Instant? = when (type) {
        HomeworkType.TODAY -> deadline
        HomeworkType.STAGE -> deadline?.let { daily ->
            // 阶段每日时刻是**钟面值**（time-of-day），与业务时区无关：
            // 入参由 [HomeworkDailyDeadlineCodec.timeOfDayCarrier] 以 UTC 锚定日承载，
            // 故此处同样按 UTC 取出钟面值，避免经业务时区往返时发生 ±8 小时偏移。
            // 取钟面值必须走 Instant.atZone（API 26 起可用），**不得**用 LocalTime.ofInstant
            // （Java 9 / Android API 31）：本工程 minSdk 29 且未启用 core library desugaring，
            // API<31 设备会抛 NoSuchMethodError——荣耀 V10（Android 10）上「保存阶段作业」
            // 必现闪退的根因即此。二者语义逐字等价（同为零偏移下的钟面值，含纳秒精度）。
            val time = daily.atZone(ZoneOffset.UTC).toLocalTime()
            HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, time)
        }
    }

    /**
     * 展开为落库作业项（**固定 1 条**，状态为 [HomeworkStatus.INITIAL]，未排定时间）。
     *
     * @param parentAccountId 归属家长账号 id（冗余维度，来自会话）
     * @param studentId 归属学生 id（来自会话或家长选中的学生）
     * @param createdAt 创建时刻（来自可注入时钟）
     * @param firstPriority 作业项优先级（后续新增项按 [HomeworkConstants.PRIORITY_STEP] 追加到末尾）
     */
    fun toItems(
        parentAccountId: Long,
        studentId: Long,
        createdAt: Instant,
        firstPriority: Int,
    ): List<HomeworkItem> = listOf(
        HomeworkItem(
            id = NEW_ITEM_ID,
            parentAccountId = parentAccountId,
            studentId = studentId,
            content = content.trim(),
            type = type,
            stageRange = if (type == HomeworkType.STAGE) stageRange else null,
            deadline = persistedDeadline(),
            priority = firstPriority,
            startTime = null,
            estimatedMinutes = null,
            status = HomeworkStatus.INITIAL,
            createdByRole = creatorRole,
            createdAt = createdAt,
        ),
    )

    companion object {

        /** 尚未落库的作业项占位主键（真实 id 由 Room 自增分配），与 [HomeworkConstants.INVALID_ID] 同源 */
        const val NEW_ITEM_ID = HomeworkConstants.INVALID_ID
    }
}
