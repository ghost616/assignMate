package com.assignmate.app.stats.data

import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.stats.domain.HistoryQuery
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 业务时区**透传闭合**在仓库链路上的行为用例（stats 侧）。
 *
 * 与 `StatsBusinessZoneTransparencyTest`（纯函数侧）互补：本文件用**真实的 [StatsRepositoryImpl]** +
 * 内存替身，把「覆写 core 唯一业务时区来源」等价为「换个 `zoneId` 装配仓库」，验证注入时区
 * 从构造函数一路走到「当天应做」分母、阶段可见性与阶段打卡进度——纯函数层传对了、仓库层漏传的
 * 退化形态（例如内部又去取默认时区）会在这里变红。
 *
 * 口径基准：固定时钟 [StatsRepositoryTestEnv.FIXED_MILLIS] = 2023-11-13T16:09:00Z，
 * 上海口径已是 2023-11-14 00:09（凌晨，跨过零点），UTC 口径仍是 2023-11-13 —— 与
 * 「UTC+8 凌晨归属日不得漂移到前一天」的验收例子同构。
 */
class StatsBusinessZoneRepositoryTest {

    private val shanghai: ZoneId = StatsRepositoryTestEnv.ZONE
    private val utc: ZoneId = ZoneId.of("UTC")

    private val studentId = StatsRepositoryTestEnv.STUDENT_ID

    /** 上海口径的「今天」（2023-11-14） */
    private val shanghaiDay = StatsRepositoryTestEnv.DAY_EPOCH

    /** UTC 口径的「今天」（2023-11-13） */
    private val utcDay = StatsRepositoryTestEnv.DAY_EPOCH - 1

    /** 阶段作业夹具的创建时刻：基准日 3 天前（上海 2023-11-11、UTC 2023-11-10） */
    private val threeDaysBefore = StatsRepositoryTestEnv.DAY_START_MILLIS - THREE_DAYS_MILLIS

    /** 上海口径下周覆盖的末日（上海起始日 + 6 天） */
    private val shanghaiLastCoveredDay = shanghaiDay + 3

    /** UTC 口径下周覆盖的首日（UTC 起始日） */
    private val utcFirstCoveredDay = utcDay - 3

    // ---- 一、当天应做（完成率分母）随注入时区切换 ----

    @Test
    fun `覆写业务时区后仓库的当天应做分母随之切换（跨零点不漂移）`() = runTest {
        val item = statsTestHomework(1L, content = "语文朗读", createdAtMillis = StatsRepositoryTestEnv.FIXED_MILLIS)

        val inShanghai = StatsRepositoryTestEnv(zoneId = shanghai)
        inShanghai.homeworkRepository.put(item)
        val inUtc = StatsRepositoryTestEnv(zoneId = utc)
        inUtc.homeworkRepository.put(item)

        // 上海口径：凌晨时刻属于 2023-11-14 → 该天的应做分母为 1
        val shanghaiSummary = success(inShanghai.repository.summarizeDay(studentId, shanghaiDay))
        assertEquals(1, shanghaiSummary.totalCount)
        assertFalse(shanghaiSummary.isEmpty)
        assertEquals(shanghaiDay, shanghaiSummary.epochDay)

        // UTC 口径：同一时刻仍属 2023-11-13 → 2023-11-14 不是它的应做日，分母为 0（空态不除零）
        val utcSummary = success(inUtc.repository.summarizeDay(studentId, shanghaiDay))
        assertEquals("覆写时区后同一目标日的应做分母必须随之切换", 0, utcSummary.totalCount)
        assertTrue(utcSummary.isEmpty)
        assertEquals(0.0, utcSummary.completionRate, 0.0)

        // 而它在 UTC 口径下的「自己的今天」仍进分母（证明不是整体失效）
        assertEquals(1, success(inUtc.repository.summarizeDay(studentId, utcDay)).totalCount)
    }

    @Test
    fun `历史范围查询的应做日同样随注入时区切换`() = runTest {
        val item = statsTestHomework(1L, createdAtMillis = StatsRepositoryTestEnv.FIXED_MILLIS)

        val inShanghai = StatsRepositoryTestEnv(zoneId = shanghai)
        inShanghai.homeworkRepository.put(item)
        val inUtc = StatsRepositoryTestEnv(zoneId = utc)
        inUtc.homeworkRepository.put(item)

        val query = HistoryQuery(shanghaiDay - 2, shanghaiDay)
        assertEquals(
            "上海口径：应做日即 2023-11-14",
            listOf(shanghaiDay),
            success(inShanghai.repository.history(studentId, query)).map { it.epochDay },
        )
        assertEquals(
            "UTC 口径：同一范围内应做日落在 2023-11-13",
            listOf(utcDay),
            success(inUtc.repository.history(studentId, query)).map { it.epochDay },
        )
    }

    // ---- 二、阶段作业可见性与打卡进度随注入时区切换 ----

    @Test
    fun `阶段作业的可见性与打卡进度由注入时区决定（仓库链路）`() = runTest {
        val inShanghai = envWithStage(shanghai)
        val inUtc = envWithStage(utc)

        // 一周覆盖整体平移：上海 [11-11 .. 11-17]、UTC [11-10 .. 11-16]（基准日 11-14 / 11-13）
        assertEquals(
            "上海口径：覆盖末日仍是应做日",
            1,
            success(inShanghai.repository.summarizeDay(studentId, shanghaiLastCoveredDay)).totalCount,
        )
        assertTrue(
            "同一日在 UTC 口径下已越出阶段覆盖区间",
            success(inUtc.repository.summarizeDay(studentId, shanghaiLastCoveredDay)).isEmpty,
        )
        assertEquals(
            "UTC 口径：覆盖首日仍是应做日",
            1,
            success(inUtc.repository.summarizeDay(studentId, utcFirstCoveredDay)).totalCount,
        )
        assertTrue(
            "同一日在上海口径下尚未进入阶段覆盖区间",
            success(inShanghai.repository.summarizeDay(studentId, utcFirstCoveredDay)).isEmpty,
        )

        // 同一天（上海口径的今天）的打卡进度：覆盖区间平移后，末日那条完成记录只有上海口径算数
        val shanghaiSummary = success(inShanghai.repository.summarizeDay(studentId, shanghaiDay))
        val utcSummary = success(inUtc.repository.summarizeDay(studentId, shanghaiDay))
        assertEquals(2, shanghaiSummary.stages.single().doneDays)
        assertEquals(1, utcSummary.stages.single().doneDays)
        assertEquals(7, shanghaiSummary.stages.single().totalDays)
        assertEquals(7, utcSummary.stages.single().totalDays)
    }

    @Test
    fun `单项详情的当天状态与打卡进度同样由注入时区决定`() = runTest {
        val inShanghai = envWithStage(shanghai)
        val inUtc = envWithStage(utc)

        // 同一天、同一个「今天」，只换注入时区：
        // - UTC 口径：该天是阶段首日且已过去、没有完成记录 → 缺卡（未完成）；
        // - 上海口径：该天尚未进入覆盖区间 → 未开始（不是缺卡）。
        val detailInUtc = success(inUtc.repository.itemDetail(HOMEWORK_ID, utcFirstCoveredDay))
        val detailInShanghai = success(inShanghai.repository.itemDetail(HOMEWORK_ID, utcFirstCoveredDay))

        assertEquals(HomeworkDayStatus.MISSED, detailInUtc.dayStatus)
        assertEquals(HomeworkDayStatus.NOT_STARTED, detailInShanghai.dayStatus)
        // 打卡进度同源（分母仍是覆盖天数，不随记录条数放大）
        assertEquals(1, detailInUtc.stageProgress?.doneDays)
        assertEquals(2, detailInShanghai.stageProgress?.doneDays)
        assertEquals(7, detailInUtc.stageProgress?.totalDays)
    }

    // ---- 夹具 ----

    /**
     * 装配「阶段作业起始日只能回退到创建日」的仓库环境：deadline 为空 → 起始日 = 按注入时区折算的创建日，
     * 故覆写时区即整体平移覆盖区间；同时登记两条完成记录（覆盖首日 + 上海口径的覆盖末日）。
     */
    private fun envWithStage(zone: ZoneId): StatsRepositoryTestEnv {
        val stage = statsTestStageHomework(HOMEWORK_ID, content = "背单词").copy(
            deadline = null,
            createdAt = Instant.ofEpochMilli(threeDaysBefore),
        )
        return StatsRepositoryTestEnv(zoneId = zone).also { env ->
            env.homeworkRepository.put(stage)
            env.dailyRecordRepository.put(
                statsTestDayRecord(HOMEWORK_ID, epochDay = shanghaiDay - 3, status = HomeworkDayStatus.COMPLETED),
            )
            env.dailyRecordRepository.put(
                statsTestDayRecord(HOMEWORK_ID, epochDay = shanghaiDay + 3, status = HomeworkDayStatus.COMPLETED),
            )
        }
    }

    private fun <T> success(result: StatsResult<T>): T {
        assertTrue("期望成功但得到：$result", result is StatsResult.Success)
        return (result as StatsResult.Success).data
    }

    private companion object {

        /** 阶段作业夹具的作业 id */
        const val HOMEWORK_ID = 1L

        /** 三天（毫秒）：构造「几天前创建」的阶段作业 */
        const val THREE_DAYS_MILLIS = 3L * 86_400_000L
    }
}
