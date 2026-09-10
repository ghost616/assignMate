package com.assignmate.app.homework.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 录入模板：描述「这次要录入什么作业」，由 [HomeworkValidators.validateTemplate] 校验后
 * 经 [toItems] 展开为一条或多条 [HomeworkItem] 落库。
 *
 * 展开规则：
 * - 当天作业（[type] = TODAY）：固定落在 [startEpochDay] 当天，产出 1 条作业项；
 * - 阶段作业（[type] = STAGE）：必须带 [stageRange]，自 [startEpochDay] 起逐日展开
 *   [StageRange.days] 天，每天产出 1 条作业项（逐日可独立完成，互不影响状态）。
 *
 * 「归属日」取舍（有意为之，避免死代码）：homework_item 表没有「归属日」列（本计划不引入 schema 变更），
 * 因此逐日展开的作业项**不持久化日期**，先后顺序仅由 priority 表达；
 * 相应地按日定位的辅助（HomeworkScheduleDay、HomeworkValidators.startOfDayMillis）已删除。
 * 若后续需要按日定位（按日提醒、按日统计），需先在 core 侧为 homework_item 增加 day 列并配套迁移，
 * 再在此处回填归属日。
 *
 * 约束（见 [HomeworkValidators]）：
 * - 阶段作业必须选阶段范围；家长录入（[creatorRole] = PARENT）的阶段作业必须设 [deadline]；
 * - 家长录入的作业必须填内容（学生录入允许留空，由学生在完成时自行填写）。
 */
data class HomeworkTemplate(
    val content: String,
    val type: HomeworkType,
    val stageRange: StageRange? = null,
    val deadline: Instant? = null,
    val creatorRole: CreatorRole,
    val startEpochDay: Long,
    val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    /** 起始日 */
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startEpochDay)

    /** 展开覆盖的日子（epochDay 升序）：当天作业 1 天，阶段作业按范围逐日 */
    fun scheduleDays(): List<Long> {
        val days = if (type == HomeworkType.STAGE) {
            stageRange?.days ?: 1
        } else {
            1
        }
        return (0 until days).map { offset -> startEpochDay + offset }
    }

    /** 阶段作业覆盖的最后一天（当天作业即 [startEpochDay] 本身） */
    fun lastEpochDay(): Long = scheduleDays().last()

    /** 结束日（便于 UI 展示「覆盖至 X 月 X 日」） */
    val endDate: LocalDate get() = LocalDate.ofEpochDay(lastEpochDay())

    /** 阶段覆盖的最后一天是否不晚于 deadline 所在日（无 deadline 视为满足） */
    fun fitsWithinDeadline(): Boolean {
        val end = deadline ?: return true
        val deadlineEpochDay = end.atZone(zoneId).toLocalDate().toEpochDay()
        return lastEpochDay() <= deadlineEpochDay
    }

    /**
     * 展开为落库作业项集合（逐日升序，状态均为 [HomeworkStatus.INITIAL]）。
     *
     * @param parentAccountId 归属家长账号 id（冗余维度，来自会话）
     * @param studentId 归属学生 id（来自会话或家长选中的学生）
     * @param createdAt 创建时刻（来自可注入时钟）
     * @param firstPriority 首条作业项优先级，其后逐条累加 [HomeworkConstants.PRIORITY_STEP]
     */
    fun toItems(
        parentAccountId: Long,
        studentId: Long,
        createdAt: Instant,
        firstPriority: Int,
    ): List<HomeworkItem> =
        scheduleDays().mapIndexed { index, day ->
            HomeworkItem(
                id = NEW_ITEM_ID,
                parentAccountId = parentAccountId,
                studentId = studentId,
                content = content.trim(),
                type = type,
                stageRange = if (type == HomeworkType.STAGE) stageRange else null,
                deadline = deadline,
                priority = firstPriority + index * HomeworkConstants.PRIORITY_STEP,
                startTime = null,
                estimatedMinutes = null,
                status = HomeworkStatus.INITIAL,
                createdByRole = creatorRole,
                createdAt = createdAt,
            )
        }

    companion object {

        /** 尚未落库的作业项占位主键（真实 id 由 Room 自增分配），与 [HomeworkConstants.INVALID_ID] 同源 */
        const val NEW_ITEM_ID = HomeworkConstants.INVALID_ID
    }
}