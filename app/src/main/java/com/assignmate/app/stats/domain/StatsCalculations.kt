package com.assignmate.app.stats.domain

import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 统计纯函数集合（无副作用、无 IO、不读时钟、不访问数据库，时间口径一律由调用方传入），
 * 集中可单测。
 *
 * 时间口径约定（全模块统一）：
 * - 所有入参时刻均为 epoch 毫秒（[java.time.Instant.toEpochMilli]）；
 * - 「当日」为业务时区自然日，由 [dayStartMillis] 依据 epochDay + 时区偏移换算区间 `[start, end)`，
 *   通过 [windowOf] 归一到 0 起点，便于把「跨天会话」裁剪到当日；
 * - 参考时刻 [referenceMillis] 表示「现在」：未结束的会话与暂停按它折算，已完成会话按结束时刻固定。
 *
 * 主要职责：
 * 1. 完成率与计数（[completionRate] / [completedCount] / [isCompleted]）；
 * 2. 暂停汇总（[pauseAccumulatedMillis] / [pauseCountOf] / [longestPauseOf] / [mostPausedHomeworkOf]）；
 * 3. 单项执行详情（[itemDetail]）与困难度评估接入（[DifficultyAssessor]）；
 * 4. 当日盘点（[summarizeDay]）与历史查询（[summarizeRange]）；
 * 5. 展示格式化（[durationText] / [minutesText] / [percentText]）。
 */
object StatsCalculations {

    // ---- 1. 完成率与计数 ----

    /** 完成率（0..1）：已完成 / 总数；总数为 0 时返回 0（不抛异常、不产生 NaN） */
    fun completionRate(completed: Int, total: Int): Double =
        if (total <= 0) 0.0 else completed.coerceAtLeast(0).toDouble() / total.toDouble()

    /** 已完成作业数量 */
    fun completedCount(items: List<HomeworkItem>): Int = items.count { isCompleted(it.status) }

    /** 作业是否已完成 */
    fun isCompleted(status: HomeworkStatus): Boolean = status == HomeworkStatus.COMPLETED

    // ---- 2. 暂停汇总 ----

    /**
     * 暂停时长合计（毫秒）：已结束的暂停按「结束 - 开始」累加；
     * 未结束的暂停按「参考时刻 - 开始」折算，故暂停期间该值随时间增长。
     *
     * 容错（与 timer 的计时口径一致，避免脏数据污染统计）：
     * - 结束时刻晚于参考时刻（如暂停明细跨越了会话结束时刻）按参考时刻截断；
     * - 开始时刻晚于结束时刻的异常记录按 0 计（不产生负数）。
     */
    fun pauseAccumulatedMillis(pauses: List<PauseFact>, referenceMillis: Long): Long =
        pauses.sumOf { pause ->
            val start = pause.pauseStartAtMillis
            val end = (pause.pauseEndAtMillis ?: referenceMillis).coerceAtMost(referenceMillis)
            (end - start).coerceAtLeast(0L)
        }

    /**
     * 暂停次数合计：按**去重后的暂停段**计（同一段暂停在多次合并/重复查询后只算一次；
     * 未结束的那一段同样计入，与 timer 计时页「暂停中已显示 1 次」口径一致）。
     *
     * 全模块唯一的「暂停次数」语义：当日盘点的窗口裁剪计数（[summarizeDay]）与单项详情
     * （[itemDetail]）都收敛到本函数/本语义，避免同模块内出现多套口径。
     */
    fun pauseCountOf(pauses: List<PauseFact>): Int =
        pauses.distinctBy { it.pauseStartAtMillis to it.pauseEndAtMillis }.size

    /**
     * 暂停最久的作业：取各作业当日累计暂停时长最大者；无暂停（或时长均为 0）返回 null。
     * 出现并列为最大时取**优先级靠前**（更紧急）的那一项，保证盘点结果稳定可复现。
     *
     * @param pausedByHomework 各作业的**当日范围内**暂停汇总（见 [summarizeDay]，已按窗口裁剪）
     */
    fun mostPausedHomeworkOf(
        items: List<HomeworkItem>,
        pausedByHomework: Map<Long, PausedAccum>,
    ): PausedHomework? {
        // 先建 id -> priority 映射：避免在比较器里对 items 做线性查找（最坏 O(n²)），
        // 同时消除「依赖 id 唯一、重复 id 会抛异常」的隐性前提。
        val priorityById = items.associate { it.id to it.priority }
        return items
            .mapNotNull { item ->
                val accumulated = pausedByHomework[item.id] ?: return@mapNotNull null
                if (accumulated.pausedMillis <= 0L || accumulated.count <= 0) {
                    return@mapNotNull null
                }
                PausedHomework(
                    homeworkId = item.id,
                    content = item.content,
                    pausedMillis = accumulated.pausedMillis,
                    pauseCount = accumulated.count,
                )
            }
            .minWithOrNull(
                compareBy<PausedHomework> { -it.pausedMillis }
                    .thenBy { priorityById[it.homeworkId] ?: Int.MAX_VALUE }
                    .thenBy { it.homeworkId },
            )
    }

    // ---- 3. 单项详情与困难度 ----

    /**
     * 单项作业详情：预估/实际/暂停时长 + 执行会话数 + 困难度侧面评估。
     *
     * 实际耗时口径：**全部历史执行会话**的已用时长合计（各会话按自身结束时刻或 [referenceMillis] 折算，
     * 已扣除暂停，负数收敛为 0）——同一作业多次开始计时会累加，符合「这项作业一共花了多久」的直觉。
     */
    fun itemDetail(
        item: HomeworkItem,
        sessions: List<TimerSessionFacts>,
        referenceMillis: Long,
    ): ItemDetail {
        val own = sessions.filter { it.homeworkId == item.id }
        val elapsed = own.sumOf { sessionElapsedMillis(it, referenceMillis, clipStart = null, clipEnd = null) }
        val pauses = own.flatMap { it.pauses }
        val pausedTotal = own.sumOf { pauseAccumulatedMillis(it.pauses, referenceOf(it, referenceMillis)) }
        // 暂停次数统一走 pauseCountOf 的去重语义，避免与当日盘点（PausedAccum.count）出现口径分歧
        val pauseCount = pauseCountOf(pauses)
        val sessionCount = own.size
        val level = DifficultyAssessor.assess(
            status = item.status,
            estimatedMinutes = item.estimatedMinutes,
            elapsedMillis = elapsed,
            pauseCount = pauseCount,
            sessionCount = sessionCount,
        )
        return ItemDetail(
            homeworkId = item.id,
            content = item.content,
            studentId = item.studentId,
            status = item.status,
            estimatedMinutes = item.estimatedMinutes,
            elapsedMillis = elapsed,
            pausedTotalMillis = pausedTotal,
            pauseCount = pauseCount,
            sessionCount = sessionCount,
            difficulty = level,
            assessmentHint = DifficultyAssessor.hintOf(
                level = level,
                estimatedMinutes = item.estimatedMinutes,
                elapsedMillis = elapsed,
                pauseCount = pauseCount,
                sessionCount = sessionCount,
            ),
        )
    }

    // ---- 4. 当日盘点 / 历史查询 ----

    /**
     * 当日盘点（某一天的核心输出）。
     *
     * **纳入口径（完成率分母）**：某作业纳入当日盘点的条件为三者之一——
     * 1. 该作业当日产生了执行时长（会话时间段与当日窗口 `[dayStart, dayEnd)` 相交且裁剪后净耗时 > 0，
     *    含「前一日开始、跨零点仍在进行」的会话，按当日窗口裁剪计时）；
     * 2. 该作业当日有执行会话仍在进行中（净耗时可能尚为 0，如「刚点开始」的作业，
     *    但只要会话时间落在当日且未结束，就算当天动过，避免刚开工的作业从盘点里消失）；
     * 3. 该作业的完成时刻落在当日（已完成会话的结束时刻在当日窗口内）——
     *    覆盖「不经过计时直接在清单里标记完成」的情形，避免当日完成数被漏计。
     *
     * 分母 = 满足上述条件的作业数；分子 = 其中状态为已完成的作业数。
     * 因此「今天没有执行也没有完成的作业」不会进入分母，完成率只反映当天真正动过的作业。
     *
     * 暂停口径：只统计**落在当日窗口内**的暂停段（跨天暂停按窗口裁剪），
     * 因此同一天的暂停次数/时长不会因为会话跨天而重复计入相邻两天。
     *
     * @param epochDay 目标自然日（UTC 纪元日）
     * @param dayStartMillis 当日业务时区 00:00 对应的 epoch 毫秒
     * @param dayEndMillis 次日 00:00 对应的 epoch 毫秒（开区间上界）
     * @param referenceMillis 参考时刻（「现在」）；早于当日结束时按当日窗口收敛
     */
    fun summarizeDay(
        epochDay: Long,
        items: List<HomeworkItem>,
        sessions: List<TimerSessionFacts>,
        dayStartMillis: Long,
        dayEndMillis: Long,
        referenceMillis: Long,
    ): DaySummary {
        val window = windowOf(dayStartMillis, dayEndMillis)
        val reference = referenceMillis.coerceIn(window.start, window.end)
        val pausedByHomework = linkedMapOf<Long, PausedAccum>()
        val dayElapsedByHomework = linkedMapOf<Long, Long>()

        sessions.forEach { session ->
            val elapsed = sessionElapsedMillis(session, reference, window.start, window.end)
            val clipped = clippedPauses(session.pauses, reference, window)
            if (elapsed <= 0L && clipped.isEmpty()) {
                return@forEach
            }
            val homeworkId = session.homeworkId
            dayElapsedByHomework[homeworkId] = (dayElapsedByHomework[homeworkId] ?: 0L) + elapsed
            pausedByHomework[homeworkId] = (pausedByHomework[homeworkId] ?: PausedAccum.EMPTY) + clipped
        }

        val daily = items.filter { item ->
            (dayElapsedByHomework[item.id] ?: 0L) > 0L ||
                activeInWindow(item.id, sessions, window) ||
                completedInWindow(item, sessions, window)
        }
        val ordered = daily.sortedWith(compareBy<HomeworkItem> { it.priority }.thenBy { it.createdAt })

        return DaySummary(
            epochDay = epochDay,
            totalCount = ordered.size,
            completedCount = completedCount(ordered),
            items = ordered,
            pauseCount = pausedByHomework.values.sumOf { it.count },
            pausedTotalMillis = pausedByHomework.values.sumOf { it.pausedMillis },
            mostPausedItem = mostPausedHomeworkOf(ordered, pausedByHomework),
        )
    }

    /**
     * 历史查询（日期范围）：返回范围内**逐日**盘点，按日期倒序（最近的一天在前）。
     * 无任何作业进入盘点口径的日子不会出现在结果中（页面据此展示空态而非空白行）。
     *
     * 逐日结果同源于 [summarizeDay]，因此单日查询与范围查询对同一天的口径完全一致。
     *
     * @param fromEpochDay 起始日（含）
     * @param toEpochDay 结束日（含）；早于起始日时返回空列表
     * @param dayStartMillisOf 由 epochDay 换算当日 00:00 的毫秒值（时区口径由调用方注入）
     */
    fun summarizeRange(
        fromEpochDay: Long,
        toEpochDay: Long,
        items: List<HomeworkItem>,
        sessions: List<TimerSessionFacts>,
        referenceMillis: Long,
        dayStartMillisOf: (Long) -> Long,
    ): List<DaySummary> {
        if (toEpochDay < fromEpochDay) {
            return emptyList()
        }
        return (fromEpochDay..toEpochDay)
            .map { epochDay ->
                val start = dayStartMillisOf(epochDay)
                val end = dayStartMillisOf(epochDay + 1L)
                summarizeDay(
                    epochDay = epochDay,
                    items = items,
                    sessions = sessions,
                    dayStartMillis = start,
                    dayEndMillis = end,
                    referenceMillis = referenceMillis,
                )
            }
            .filter { !it.isEmpty }
            .sortedByDescending { it.epochDay }
    }

    /** 范围合计：把逐日盘点按日期升序汇总为一个总计（历史页「合计」一栏使用） */
    fun totalOf(summaries: List<DaySummary>): DaySummary {
        val ordered = summaries.sortedBy { it.epochDay }
        return DaySummary(
            epochDay = ordered.lastOrNull()?.epochDay ?: 0L,
            totalCount = ordered.sumOf { it.totalCount },
            completedCount = ordered.sumOf { it.completedCount },
            items = ordered.flatMap { it.items },
            pauseCount = ordered.sumOf { it.pauseCount },
            pausedTotalMillis = ordered.sumOf { it.pausedTotalMillis },
            mostPausedItem = ordered
                .mapNotNull { it.mostPausedItem }
                .maxWithOrNull(compareBy<PausedHomework> { it.pausedMillis }.thenByDescending { it.homeworkId }),
        )
    }

    // ---- 5. 时间与展示 ----

    /** 业务时区自然日 00:00 的 epoch 毫秒（epochDay + 时区偏移；纯函数，可在 UTC 下直接断言） */
    fun dayStartMillis(epochDay: Long, zoneOffsetMillis: Int): Long =
        epochDay * MILLIS_PER_DAY - zoneOffsetMillis

    /** 某个时刻（epoch 毫秒）在给定时区下所属的自然日（UTC 纪元日） */
    fun epochDayOf(millis: Long, zoneOffsetMillis: Int): Long =
        Math.floorDiv(millis + zoneOffsetMillis, MILLIS_PER_DAY)

    /**
     * 某自然日 00:00 的 epoch 毫秒（业务时区口径）。
     *
     * **全模块唯一的「某日窗口起点」换算入口**（仓库与各 ViewModel 都必须走本函数）：
     * 时区偏移取该日**正午**（`date 12:00`）的偏移，规避夏令时切换日 00:00 时刻的偏移歧义
     * （切换日零点前后的偏移可能不同，取正午可稳定表达「这一天」）。
     */
    fun dayStartMillisOf(zoneId: ZoneId, epochDay: Long): Long {
        val date = LocalDate.ofEpochDay(epochDay)
        val offsetMillis = zoneId.rules.getOffset(date.atTime(12, 0)).totalSeconds * MILLIS_PER_SECOND
        return dayStartMillis(epochDay, offsetMillis)
    }

    /** 某时刻（epoch 毫秒）在业务时区下所属的自然日（UTC 纪元日） */
    fun epochDayOfToday(zoneId: ZoneId, nowMillis: Long): Long {
        val offsetMillis = zoneId.rules.getOffset(Instant.ofEpochMilli(nowMillis)).totalSeconds *
            MILLIS_PER_SECOND
        return epochDayOf(nowMillis, offsetMillis)
    }

    /** 某自然日在给定时区下的展示文案（`yyyy-MM-dd`） */
    fun dateText(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    /** 时长展示：`xx 分钟`；不足一分钟返回「不到 1 分钟」；达到 1 小时补充小时数 */
    fun durationText(millis: Long): String {
        val safe = millis.coerceAtLeast(0L)
        if (safe < StatsConstants.DISPLAY_MINUTE_MIN_MILLIS) {
            return "不到 1 分钟"
        }
        val minutes = minutesOf(safe)
        return if (minutes < StatsConstants.MINUTES_PER_HOUR) {
            "$minutes 分钟"
        } else {
            val hours = minutes / StatsConstants.MINUTES_PER_HOUR
            val rest = minutes % StatsConstants.MINUTES_PER_HOUR
            if (rest == 0L) "$hours 小时" else "$hours 小时 $rest 分钟"
        }
    }

    /** 毫秒 -> 整分钟（向下取整，负数收敛为 0） */
    fun minutesOf(millis: Long): Long = millis.coerceAtLeast(0L) / StatsConstants.MILLIS_PER_MINUTE

    /** 分钟数展示：`xx 分钟`；不足一分钟返回「不到 1 分钟」 */
    fun minutesText(minutes: Long): String = durationText(minutes * StatsConstants.MILLIS_PER_MINUTE)

    /** 完成率百分比文案（保留 1 位小数，如 `87.5%`；无作业时输出 `0.0%`） */
    fun percentText(rate: Double): String =
        String.format(Locale.ROOT, "%.${StatsConstants.PERCENT_SCALE}f%%", rate.coerceIn(0.0, 1.0) * 100.0)

    // ---- 私有工具 ----

    /**
     * 会话已用时长（毫秒），可裁剪到当日窗口：
     * `min(结束, clipEnd) - max(开始, clipStart) - 落在该窗口内的暂停时长`。
     *
     * 未传窗口（均为 null）表示「整段会话」：暂停按会话自身参考时刻（结束时刻或现在）折算。
     */
    private fun sessionElapsedMillis(
        session: TimerSessionFacts,
        referenceMillis: Long,
        clipStart: Long?,
        clipEnd: Long?,
    ): Long {
        val start = maxOf(session.startedAtMillis, clipStart ?: Long.MIN_VALUE)
        val end = minOf(session.finishedAtMillis ?: referenceMillis, clipEnd ?: Long.MAX_VALUE)
        if (end <= start) {
            return 0L
        }
        // 暂停一律先在会话自身时刻轴上折算，再按同一窗口裁剪：
        // 避免「会话跨天」时把窗口外的暂停时长误算进当天的净耗时（那会让净耗时虚低甚至为 0）。
        val sessionStart = session.startedAtMillis
        val sessionEnd = session.finishedAtMillis ?: referenceMillis
        val paused = if (clipStart == null && clipEnd == null) {
            pauseAccumulatedMillis(session.pauses, referenceOf(session, referenceMillis))
        } else {
            session.pauses
                .map { pause ->
                    val pauseStart = pause.pauseStartAtMillis.coerceIn(sessionStart, sessionEnd)
                    val pauseEnd = (pause.pauseEndAtMillis ?: referenceMillis).coerceIn(sessionStart, sessionEnd)
                    minOf(pauseEnd, end) - maxOf(pauseStart, start)
                }
                .sumOf { it.coerceAtLeast(0L) }
        }
        return (end - start - paused).coerceAtLeast(0L)
    }

    /** 会话的参考时刻：已完成会话取结束时刻（固定），未结束会话取「现在」 */
    private fun referenceOf(session: TimerSessionFacts, referenceMillis: Long): Long =
        session.finishedAtMillis ?: referenceMillis

    /**
     * 落在给定窗口内的暂停段（已按窗口裁剪后的长度）：
     * 未结束的暂停按参考时刻折算，并同样裁剪到窗口内；
     * 与窗口无交集的暂停段直接丢弃（跨天暂停因此不会在相邻两天重复计入）。
     *
     * 说明：全模块「窗口裁剪暂停」只有本实现一处（供 [summarizeDay] 使用），
     * 不做第二套同名口径，以免两套裁剪并存造成误用。
     */
    private fun clippedPauses(
        pauses: List<PauseFact>,
        referenceMillis: Long,
        window: Window,
    ): List<Long> = pauses.mapNotNull { pause ->
        val start = maxOf(pause.pauseStartAtMillis, window.start)
        val end = minOf(pause.pauseEndAtMillis ?: referenceMillis, window.end)
        (end - start).takeIf { it > 0L }
    }

    /**
     * 作业当日是否有仍未结束的执行会话（会话起点落在当日窗口内且未结束）。
     * 用于「刚点开始计时、净耗时尚为 0」的作业：它确实在当天动过，不应被盘点遗漏。
     */
    private fun activeInWindow(
        homeworkId: Long,
        sessions: List<TimerSessionFacts>,
        window: Window,
    ): Boolean = sessions.any { session ->
        session.homeworkId == homeworkId &&
            session.finishedAtMillis == null &&
            session.startedAtMillis in window.start until window.end
    }

    /** 作业的完成时刻是否落在当日窗口内（依据该作业的已完成会话结束时刻） */
    private fun completedInWindow(
        item: HomeworkItem,
        sessions: List<TimerSessionFacts>,
        window: Window,
    ): Boolean {
        if (!isCompleted(item.status)) {
            return false
        }
        return sessions.any { session ->
            session.homeworkId == item.id &&
                session.finishedAtMillis?.let { it >= window.start && it < window.end } == true
        }
    }

    private fun windowOf(startMillis: Long, endMillis: Long): Window = Window(startMillis, endMillis)

    /** 半开时间窗口 `[start, end)` */
    private data class Window(val start: Long, val end: Long)

        private const val MILLIS_PER_DAY = 86_400_000L

    /** 一秒的毫秒数（时区偏移换算用） */
    private const val MILLIS_PER_SECOND = 1_000
}