package com.assignmate.app.stats.domain

import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.HomeworkDayState
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageDayRecords
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 统计纯函数集合（无副作用、无 IO、不读时钟、不访问数据库，数据与日期一律由调用方传入），
 * 集中可单测。
 *
 * 口径总纲（阶段作业「一条作业项 + 每天详情」改造后）：
 * 1. **当天应做（完成率分母）** = 「当天作业（落在当天）+ 阶段作业（今天落在其阶段范围内）」，
 *    判定由**作业项**推导，并直接复用 homework 领域能力
 *    （[StageDayRecords.isVisibleOnDay] / [HomeworkValidators.epochDayOf]），避免两处各写一套「应做」口径；
 *    **不能**用「当天有没有每天详情」当分母：每天详情是**按需写入**的（开始/完成时才写），
 *    没动过的作业没有详情，用它当分母会让完成率虚高；
 * 1.1 **业务时区一律显式透传**：凡调用 homework 侧按自然日判定的能力（[StageDayRecords.isVisibleOnDay] /
 *    [StageDayRecords.progressOf] 等），都必须把构造注入的业务时区（core 唯一来源）显式传下去，
 *    本模块**不得**出现 `ZoneId.systemDefault()`、也不得依赖对方签名上的任何默认值——
 *    否则覆写 core 的唯一业务时区绑定后，「当天应做」仍按系统时区判定，跨零点时口径漂移；
 * 2. **当天状态与完成数**：阶段作业由每天详情推导（与 homework 清单行的阶段口径同源），
 *    当天作业用作业状态列（homework 清单行对当天作业同样用状态列）；
 * 3. **暂停口径**：暂停次数/时长/暂停最久作业取自当天应做作业在**这一天的每天详情**
 *    （跨天计时按**开始日**归属，由 timer 在写入每天详情时确定，与计时口径一致），不做时段裁剪；
 * 4. **展示**：时长字段以毫秒流转（由详情的分钟值经 [millisOfMinutes] 换算），
 *    预估时长以分钟流转（展示走 [minutesText]，其入参同样是分钟）。
 *
 * 主要职责：
 * 1. 完成率与计数（[completionRate] / [isDayCompleted] / [completedCountOf]）；
 * 2. 当天应做与当天状态（[shouldDoOn] / [dayStatusOf] / [mostPausedHomeworkOf]）；
 * 3. 单项按天详情（[dayDetail]）与阶段打卡进度（[stageProgressOf]）；
 * 4. 当日盘点（[summarizeDay]）与历史查询（[summarizeRange] / [totalOf]）；
 * 5. 时间与展示格式化（[epochDayOfToday] / [dateText] / [durationText] / [minutesText] / [percentText]）。
 */
object StatsCalculations {

    // ---- 1. 完成率与计数 ----

    /** 完成率（0..1）：已完成 / 应做总数；总数为 0 时返回 0（不抛异常、不产生 NaN） */
    fun completionRate(completed: Int, total: Int): Double =
        if (total <= 0) 0.0 else completed.coerceAtLeast(0).toDouble() / total.toDouble()

    /** 某一天的作业是否已完成 */
    fun isDayCompleted(status: HomeworkDayStatus): Boolean = status == HomeworkDayStatus.COMPLETED

    /** 已完成天数（给定详情范围内） */
    fun completedCountOf(records: List<HomeworkDailyRecord>): Int = records.count { isDayCompleted(it.status) }

    // ---- 2. 当天应做与当天状态 ----

    /**
     * 某作业在某自然日**是否应做**（全模块唯一的「当天应做」判定，完成率分母与当天清单共用）：
     * - 阶段作业：该天落在阶段覆盖范围内（[StageDayRecords.isVisibleOnDay]，阶段开始前不显示、结束后移出）；
     * - 当天作业：该天即其**归属日**（创建时刻按业务时区折算的自然日，[HomeworkValidators.epochDayOf]）。
     *
     * 复用 homework 领域函数而不是在此重算覆盖区间，保证与清单页「今日」口径完全一致；
     * [zoneId] 必须显式透传（阶段起始日无法从编码还原时 homework 会按业务时区回退到创建日，
     * 漏传即会让「当天应做」按系统时区判定）。
     *
     * @param zoneId 业务时区（core 唯一来源，构造注入；本模块不得自建、不得依赖 systemDefault）
     */
    fun shouldDoOn(item: HomeworkItem, epochDay: Long, zoneId: ZoneId): Boolean = if (item.isStage) {
        StageDayRecords.isVisibleOnDay(item, epochDay, zoneId)
    } else {
        HomeworkValidators.epochDayOf(item.createdAt.toEpochMilli(), zoneId) == epochDay
    }

    /**
     * 某作业在某自然日的**当天状态**（当日清单与单项详情共用同一口径）：
     * - 阶段作业：**这一天就是今天**且当天详情显示已开工（状态进行中或已有开始时刻）→「进行中」；
     *   否则由 [StageDayRecords.stateOf] 推导——已有完成记录即「已完成」；覆盖日已过去且未完成即
     *   「未完成（缺卡）」；当天到点前 / 尚未到来的日子按「未开始」；
     * - 当天作业：由作业状态列映射（已完成 / 进行中 / 未开始）。
     *
     * 两个判定细节及其原因：
     * 1. 「已开工」要先于 [StageDayRecords.stateOf] 判断：`stateOf` 的入参只有一个 `isCompleted` 布尔，
     *    其 `PENDING` 同时覆盖「今天还没动过」与「今天已开工未完成」，按值域无法区分；若直接压成「未开始」，
     *    就会出现「详情页显示已用 8 分钟、状态却写还没开始」的自相矛盾；
     * 2. 「已开工」只在**当天且仍在阶段覆盖期内**成立：历史日残留的进行中详情（计时中途退出、会话跨天未收敛等）
     *    不能永远显示「进行中」——那天已经过去、也不能再补做；阶段整体结束后「今天」若仍残留进行中详情
     *    同样不该显示进行中（该天已不在阶段范围内）。两种情形都落回 [StageDayRecords.stateOf] 更诚实。
     *
     * @param todayEpochDay 业务自然日的「今天」（阶段作业据它区分「已开工 / 缺卡 / 尚未到来」）
     * @param zoneId 业务时区（**必填**）：阶段起始日无法从 deadline 编码还原时，homework 会按业务时区
     *   回退到「作业创建日」（[HomeworkItem.stageStartEpochDayOr]）——本函数必须复用同一入口，
     *   否则会出现「[shouldDoOn] 按回退后的覆盖区间判为应做、这里却因起始日缺失永远显示未开始」的口径分叉
     */
    fun dayStatusOf(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        epochDay: Long,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): HomeworkDayStatus {
        if (!item.isStage) {
            return when (item.status) {
                HomeworkStatus.COMPLETED -> HomeworkDayStatus.COMPLETED
                HomeworkStatus.IN_PROGRESS -> HomeworkDayStatus.IN_PROGRESS
                HomeworkStatus.PENDING, HomeworkStatus.RECORDED -> HomeworkDayStatus.NOT_STARTED
            }
        }
        val start = item.stageStartEpochDayOr(zoneId) ?: return HomeworkDayStatus.NOT_STARTED
        val completedOnDay = StageDayRecords.isCompletedOn(item, records, epochDay)
        val record = records.firstOrNull { it.epochDay == epochDay }
        val inProgressToday = epochDay == todayEpochDay &&
            StageDayRecords.isWithinCoverage(epochDay, start, item.stageCoveredDays) &&
            hasStartedOn(record)
        if (!completedOnDay && inProgressToday) {
            return HomeworkDayStatus.IN_PROGRESS
        }
        return when (
            StageDayRecords.stateOf(
                epochDay = epochDay,
                startEpochDay = start,
                coveredDays = item.stageCoveredDays,
                todayEpochDay = todayEpochDay,
                isCompleted = completedOnDay,
            )
        ) {
            HomeworkDayState.COMPLETED -> HomeworkDayStatus.COMPLETED
            HomeworkDayState.MISSED -> HomeworkDayStatus.MISSED
            // 「待完成（今天到点前）/ 未到（将来日）」都还没有完成记录，统一按「未开始」呈现
            HomeworkDayState.PENDING, HomeworkDayState.NOT_ARRIVED -> HomeworkDayStatus.NOT_STARTED
        }
    }

    /** 当天详情是否留下了「已开工」痕迹（进行中状态或已有开始时刻；已完成由调用方单独判定） */
    private fun hasStartedOn(record: HomeworkDailyRecord?): Boolean {
        if (record == null) {
            return false
        }
        return record.status == HomeworkDayStatus.IN_PROGRESS || record.startedAtMillis != null
    }

    /**
     * 暂停最久的作业：取当天详情里暂停时长最大者；无暂停（或时长均为 0）返回 null。
     *
     * 并列时取**优先级靠前**（更紧急）的一项，再看作业 id 升序，保证盘点结果稳定可复现。
     *
     * @param items 当天应做的作业清单（已带优先级）
     * @param dayRecords 当天应做作业的当天详情（暂停口径的唯一来源）
     */
    fun mostPausedHomeworkOf(
        items: List<DayItem>,
        dayRecords: List<HomeworkDailyRecord>,
    ): PausedHomework? {
        val recordByHomework = dayRecords.associateBy { it.homeworkId }
        // 先建 id -> priority 映射：避免在比较器里对 items 做线性查找（最坏 O(n²)）
        val priorityById = items.associate { it.homeworkId to it.priority }
        return items
            .mapNotNull { item ->
                val record = recordByHomework[item.homeworkId] ?: return@mapNotNull null
                if (record.pausedTotalMinutes <= 0 || record.pauseCount <= 0) {
                    return@mapNotNull null
                }
                PausedHomework(
                    homeworkId = item.homeworkId,
                    content = item.content,
                    pausedMillis = millisOfMinutes(record.pausedTotalMinutes),
                    pauseCount = record.pauseCount,
                )
            }
            .minWithOrNull(
                compareBy<PausedHomework> { -it.pausedMillis }
                    .thenBy { priorityById[it.homeworkId] ?: Int.MAX_VALUE }
                    .thenBy { it.homeworkId },
            )
    }

    /**
     * 阶段作业打卡进度：**已打卡天数 / 阶段覆盖天数**（分母 M = 阶段范围天数，与 homework 的阶段进度同源：
     * 直接复用 [StageDayRecords.progressOf]，不在此重算覆盖区间）。
     *
     * [zoneId] 必须显式透传：阶段起始日无法从编码还原时，覆盖区间要按业务时区回退到创建日
     * （覆盖区间整体平移会改变分子分母），漏传即按系统时区统计。
     *
     * @param zoneId 业务时区（core 唯一来源，构造注入）
     * @return 非阶段作业、或阶段范围/起始日缺失时返回 null（不臆造进度）
     */
    fun stageProgressOf(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): StageProgress? {
        val report = StageDayRecords.progressOf(item, records, todayEpochDay, zoneId) ?: return null
        return StageProgress(
            homeworkId = item.id,
            content = item.content,
            doneDays = report.completedDays,
            totalDays = report.coveredDays,
        )
    }

    // ---- 3. 单项按天详情 ----

    /**
     * 单项作业详情（**指定某一天**）：预估/实际/暂停时长 + 当天状态 + 执行痕迹 + 困难度侧面评估。
     *
     * 取值规则：
     * - 时长与暂停只取该作业**这一天**的详情（分钟值 -> 毫秒），阶段作业不给整段合计；
     * - 当天状态与当日清单同一口径（[dayStatusOf]），故清单里显示「已完成」的项在详情页同样显示已完成；
     * - 当天没有详情时按「未开始」呈现（时长为 0、[ItemDetail.hasExecution] 为 false），
     *   预估时长回落到作业本身，避免详情页整页空白；
     * - 预估时长优先取当天详情，其次作业本身（当天未单独设定时仍能给出参考值）。
     *
     * @param item 作业项
     * @param records 该作业的**全部**天详情（用于取指定那一天，并计算阶段打卡进度）
     * @param epochDay 要查看的自然日
     * @param todayEpochDay 业务自然日的「今天」（阶段作业缺卡判定需要）
     * @param zoneId 业务时区（core 唯一来源，构造注入）：当天状态与阶段打卡进度都按它判定，不得依赖默认值
     */
    fun dayDetail(
        item: HomeworkItem,
        records: List<HomeworkDailyRecord>,
        epochDay: Long,
        todayEpochDay: Long,
        zoneId: ZoneId,
    ): ItemDetail {
        val record = records.firstOrNull { it.epochDay == epochDay }
        val estimatedMinutes = record?.estimatedMinutes ?: item.estimatedMinutes
        val elapsedMillis = millisOfMinutes(record?.actualMinutes)
        val pausedTotalMillis = millisOfMinutes(record?.pausedTotalMinutes)
        val pauseCount = record?.pauseCount ?: 0
        val dayStatus = dayStatusOf(item, records, epochDay, todayEpochDay, zoneId)
        val hasExecution = hasExecution(record)
        val level = DifficultyAssessor.assess(
            dayStatus = dayStatus,
            estimatedMinutes = estimatedMinutes,
            elapsedMillis = elapsedMillis,
            pauseCount = pauseCount,
            hasExecution = hasExecution,
        )
        return ItemDetail(
            homeworkId = item.id,
            content = item.content,
            studentId = item.studentId,
            epochDay = epochDay,
            isStage = item.isStage,
            dayStatus = dayStatus,
            estimatedMinutes = estimatedMinutes,
            elapsedMillis = elapsedMillis,
            pausedTotalMillis = pausedTotalMillis,
            pauseCount = pauseCount,
            hasExecution = hasExecution,
            difficulty = level,
            assessmentHint = DifficultyAssessor.hintOf(
                level = level,
                estimatedMinutes = estimatedMinutes,
                elapsedMillis = elapsedMillis,
                pauseCount = pauseCount,
            ),
            stageProgress = if (item.isStage) stageProgressOf(item, records, todayEpochDay, zoneId) else null,
        )
    }

    // ---- 4. 当日盘点 / 历史查询 ----

    /**
     * 当日盘点（某一天的核心输出）。
     *
     * **当天应做（完成率分母）**：清单里满足 [shouldDoOn] 的作业数，即
     * 「当天作业（落在当天）+ 阶段作业（今天落在其阶段范围内）」；分子为其中当天状态已完成的数量
     * （阶段作业按每天详情、当天作业按作业状态列，见 [dayStatusOf]）。
     * 未开始 / 尚未到来的作业同样计入分母（「应做」不等于「动过」）。
     *
     * 暂停口径：暂停次数/总时长/暂停最久作业取自这些应做作业**当天详情**，不按窗口裁剪、不跨天重复计入。
     *
     * @param epochDay 目标自然日（业务时区纪元日）
     * @param todayEpochDay 业务自然日的「今天」（用于区分阶段当天的「未完成缺卡」与「尚未到来」）
     * @param items 该学生的作业清单（按优先级升序）
     * @param recordsByHomework 作业 id -> 该作业全部天详情
     * @param zoneId 业务时区（core 唯一来源，构造注入）：既用于当天作业「归属日」折算，也**原样透传**给
     *   homework 侧的自然日判定（[shouldDoOn] / [dayStatusOf] / [stageProgressOf]），四者必须同源同值
     */
    fun summarizeDay(
        epochDay: Long,
        todayEpochDay: Long,
        items: List<HomeworkItem>,
        recordsByHomework: Map<Long, List<HomeworkDailyRecord>>,
        zoneId: ZoneId,
    ): DaySummary {
        val shouldDo = items.filter { item -> shouldDoOn(item, epochDay, zoneId) }
        val recordsOf: (Long) -> List<HomeworkDailyRecord> = { id -> recordsByHomework[id].orEmpty() }
        val dayRecords = shouldDo.mapNotNull { item ->
            recordsOf(item.id).firstOrNull { it.epochDay == epochDay }
        }
        val dayItems = shouldDo
            .map { item ->
                DayItem(
                    homeworkId = item.id,
                    content = item.content,
                    status = dayStatusOf(item, recordsOf(item.id), epochDay, todayEpochDay, zoneId),
                    isStage = item.isStage,
                    priority = item.priority,
                )
            }
            .sortedWith(compareBy<DayItem> { it.priority }.thenBy { it.homeworkId })

        val stages = shouldDo
            .filter { it.isStage }
            .mapNotNull { item -> stageProgressOf(item, recordsOf(item.id), todayEpochDay, zoneId) }

        return DaySummary(
            epochDay = epochDay,
            totalCount = dayItems.size,
            completedCount = dayItems.count { it.isCompleted },
            items = dayItems,
            pauseCount = dayRecords.sumOf { it.pauseCount },
            pausedTotalMillis = dayRecords.sumOf { millisOfMinutes(it.pausedTotalMinutes) },
            mostPausedItem = mostPausedHomeworkOf(dayItems, dayRecords),
            stages = stages,
        )
    }

    /**
     * 历史查询（日期范围）：返回范围内**逐日**盘点，按日期倒序（最近的一天在前）。
     *
     * 无应做作业的日子不会出现在结果中（页面据此展示空态而非空白行）；
     * 阶段作业在其覆盖期内**每一天都有应做项**（即使当天没有执行记录），
     * 因此历史能体现「当天是否完成」与整段「打卡进度」。
     *
     * 逐日结果同源于 [summarizeDay]，故单日查询与范围查询对同一天的口径完全一致。
     *
     * @param fromEpochDay 起始日（含）
     * @param toEpochDay 结束日（含）；早于起始日时返回空列表
     */
    fun summarizeRange(
        fromEpochDay: Long,
        toEpochDay: Long,
        todayEpochDay: Long,
        items: List<HomeworkItem>,
        recordsByHomework: Map<Long, List<HomeworkDailyRecord>>,
        zoneId: ZoneId,
    ): List<DaySummary> {
        if (toEpochDay < fromEpochDay) {
            return emptyList()
        }
        return (fromEpochDay..toEpochDay)
            .map { epochDay ->
                summarizeDay(
                    epochDay = epochDay,
                    todayEpochDay = todayEpochDay,
                    items = items,
                    recordsByHomework = recordsByHomework,
                    zoneId = zoneId,
                )
            }
            .filter { !it.isEmpty }
            .sortedByDescending { it.epochDay }
    }

    /** 范围合计：把逐日盘点汇总为一个总计（历史页「合计」一栏使用） */
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
            // 阶段打卡进度是「某一天」的概念（同一阶段作业在不同天进度不同），不可跨日相加，故合计不携带
            stages = emptyList(),
        )
    }

    // ---- 5. 时间与展示 ----

    /**
     * 某时刻（epoch 毫秒）在业务时区下所属的自然日（业务时区纪元日）。
     *
     * **全模块唯一的「今天」换算入口**（各 ViewModel 与仓库都必须走本函数）：
     * 直接按注入时区把时刻折算为本地日期，避免 `millis / 86_400_000` 的 UTC 折算
     * （UTC+8 的 00:00-07:59 会取到昨天）。
     */
    fun epochDayOfToday(zoneId: ZoneId, nowMillis: Long): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().toEpochDay()

    /** 某自然日的展示文案（`yyyy-MM-dd`） */
    fun dateText(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    /** 分钟值（可空）-> 毫秒；null 与负值一律按 0（脏数据不产生负时长） */
    fun millisOfMinutes(minutes: Int?): Long =
        (minutes ?: 0).coerceAtLeast(0).toLong() * StatsConstants.MILLIS_PER_MINUTE

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

    /** 完成率百分比文案（保留 1 位小数，如 `87.5%`；无应做作业时输出 `0.0%`） */
    fun percentText(rate: Double): String =
        String.format(Locale.ROOT, "%.${StatsConstants.PERCENT_SCALE}f%%", rate.coerceIn(0.0, 1.0) * 100.0)

    // ---- 私有工具 ----

    /**
     * 当天详情是否留下执行痕迹：开始过计时、有实际时长或有暂停即算「动过」；
     * 未开始与「缺卡未做（MISSED）」都算没动过（后者是漏做，不是有耗时数据）。
     */
    private fun hasExecution(record: HomeworkDailyRecord?): Boolean {
        if (record == null) {
            return false
        }
        return record.startedAtMillis != null ||
            (record.actualMinutes ?: 0) > 0 ||
            record.pauseCount > 0
    }
}
