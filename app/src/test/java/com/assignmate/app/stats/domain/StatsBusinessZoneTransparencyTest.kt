package com.assignmate.app.stats.domain

import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.HomeworkItem
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 业务时区**透传闭合**专项用例（stats 侧，行为 + 结构双重护栏）。
 *
 * 背景（已确认的验收口径）：覆写 core 的唯一业务时区来源（`DailyRecordModule.provideBusinessZoneId`）
 * 必须**全局生效**，其中包含 stats 的「当天应做」口径。此前 [StatsCalculations.shouldDoOn] /
 * [StatsCalculations.stageProgressOf] 调用 homework 的
 * [com.assignmate.app.homework.domain.StageDayRecords] 时**省掉了 zoneId**，靠对方签名里的
 * `systemDefault()` 默认值兜底 —— 覆写唯一来源后 stats 的「当天应做」仍按系统时区判定（跨零点漂移）。
 * homework 侧已把五个推导入口的 `zoneId` 去默认值改必填（省参即编译错误），本文件从两侧锁死：
 *
 * 1. **行为**（不是「取到默认值就算过」）：
 *    - 跨零点基准时刻 `2024-12-31T20:53:20Z`（上海口径已是 2025-01-01 凌晨、UTC 口径仍是 12-31）
 *      下，切换业务时区后「当天应做」的应做日与完成率分母一起切换，且分母为 0 时仍是空态不除零；
 *    - 阶段作业的**可见性**与**阶段打卡进度**同样由注入时区决定（起始日回退到创建日的路径，
 *      覆盖区间随时区整体平移，分子分母都随之变化）；
 * 2. **结构**：stats 生产代码不得自建业务时区（`systemDefault()`）、不得调用 homework 的
 *    按自然日判定入口而漏传时区（时区实参按**标识符级** `zoneId` / `zone` 校验，只是「含 zone 字样」
 *    的实参不算透传）、阶段起始日不得绕过带回退的唯一入口。
 *
 * 为什么两个时区都要在同一用例内断言：任一实现若改回「吃默认值」，本机系统时区无论取上海还是 UTC，
 * 都会让其中一条断言失败（宿主机时区不是被测口径），不会出现「恰好通过」的假绿灯。
 */
class StatsBusinessZoneTransparencyTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val utc: ZoneId = ZoneId.of("UTC")

    /** 跨零点基准时刻：2024-12-31T20:53:20Z（上海 2025-01-01 04:53 凌晨、UTC 仍属 2024-12-31） */
    private val crossMidnightMillis = CROSS_MIDNIGHT_MILLIS

    /** 上海口径的自然日（2025-01-01） */
    private val shanghaiDay = SHANGHAI_DAY

    /** UTC 口径的自然日（2024-12-31，比上海口径早一天） */
    private val utcDay = UTC_DAY

    // ---- 一、跨零点：业务时区切换即「当天应做」分母切换 ----

    @Test
    fun `跨零点时刻按注入业务时区分别落到相邻两个自然日`() {
        assertEquals(shanghaiDay, StatsCalculations.epochDayOfToday(shanghai, crossMidnightMillis))
        assertEquals(utcDay, StatsCalculations.epochDayOfToday(utc, crossMidnightMillis))
        assertEquals("覆写时区必须改变自然日折算（相差一天）", 1L, shanghaiDay - utcDay)
    }

    @Test
    fun `覆写业务时区后当天作业的应做日与完成率分母随之切换`() {
        val item = StatsTestData.homework(1L, content = "语文朗读", createdAtMillis = crossMidnightMillis)

        // 归属日：上海凌晨时刻必须落在「新的一天」，不得漂移到前一天
        assertEquals("上海口径下该时刻已是 2025-01-01 凌晨", shanghaiDay, item.createdEpochDay(shanghai))
        assertEquals("同一时刻在 UTC 口径仍属 2024-12-31", utcDay, item.createdEpochDay(utc))

        // 应做日跟着注入时区走：两个时区各自认「自己的今天」，且互不认对方的今天
        assertTrue(StatsCalculations.shouldDoOn(item, shanghaiDay, shanghai))
        assertTrue(StatsCalculations.shouldDoOn(item, utcDay, utc))
        assertFalse(StatsCalculations.shouldDoOn(item, shanghaiDay, utc))
        assertFalse(StatsCalculations.shouldDoOn(item, utcDay, shanghai))

        // 完成率分母（当天应做）切换到 UTC 后，上海口径的那一天不再是应做日 → 空态而非虚高完成率
        val inShanghai = summarize(
            epochDay = shanghaiDay,
            todayEpochDay = shanghaiDay,
            items = listOf(item),
            zone = shanghai,
        )
        assertEquals(1, inShanghai.totalCount)
        assertFalse(inShanghai.isEmpty)
        assertEquals(0, inShanghai.completedCount)
        assertEquals(0.0, inShanghai.completionRate, 0.0)

        val inUtc = summarize(
            epochDay = shanghaiDay,
            todayEpochDay = shanghaiDay,
            items = listOf(item),
            zone = utc,
        )
        assertEquals("覆写时区后同一天的应做分母必须随之切换", 0, inUtc.totalCount)
        assertTrue("分母为 0 时是空态（不除零、不产生 NaN）", inUtc.isEmpty)
        assertEquals(0.0, inUtc.completionRate, 0.0)

        // UTC 口径下这条作业属于 2024-12-31：在它自己的「今天」仍进分母（证明不是整体失效）
        val utcOwnDay = summarize(
            epochDay = utcDay,
            todayEpochDay = utcDay,
            items = listOf(item),
            zone = utc,
        )
        assertEquals(1, utcOwnDay.totalCount)
    }

    // ---- 二、阶段作业可见性与阶段打卡进度同样受注入时区控制 ----

    @Test
    fun `阶段作业可见性由注入业务时区决定（起始日按业务时区回退到创建日）`() {
        // deadline 无法还原起始日（历史脏值）→ 由 homework 按业务时区回退到「作业创建日」
        val stage = StatsTestData.stageHomeworkWithFallbackStart(
            id = 1L,
            content = "背单词",
            createdAtMillis = crossMidnightMillis,
        )
        assertEquals(shanghaiDay, stage.stageStartEpochDayOr(shanghai))
        assertEquals(utcDay, stage.stageStartEpochDayOr(utc))

        // 一周覆盖随时区整体平移：上海口径的覆盖末日（+6 天）已越出 UTC 口径的覆盖区间
        val shanghaiLastCoveredDay = shanghaiDay + 6
        assertTrue(StatsCalculations.shouldDoOn(stage, shanghaiLastCoveredDay, shanghai))
        assertFalse(
            "同一天在 UTC 口径下已不在阶段覆盖范围内",
            StatsCalculations.shouldDoOn(stage, shanghaiLastCoveredDay, utc),
        )
        // 反向：UTC 口径的覆盖首日（2024-12-31）在上海口径下尚未进入阶段范围
        assertTrue(StatsCalculations.shouldDoOn(stage, utcDay, utc))
        assertFalse(StatsCalculations.shouldDoOn(stage, utcDay, shanghai))

        // 当日盘点的分母同步切换
        assertEquals(1, summarizeDay(shanghaiLastCoveredDay, listOf(stage), shanghai).totalCount)
        assertTrue(summarizeDay(shanghaiLastCoveredDay, listOf(stage), utc).isEmpty)
    }

    @Test
    fun `阶段打卡进度同样由注入业务时区决定（覆盖区间平移改变分子）`() {
        val stage = StatsTestData.stageHomeworkWithFallbackStart(
            id = 1L,
            content = "背单词",
            createdAtMillis = crossMidnightMillis,
        )
        // 相邻两天的完成记录：上海口径覆盖 [上海日 .. 上海日+6]（UTC 日已越出）；UTC 口径覆盖 [UTC 日 .. +5]
        val records = listOf(
            StatsTestData.dayRecord(1L, epochDay = utcDay, status = HomeworkDayStatus.COMPLETED),
            StatsTestData.dayRecord(1L, epochDay = shanghaiDay, status = HomeworkDayStatus.COMPLETED),
        )

        val inShanghai = StatsCalculations.stageProgressOf(
            item = stage,
            records = records,
            todayEpochDay = shanghaiDay,
            zoneId = shanghai,
        )
        val inUtc = StatsCalculations.stageProgressOf(
            item = stage,
            records = records,
            todayEpochDay = shanghaiDay,
            zoneId = utc,
        )

        assertEquals("上海口径覆盖区间不含 UTC 日那条记录", 1, inShanghai?.doneDays)
        assertEquals("UTC 口径覆盖区间两条记录都算打卡", 2, inUtc?.doneDays)
        assertEquals(7, inShanghai?.totalDays)
        assertEquals(7, inUtc?.totalDays)

        // 当日盘点携带的阶段进度与上方同源（分母 M 仍是覆盖天数，不随天数放大）
        val summaryInShanghai = summarizeDay(shanghaiDay, listOf(stage), shanghai, mapOf(1L to records))
        val summaryInUtc = summarizeDay(shanghaiDay, listOf(stage), utc, mapOf(1L to records))
        assertEquals(1, summaryInShanghai.stages.single().doneDays)
        assertEquals(2, summaryInUtc.stages.single().doneDays)
        assertEquals(7, summaryInShanghai.stages.single().totalDays)
    }

    @Test
    fun `单项详情按天取值时业务时区同样决定缺卡与打卡进度`() {
        // 阶段作业创建于基准时刻的 3 天前：起始日回退到创建日 → 上海 2025-01-01 前 3 天、UTC 再早一天
        val stage = StatsTestData.stageHomeworkWithFallbackStart(
            id = 1L,
            content = "背单词",
            createdAtMillis = crossMidnightMillis - 3L * MILLIS_PER_DAY,
        )
        // 覆盖区间：上海 [上海日-3 .. 上海日+3]、UTC [UTC 日-3 .. UTC 日+3]（整体早一天）
        val records = listOf(
            StatsTestData.dayRecord(1L, epochDay = shanghaiDay - 3, status = HomeworkDayStatus.COMPLETED),
            StatsTestData.dayRecord(1L, epochDay = shanghaiDay + 3, status = HomeworkDayStatus.COMPLETED),
        )

        // 同一天、同一个「今天」，只换注入时区：
        // - UTC 口径：该天落在覆盖区间内且已过去 → 缺卡（未完成）；
        // - 上海口径：该天尚未进入覆盖区间 → 未开始（不是缺卡）。
        val detailInUtc = StatsCalculations.dayDetail(
            item = stage,
            records = records,
            epochDay = utcDay - 3,
            todayEpochDay = shanghaiDay,
            zoneId = utc,
        )
        val detailInShanghai = StatsCalculations.dayDetail(
            item = stage,
            records = records,
            epochDay = utcDay - 3,
            todayEpochDay = shanghaiDay,
            zoneId = shanghai,
        )

        assertEquals(HomeworkDayStatus.MISSED, detailInUtc.dayStatus)
        assertEquals(HomeworkDayStatus.NOT_STARTED, detailInShanghai.dayStatus)
        // 打卡进度同源：覆盖区间平移后，末日那条完成记录只有上海口径算数
        assertEquals(1, detailInUtc.stageProgress?.doneDays)
        assertEquals(2, detailInShanghai.stageProgress?.doneDays)
        assertEquals(7, detailInUtc.stageProgress?.totalDays)
    }

    // ---- 三、结构护栏（防「又吃默认值」回流） ----

    @Test
    fun `stats 生产代码不得自建业务时区来源`() {
        val sources = statsMainSources()
        assertTrue("未扫描到足够的 stats 源文件，路径解析可能失效", sources.size >= 10)
        val offenders = sources.filter { codeText(it).contains("systemDefault") }.map { it.name }
        assertTrue(
            "stats 不得出现 systemDefault 兜底（业务时区只能由 core 唯一来源注入）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `stats 调用 homework 按自然日判定的入口必须显式传入业务时区`() {
        val calls = statsMainSources().flatMap { file ->
            STAGE_DAY_RECORDS_CALL.findAll(codeText(file)).map { match ->
                Triple(file.name, match.groupValues[1], match.groupValues[2])
            }
        }
        val zoneAware = calls.filter { it.second in ZONE_REQUIRED_ENTRIES }
        assertTrue("未扫描到 homework 自然日判定入口的调用点，结构断言可能失效：$calls", zoneAware.size >= 2)
        val missing = zoneAware
            .filterNot { ZONE_TOKEN.containsMatchIn(it.third) }
            .map { "${it.first}: StageDayRecords.${it.second}(...)" }
        assertTrue("以下调用点未显式透传业务时区（禁止依赖对方默认值）：$missing", missing.isEmpty())
    }

    @Test
    fun `阶段起始日只允许走带回退的唯一入口`() {
        val offenders = statsMainSources()
            .filter { RAW_STAGE_START_DAY.containsMatchIn(codeText(it)) }
            .map { it.name }
        assertTrue(
            "阶段起始日必须经 HomeworkItem.stageStartEpochDayOr(zoneId)（与 shouldDoOn 的覆盖区间同源，" +
                "否则会出现「分母算应做、状态却永远未开始」的口径分叉）：$offenders",
            offenders.isEmpty(),
        )
    }

    // ---- 夹具与源码扫描 ----

    /** 某一天的盘点（业务时区显式传入，正是被测口径） */
    private fun summarize(
        epochDay: Long,
        todayEpochDay: Long,
        items: List<HomeworkItem>,
        zone: ZoneId,
        recordsByHomework: Map<Long, List<HomeworkDailyRecord>> = emptyMap(),
    ): DaySummary = StatsCalculations.summarizeDay(
        epochDay = epochDay,
        todayEpochDay = todayEpochDay,
        items = items,
        recordsByHomework = recordsByHomework,
        zoneId = zone,
    )

    /** 某一天的盘点（「今天」固定为被测那一天，便于只关注阶段可见性） */
    private fun summarizeDay(
        epochDay: Long,
        items: List<HomeworkItem>,
        zone: ZoneId,
        recordsByHomework: Map<Long, List<HomeworkDailyRecord>> = emptyMap(),
    ): DaySummary = summarize(
        epochDay = epochDay,
        todayEpochDay = epochDay,
        items = items,
        zone = zone,
        recordsByHomework = recordsByHomework,
    )

    /** stats 生产源码文件（不含测试源码） */
    private fun statsMainSources(): List<File> {
        val root = File(repoRoot(), STATS_SOURCE_ROOT)
        assertTrue("stats 主源码目录不存在: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** 去掉块注释与行注释后的代码文本：注释里的说明字样不得参与结构校验 */
    private fun codeText(file: File): String =
        file.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "\n")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, STATS_SOURCE_ROOT).isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("在工作目录 ${File("").absolutePath} 向上未找到 $STATS_SOURCE_ROOT")
    }

    private companion object {

        const val STATS_SOURCE_ROOT = "app/src/main/java/com/assignmate/app/stats"

        /** 固定时刻：2024-12-31T20:53:20Z（上海 2025-01-01 04:53、UTC 2024-12-31 20:53） */
        const val CROSS_MIDNIGHT_MILLIS = 1_735_678_400_000L

        /** 2025-01-01 的业务自然日 */
        const val SHANGHAI_DAY = 20_089L

        /** 2024-12-31 的业务自然日 */
        const val UTC_DAY = 20_088L

        /** 一天（毫秒）：构造「几天前创建」的作业项 */
        const val MILLIS_PER_DAY = 86_400_000L

        /** `StageDayRecords.<入口>(<参数>)` 调用点：参数可跨行（`[^)]` 会匹配换行） */
        val STAGE_DAY_RECORDS_CALL = Regex("StageDayRecords\\.(\\w+)\\s*\\(([^)]*)\\)")

        /** homework 中**按自然日判定**、必须显式传业务时区的入口（其余入口不涉及时区） */
        val ZONE_REQUIRED_ENTRIES = setOf(
            "isVisibleOnDay",
            "todayOutcome",
            "progressOf",
            "isTodayActionable",
            "outcomeFor",
            "dailyDeadlineOf",
        )

        /**
         * 业务时区实参形态（**标识符级**）：必须是独立标识符 `zoneId` / `zone`。
         *
         * 为什么不能用 `[Zz]one` 这类子串匹配：任何「含 zone 字样」的实参都能通过，
         * 断言形同虚设——离朱/皋陶实测：把实参换成 `timezoneFallback` 这类与业务时区无关的
         * 标识符时旧断言仍全绿（假绿）。收紧为标识符级后：
         * - `timezoneFallback` / `zoneOffsetOfDay` 这类「只含字样」的实参被拦下；
         * - `ZoneId.systemDefault()` 也被拦下（大小写敏感，且它同时被「不得自建时区来源」用例看护）；
         * 变异验证（隔离副本 `ZONE_TOKEN` 换成旧正则对照）：`timezoneFallback` 变异在旧正则下全绿、
         * 在新正则下变红，具备杀伤力。
         */
        val ZONE_TOKEN = Regex("\\b(?:zoneId|zone)\\b")

        /** 不带回退的裸属性访问：`stageStartEpochDay` 后紧跟 `Or` 即为允许的入口 */
        val RAW_STAGE_START_DAY = Regex("stageStartEpochDay(?!Or)")
    }
}
