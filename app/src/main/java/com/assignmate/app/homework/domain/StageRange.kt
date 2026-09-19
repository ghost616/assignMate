package com.assignmate.app.homework.domain

import java.time.Duration
import java.time.LocalDate
import java.time.Period

/**
 * 阶段作业范围：一周 / 两周 / 三周 / 一个月。
 *
 * 仅 [HomeworkType.STAGE] 作业使用；[days] 为「从起始日算起覆盖的自然天数」
 * （一周 7 天、一个月按 [HomeworkConstants.DAYS_PER_MONTH] 折算）。
 *
 * 用途（**不再用于展开条目数**）：阶段作业在清单里始终只产出 **1 条**作业项，
 * [days] 只用于 (1) 计算阶段起止日与「今日是否落在阶段范围内」；(2) 作为阶段进度的分母 M
 * （阶段进度 = 每天详情里已完成的天数 / [days]，见 [StageDayRecords]）。
 *
 * 持久化约定：homework_item.stage_range 列以本枚举 name 字符串存储。
 */
enum class StageRange(val days: Int) {

    /** 一周 */
    ONE_WEEK(7),

    /** 两周 */
    TWO_WEEKS(14),

    /** 三周 */
    THREE_WEEKS(21),

    /** 一个月（按固定天数折算，避免跨月天数歧义） */
    ONE_MONTH(HomeworkConstants.DAYS_PER_MONTH),
    ;

    /** 用户可读的中文标签 */
    val label: String
        get() = when (this) {
            ONE_WEEK -> "一周"
            TWO_WEEKS -> "两周"
            THREE_WEEKS -> "三周"
            ONE_MONTH -> "一个月"
        }

    /** 覆盖周期（便于按天/按周推进） */
    val period: Period get() = Period.ofDays(days)

    /** 覆盖时长（便于计算时间区间） */
    val duration: Duration get() = Duration.ofDays(days.toLong())

    /** 从 [startEpochDay]（含当天）起覆盖的最后一个日子（epochDay），即阶段的结束日 */
    fun lastEpochDay(startEpochDay: Long): Long =
        LocalDate.ofEpochDay(startEpochDay).plusDays(days - 1L).toEpochDay()

    /** 从 [startEpochDay]（含当天）起覆盖的全部自然日（epochDay 升序，长度 = [days]） */
    fun coveredEpochDays(startEpochDay: Long): List<Long> =
        (0 until days.toLong()).map { offset -> startEpochDay + offset }

    /** [epochDay] 是否落在自 [startEpochDay] 起的覆盖区间内（含首尾） */
    fun covers(startEpochDay: Long, epochDay: Long): Boolean =
        epochDay >= startEpochDay && epochDay <= lastEpochDay(startEpochDay)

    companion object {

        /** 字符串安全解析（null/未知取值均返回 null） */
        fun fromName(name: String?): StageRange? =
            name?.let { raw -> entries.firstOrNull { it.name == raw } }
    }
}