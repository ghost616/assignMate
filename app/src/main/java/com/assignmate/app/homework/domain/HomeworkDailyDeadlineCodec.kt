package com.assignmate.app.homework.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 两类截止时间的统一编解码：把「当天作业的绝对时刻」与「阶段作业的每日时刻」表达为同一个可持久化字段。
 *
 * 语义（与需求一致）：
 * - 当天作业（[HomeworkType.TODAY]）：deadline 是**绝对时刻**（日期 + 时刻），语义不变；
 * - 阶段作业（[HomeworkType.STAGE]）：deadline **只填时刻**（time-of-day，如 21:00），
 *   语义为「阶段范围内每天到这个时刻截止」，**不再有日期含义**。
 *
 * 持久化约束：homework_item 只有 deadline 一个 Instant 列（core 持有表结构，本模块不改 schema），
 * 因此阶段作业的「每日时刻」需要把「阶段起始日」与「当日时刻」一起编码进该列，才能：
 * 1) 唯一、可逆地还原成 [HomeworkDeadline.StageDaily]（不依赖创建时刻反推，避免用户改过阶段范围后错位）；
 * 2) 把日期维度一并固定下来（阶段范围的「覆盖日」= 起始日 + [StageRange.days]，用户后来改范围即可整体平移，
 *    覆盖日与每日时刻始终保持一致）；
 * 3) 与当天作业的绝对 deadline（当代毫秒量级）在数值上天然可分。
 *
 * 编码：raw = ([STAGE_ANCHOR_EPOCH_DAY] + 起始日 epochDay) * [MILLIS_PER_DAY] + 当日时刻毫秒（自当日 00:00 起）。
 * 锚点取 1970-01-02 而非 1970-01-01：真实作业的起始日不早于当下（远大于 0），加 1 后 raw 必为正数，
 * 既便于解读也避免出现负 Instant 这类不适合落库的取值。
 *
 * 时区口径：当天作业按 [ZoneId] 把「日期 + 时刻」折算为瞬时；阶段作业只关心「当日时刻」这一钟面值，
 * 编码/解码均不涉及时区，故不存在 UTC 折算偏差。
 */
object HomeworkDailyDeadlineCodec {

    /** 一日的毫秒数（仅用于每日时刻的编码/解码，不用于任何日期折算） */
    const val MILLIS_PER_DAY = 86_400_000L

    /** 一毫秒的纳秒数（LocalTime 只提供纳秒精度，编码时折算为毫秒） */
    private const val NANOS_PER_MILLI = 1_000_000L

    /** 阶段编码的锚点日（见类注释：保证真实数据的编码结果为正） */
    const val STAGE_ANCHOR_EPOCH_DAY = 1L

    /** 阶段作业未填时刻时的兜底每日截止时刻（21:00，与录入页快捷项一致） */
    val DEFAULT_DEADLINE_TIME: LocalTime = LocalTime.of(21, 0)

    /**
     * 编码阶段作业的「每日截止时刻」为可持久化的 [Instant]。
     *
     * @param startEpochDay 阶段起始日（业务时区口径的自然日，= 作业创建日）
     * @param timeOfDay 每日截止时刻
     */
    fun encodeStageDaily(startEpochDay: Long, timeOfDay: LocalTime): Instant =
        Instant.ofEpochMilli(
            (STAGE_ANCHOR_EPOCH_DAY + startEpochDay) * MILLIS_PER_DAY +
                timeOfDay.toNanoOfDay() / NANOS_PER_MILLI,
        )

    /**
     * 解码任意 deadline 为领域语义；取值不可识别（空、脏值、越界）时返回 null。
     *
     * @param type 作业类型：TODAY 解为绝对时刻，STAGE 解为每日时刻
     */
    fun decode(type: HomeworkType, deadlineMillis: Long?): HomeworkDeadline? {
        if (deadlineMillis == null) {
            return null
        }
        return when (type) {
            HomeworkType.TODAY -> HomeworkDeadline.Absolute(Instant.ofEpochMilli(deadlineMillis))
            HomeworkType.STAGE -> decodeStageDaily(deadlineMillis)?.let(HomeworkDeadline::StageDaily)
        }
    }

    /**
     * 仅解码阶段作业的「每日截止时刻」（作业所属的当天 / 阶段范围由 [stageStartEpochDay] 单独还原）。
     *
     * 越界值（>= 24h）与负值一律返回 null，避免脏数据构造出非法 LocalTime 而崩溃。
     */
    fun decodeStageDaily(deadlineMillis: Long): LocalTime? {
        val timeOfDayMillis = deadlineMillis % MILLIS_PER_DAY
        if (timeOfDayMillis < 0L || timeOfDayMillis >= MILLIS_PER_DAY) {
            return null
        }
        return runCatching { LocalTime.ofNanoOfDay(timeOfDayMillis * 1_000_000L) }.getOrNull()
    }

    /**
     * 还原阶段作业的「起始日」（业务时区口径的自然日），与 [encodeStageDaily] 互逆。
     * 返回值小于 0 说明该 deadline 不是本编解码产出的阶段取值（脏值），由调用方兜底处理。
     */
    fun stageStartEpochDay(deadlineMillis: Long): Long =
        deadlineMillis.floorDiv(MILLIS_PER_DAY) - STAGE_ANCHOR_EPOCH_DAY

    /** 当天作业的 deadline 折算为业务自然日（epochDay），用于「当天作业只在归属日展示」等口径 */
    fun absoluteEpochDay(deadlineMillis: Long, zoneId: ZoneId): Long =
        LocalDate.ofInstant(Instant.ofEpochMilli(deadlineMillis), zoneId).toEpochDay()

    /** 某自然日 + 当日时刻的业务瞬时（阶段作业「该天到点」的判定基准） */
    fun instantAt(epochDay: Long, timeOfDay: LocalTime, zoneId: ZoneId): Instant =
        LocalDate.ofEpochDay(epochDay).atTime(timeOfDay).atZone(zoneId).toInstant()

    /** 每日时刻文案（HH:mm），供清单行与表单回显共用（文案单源） */
    fun formatTime(timeOfDay: LocalTime): String =
        "%02d:%02d".format(timeOfDay.hour, timeOfDay.minute)

    /**
     * 把「每日时刻」承载进一个不依赖业务日期的瞬时，供 [HomeworkTemplate] 的 deadline 入参使用
     * （[HomeworkTemplate.toItems] 会按业务时区还原为钟面值并编码阶段起始日）。
     *
     * 锚点取 1970-01-01（UTC 零点）而非「今天」：结果与业务时区无关，也不会因跨日产生漂移。
     */
    fun timeOfDayCarrier(timeOfDay: LocalTime): Instant =
        LocalDate.ofEpochDay(0L).atTime(timeOfDay).atZone(ZoneId.of("UTC")).toInstant()
}

/**
 * 截止时间的领域语义（[HomeworkDailyDeadlineCodec] 的产物）：
 * - [Absolute]：当天作业的绝对截止时刻（日期 + 时刻）；
 * - [StageDaily]：阶段作业的每日截止时刻（只有时刻，语义为「每天到这个时刻截止」）。
 */
sealed interface HomeworkDeadline {

    /** 当天作业的绝对截止时刻 */
    data class Absolute(val instant: Instant) : HomeworkDeadline

    /** 阶段作业的每日截止时刻 */
    data class StageDaily(val timeOfDay: LocalTime) : HomeworkDeadline

    companion object {

        /** 阶段作业的每日时刻 -> 可持久化取值（需带上阶段起始日才能唯一还原） */
        fun stageDaily(startEpochDay: Long, timeOfDay: LocalTime): Absolute =
            Absolute(HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, timeOfDay))
    }
}
