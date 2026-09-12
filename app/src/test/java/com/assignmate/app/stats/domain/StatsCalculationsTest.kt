package com.assignmate.app.stats.domain

import com.assignmate.app.homework.domain.HomeworkStatus
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatsCalculations] 单测：完成率边界、暂停汇总、最久暂停、单项详情、当日盘点与历史范围聚合。
 *
 * 全部用例为纯函数断言（传入构造时刻，不依赖真实时钟与数据库），
 * 业务时区固定为 [StatsTestData.ZONE]（Asia/Shanghai），当日窗口起点为
 * [StatsTestData.DAY_20231114_START]。
 */
class StatsCalculationsTest {

    private val zone = StatsTestData.ZONE

    // ---- 完成率 ----

    @Test
    fun `无作业时完成率为 0 且不产生 NaN`() {
        assertEquals(0.0, StatsCalculations.completionRate(completed = 0, total = 0), 0.0)
    }

    @Test
    fun `全部完成时完成率为 1`() {
        assertEquals(1.0, StatsCalculations.completionRate(completed = 5, total = 5), 0.0)
    }

    @Test
    fun `部分完成时完成率为已完成除以总数`() {
        assertEquals(0.5, StatsCalculations.completionRate(completed = 3, total = 6), 1e-9)
    }

    @Test
    fun `完成数为负时按 0 计（脏数据不产生负完成率）`() {
        assertEquals(0.0, StatsCalculations.completionRate(completed = -2, total = 4), 0.0)
    }

    @Test
    fun `已完成计数只统计 COMPLETED 状态`() {
        val items = listOf(
            StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED),
            StatsTestData.homework(2L, status = HomeworkStatus.IN_PROGRESS),
            StatsTestData.homework(3L, status = HomeworkStatus.PENDING),
            StatsTestData.homework(4L, status = HomeworkStatus.RECORDED),
        )
        assertEquals(1, StatsCalculations.completedCount(items))
    }

    // ---- 暂停汇总 ----

    @Test
    fun `暂停时长按已结束段落累加`() {
        val pauses = listOf(
            StatsTestData.pause(StatsTestData.at(0), StatsTestData.at(2)),
            StatsTestData.pause(StatsTestData.at(10), StatsTestData.at(15)),
        )
        assertEquals(7 * StatsTestData.MINUTE, StatsCalculations.pauseAccumulatedMillis(pauses, StatsTestData.at(60)))
    }

    @Test
    fun `未结束暂停按参考时刻折算`() {
        val pauses = listOf(StatsTestData.ongoingPause(StatsTestData.at(10)))
        assertEquals(5 * StatsTestData.MINUTE, StatsCalculations.pauseAccumulatedMillis(pauses, StatsTestData.at(15)))
    }

    @Test
    fun `暂停结束时刻晚于参考时刻时按参考时刻截断`() {
        val pauses = listOf(StatsTestData.pause(StatsTestData.at(5), StatsTestData.at(50)))
        assertEquals(10 * StatsTestData.MINUTE, StatsCalculations.pauseAccumulatedMillis(pauses, StatsTestData.at(15)))
    }

    @Test
    fun `开始时刻晚于结束时刻的异常暂停按 0 计`() {
        val pauses = listOf(StatsTestData.pause(StatsTestData.at(20), StatsTestData.at(10)))
        assertEquals(0L, StatsCalculations.pauseAccumulatedMillis(pauses, StatsTestData.at(30)))
    }

    @Test
    fun `暂停次数与明细条数同源且未结束暂停同样计入`() {
        val pauses = listOf(
            StatsTestData.pause(StatsTestData.at(1), StatsTestData.at(2)),
            StatsTestData.ongoingPause(StatsTestData.at(10)),
        )
        assertEquals(2, StatsCalculations.pauseCountOf(pauses))
    }

    @Test
    fun `暂停次数对完全相同的明细去重`() {
        val repeated = StatsTestData.pause(StatsTestData.at(1), StatsTestData.at(2))
        assertEquals(1, StatsCalculations.pauseCountOf(listOf(repeated, repeated)))
    }

    // ---- 最久暂停作业（模块内唯一的「暂停次数」语义为 pauseCountOf 的去重口径） ----

    @Test
    fun `单项详情与暂停次数口径一致（重复明细去重）`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED)
        val repeated = StatsTestData.pause(StatsTestData.at(1), StatsTestData.at(3))
        val sessions = listOf(
            StatsTestData.session(
                sessionId = 10L,
                homeworkId = 1L,
                startedAtMillis = StatsTestData.at(0),
                finishedAtMillis = StatsTestData.at(30),
                // 脏数据：同一段暂停被重复写入两次
                pauses = listOf(repeated, repeated),
            ),
        )
        val detail = StatsCalculations.itemDetail(item, sessions, referenceMillis = StatsTestData.at(60))
        assertEquals(StatsCalculations.pauseCountOf(listOf(repeated, repeated)), detail.pauseCount)
        assertEquals(1, detail.pauseCount)
    }

    @Test
    fun `暂停最久的作业按作业聚合后取最大`() {
        val items = listOf(
            StatsTestData.homework(1L, content = "语文生字", priority = 100),
            StatsTestData.homework(2L, content = "数学口算", priority = 101),
        )
        val result = StatsCalculations.mostPausedHomeworkOf(
            items = items,
            pausedByHomework = mapOf(
                1L to PausedAccum(pausedMillis = 2 * StatsTestData.MINUTE, count = 1),
                2L to PausedAccum(pausedMillis = 10 * StatsTestData.MINUTE, count = 1),
            ),
        )
        assertEquals(2L, result?.homeworkId)
        assertEquals("数学口算", result?.content)
        assertEquals(10 * StatsTestData.MINUTE, result?.pausedMillis)
        assertEquals(1, result?.pauseCount)
    }

    @Test
    fun `暂停时长并列时取优先级靠前的作业`() {
        val items = listOf(
            StatsTestData.homework(1L, priority = 50),
            StatsTestData.homework(2L, priority = 10),
        )
        val samePause = PausedAccum(pausedMillis = 5 * StatsTestData.MINUTE, count = 1)
        val result = StatsCalculations.mostPausedHomeworkOf(
            items = items,
            pausedByHomework = mapOf(1L to samePause, 2L to samePause),
        )
        assertEquals(2L, result?.homeworkId)
    }

    @Test
    fun `不在当日清单内的作业其暂停不参与最久暂停评选`() {
        val items = listOf(StatsTestData.homework(1L))
        val result = StatsCalculations.mostPausedHomeworkOf(
            items = items,
            pausedByHomework = mapOf(99L to PausedAccum(pausedMillis = 9 * StatsTestData.MINUTE, count = 1)),
        )
        assertNull(result)
    }

    @Test
    fun `暂停汇总为空或时长为零时不产生暂停最久作业`() {
        val items = listOf(StatsTestData.homework(1L))
        assertNull(
            StatsCalculations.mostPausedHomeworkOf(
                items = items,
                pausedByHomework = mapOf(1L to PausedAccum.EMPTY),
            ),
        )
    }

    // ---- 单项详情 ----

    @Test
    fun `单项详情汇总预估实际暂停与执行次数`() {
        val item = StatsTestData.homework(1L, estimatedMinutes = 30, status = HomeworkStatus.COMPLETED)
        val sessions = listOf(
            StatsTestData.session(
                sessionId = 10L,
                homeworkId = 1L,
                startedAtMillis = StatsTestData.at(0),
                finishedAtMillis = StatsTestData.at(30),
                pauses = listOf(StatsTestData.pause(StatsTestData.at(10), StatsTestData.at(15))),
            ),
        )
        val detail = StatsCalculations.itemDetail(item, sessions, referenceMillis = StatsTestData.at(60))
        assertEquals(30, detail.estimatedMinutes)
        assertEquals(25 * StatsTestData.MINUTE, detail.elapsedMillis)
        assertEquals(5 * StatsTestData.MINUTE, detail.pausedTotalMillis)
        assertEquals(1, detail.pauseCount)
        assertEquals(1, detail.sessionCount)
        assertTrue(detail.hasExecution)
    }

    @Test
    fun `多次执行会话的实际耗时累加`() {
        val item = StatsTestData.homework(1L, estimatedMinutes = 30, status = HomeworkStatus.COMPLETED)
        val sessions = listOf(
            StatsTestData.session(10L, 1L, StatsTestData.at(0), StatsTestData.at(10)),
            StatsTestData.session(11L, 1L, StatsTestData.at(60), StatsTestData.at(70)),
        )
        val detail = StatsCalculations.itemDetail(item, sessions, referenceMillis = StatsTestData.at(120))
        assertEquals(20 * StatsTestData.MINUTE, detail.elapsedMillis)
        assertEquals(2, detail.sessionCount)
    }

    @Test
    fun `未结束会话按参考时刻折算实际耗时`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.IN_PROGRESS)
        val sessions = listOf(
            StatsTestData.session(10L, 1L, StatsTestData.at(0), finishedAtMillis = null),
        )
        val detail = StatsCalculations.itemDetail(item, sessions, referenceMillis = StatsTestData.at(20))
        assertEquals(20 * StatsTestData.MINUTE, detail.elapsedMillis)
    }

    @Test
    fun `无执行记录时单项详情标记为尚未开始`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.PENDING)
        val detail = StatsCalculations.itemDetail(item, emptyList(), referenceMillis = StatsTestData.at(60))
        assertFalse(detail.hasExecution)
        assertEquals(0, detail.sessionCount)
        assertEquals(0L, detail.elapsedMillis)
        assertEquals(DifficultyLevel.UNKNOWN, detail.difficulty)
        assertEquals(DifficultyAssessor.HINT_NOT_STARTED, detail.assessmentHint)
    }

    @Test
    fun `其他作业的会话不计入本项详情`() {
        val item = StatsTestData.homework(1L)
        val sessions = listOf(StatsTestData.session(10L, 2L, StatsTestData.at(0), StatsTestData.at(10)))
        val detail = StatsCalculations.itemDetail(item, sessions, referenceMillis = StatsTestData.at(60))
        assertFalse(detail.hasExecution)
    }

    // ---- 当日盘点 ----

    @Test
    fun `当日盘点纳入当日有执行记录的作业并统计完成率与暂停`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED)
        val untouched = StatsTestData.homework(2L, status = HomeworkStatus.PENDING)
        val sessions = listOf(
            StatsTestData.session(
                sessionId = 10L,
                homeworkId = 1L,
                startedAtMillis = StatsTestData.at(0),
                finishedAtMillis = StatsTestData.at(10),
                pauses = listOf(StatsTestData.pause(StatsTestData.at(2), StatsTestData.at(4))),
            ),
        )
        val summary = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item, untouched),
            sessions = sessions,
            dayStartMillis = StatsTestData.DAY_20231114_START,
            dayEndMillis = StatsTestData.at(24 * 60),
            referenceMillis = StatsTestData.at(24 * 60),
        )
        assertEquals(1, summary.totalCount)
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.pauseCount)
        assertEquals(2 * StatsTestData.MINUTE, summary.pausedTotalMillis)
        assertEquals(1L, summary.mostPausedItem?.homeworkId)
        assertEquals(listOf(1L), summary.items.map { it.id })
        assertEquals(1.0, summary.completionRate, 1e-9)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun `当日盘点排除窗口外的执行记录`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED)
        val sessions = listOf(
            StatsTestData.session(10L, 1L, StatsTestData.at(-30), StatsTestData.at(-20)),
        )
        val summary = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item),
            sessions = sessions,
            dayStartMillis = StatsTestData.DAY_20231114_START,
            dayEndMillis = StatsTestData.at(24 * 60),
            referenceMillis = StatsTestData.at(24 * 60),
        )
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.totalCount)
        assertEquals(0, summary.pauseCount)
    }

    @Test
    fun `跨天会话按当日窗口裁剪后仍纳入当日盘点`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.IN_PROGRESS)
        val sessions = listOf(
            StatsTestData.session(
                sessionId = 10L,
                homeworkId = 1L,
                startedAtMillis = StatsTestData.at(-10),
                finishedAtMillis = null,
            ),
        )
        val summary = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item),
            sessions = sessions,
            dayStartMillis = StatsTestData.DAY_20231114_START,
            dayEndMillis = StatsTestData.at(24 * 60),
            referenceMillis = StatsTestData.at(20),
        )
        // 会话 [-10, 现在=+20] 与当日窗口 [0, 24h) 相交，故该作业纳入当日盘点（分子为 0，未完成）
        assertEquals(listOf(1L), summary.items.map { it.id })
        assertEquals(1, summary.totalCount)
        assertEquals(0, summary.completedCount)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun `跨天暂停按当日窗口裁剪且不重复计入相邻两天`() {
        val item = StatsTestData.homework(1L)
        // 会话：前一日 22:00 → 当日 01:30；暂停：前一日 23:00 → 当日 00:30（跨零点 1.5 小时）
        val session = StatsTestData.session(
            sessionId = 10L,
            homeworkId = 1L,
            startedAtMillis = StatsTestData.at(-120),
            finishedAtMillis = StatsTestData.at(90),
            pauses = listOf(StatsTestData.pause(StatsTestData.at(-60), StatsTestData.at(30))),
        )
        val today = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item),
            sessions = listOf(session),
            dayStartMillis = StatsTestData.DAY_20231114_START,
            dayEndMillis = StatsTestData.at(24 * 60),
            referenceMillis = StatsTestData.at(24 * 60),
        )
        val yesterday = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH - 1,
            items = listOf(item),
            sessions = listOf(session),
            dayStartMillis = StatsTestData.at(-24 * 60),
            dayEndMillis = StatsTestData.DAY_20231114_START,
            referenceMillis = StatsTestData.at(24 * 60),
        )
        // 暂停段 [-60, +30] 被当日窗口 [0, 24h) 裁到 [0, +30] = 30 分钟（当天只承担落在当天的部分）
        assertEquals(30 * StatsTestData.MINUTE, today.pausedTotalMillis)
        assertEquals(1, today.pauseCount)
        // 前一日窗口同理只承担 [-60, 0) = 60 分钟，两天合计恰好等于暂停总长 90 分钟（不重不漏）
        assertEquals(60 * StatsTestData.MINUTE, yesterday.pausedTotalMillis)
        assertEquals(1, yesterday.pauseCount)
        assertEquals(
            90 * StatsTestData.MINUTE,
            today.pausedTotalMillis + yesterday.pausedTotalMillis,
        )
    }

    @Test
    fun `暂停中的会话按参考时刻折算并计入当日`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.IN_PROGRESS)
        val session = StatsTestData.session(
            sessionId = 10L,
            homeworkId = 1L,
            startedAtMillis = StatsTestData.at(0),
            finishedAtMillis = null,
            pauses = listOf(StatsTestData.ongoingPause(StatsTestData.at(10))),
        )
        val summary = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item),
            sessions = listOf(session),
            dayStartMillis = StatsTestData.DAY_20231114_START,
            dayEndMillis = StatsTestData.at(24 * 60),
            referenceMillis = StatsTestData.at(20),
        )
        assertEquals(10 * StatsTestData.MINUTE, summary.pausedTotalMillis)
        assertEquals(1, summary.pauseCount)
        assertEquals(1, summary.totalCount)
        assertEquals(0, summary.completedCount)
    }

    @Test
    fun `参考时刻早于当日起点时收敛到日起点且当日完成记录仍纳入`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED)
        val session = StatsTestData.session(10L, 1L, StatsTestData.at(10), StatsTestData.at(20))
        val summary = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item),
            sessions = listOf(session),
            dayStartMillis = StatsTestData.DAY_20231114_START,
            dayEndMillis = StatsTestData.at(24 * 60),
            // 参考时刻被收敛到当日 00:00：计时净耗时为 0，但会话结束时刻在当日，故仍计入当日完成
            referenceMillis = StatsTestData.at(-100),
        )
        assertEquals(1, summary.totalCount)
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.items.size)
    }

    @Test
    fun `刚点开始计时（净耗时为 0）的进行中作业仍纳入当日盘点`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.IN_PROGRESS)
        val session = StatsTestData.session(
            sessionId = 10L,
            homeworkId = 1L,
            startedAtMillis = StatsTestData.at(9),
            finishedAtMillis = null,
        )
        val summary = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item),
            sessions = listOf(session),
            dayStartMillis = StatsTestData.DAY_20231114_START,
            dayEndMillis = StatsTestData.at(24 * 60),
            referenceMillis = StatsTestData.at(9),
        )
        assertEquals(1, summary.totalCount)
        assertEquals(0, summary.completedCount)
        assertEquals(0.0, summary.completionRate, 0.0)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun `进行中会话开始于窗口之外时不纳入当日盘点`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.IN_PROGRESS)
        // 会话起点在窗口之前且仍未结束：当日窗口内既无净耗时也不构成「当日在进行中」
        val session = StatsTestData.session(
            sessionId = 10L,
            homeworkId = 1L,
            startedAtMillis = StatsTestData.at(-30),
            finishedAtMillis = null,
        )
        val summary = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item),
            sessions = listOf(session),
            dayStartMillis = StatsTestData.DAY_20231114_START,
            dayEndMillis = StatsTestData.at(24 * 60),
            referenceMillis = StatsTestData.at(-1),
        )
        assertTrue(summary.isEmpty)
    }

    @Test
    fun `空数据当日盘点为空且完成率为 0`() {
        val summary = StatsCalculations.summarizeDay(
            epochDay = StatsTestData.DAY_20231114_EPOCH,
            items = emptyList(),
            sessions = emptyList(),
            dayStartMillis = StatsTestData.DAY_20231114_START,
            dayEndMillis = StatsTestData.at(24 * 60),
            referenceMillis = StatsTestData.at(24 * 60),
        )
        assertTrue(summary.isEmpty)
        assertEquals(0.0, summary.completionRate, 0.0)
        assertEquals(StatsTestData.DAY_20231114_EPOCH, summary.epochDay)
        assertNull(summary.mostPausedItem)
    }

    // ---- 历史范围查询 ----

    @Test
    fun `范围查询逐日聚合且按日期倒序`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED)
        val sessions = listOf(
            StatsTestData.session(10L, 1L, StatsTestData.at(0), StatsTestData.at(10)),
            StatsTestData.session(11L, 1L, StatsTestData.at(-24 * 60), StatsTestData.at(-24 * 60 + 10)),
        )
        val summaries = StatsCalculations.summarizeRange(
            fromEpochDay = StatsTestData.DAY_20231114_EPOCH - 1,
            toEpochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item),
            sessions = sessions,
            referenceMillis = StatsTestData.at(24 * 60),
            dayStartMillisOf = { day -> StatsCalculations.dayStartMillis(day, OFFSET_MILLIS) },
        )
        assertEquals(2, summaries.size)
        assertEquals(StatsTestData.DAY_20231114_EPOCH, summaries.first().epochDay)
        assertEquals(StatsTestData.DAY_20231114_EPOCH - 1, summaries.last().epochDay)
    }

    @Test
    fun `范围查询跳过无记录的日子`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED)
        val sessions = listOf(StatsTestData.session(10L, 1L, StatsTestData.at(0), StatsTestData.at(10)))
        val summaries = StatsCalculations.summarizeRange(
            fromEpochDay = StatsTestData.DAY_20231114_EPOCH - 3,
            toEpochDay = StatsTestData.DAY_20231114_EPOCH,
            items = listOf(item),
            sessions = sessions,
            referenceMillis = StatsTestData.at(24 * 60),
            dayStartMillisOf = { day -> StatsCalculations.dayStartMillis(day, OFFSET_MILLIS) },
        )
        assertEquals(listOf(StatsTestData.DAY_20231114_EPOCH), summaries.map { it.epochDay })
    }

    @Test
    fun `范围查询的起止日倒置时返回空列表`() {
        val summaries = StatsCalculations.summarizeRange(
            fromEpochDay = StatsTestData.DAY_20231114_EPOCH,
            toEpochDay = StatsTestData.DAY_20231114_EPOCH - 1,
            items = emptyList(),
            sessions = emptyList(),
            referenceMillis = StatsTestData.at(0),
            dayStartMillisOf = { day -> StatsCalculations.dayStartMillis(day, OFFSET_MILLIS) },
        )
        assertTrue(summaries.isEmpty())
    }

    @Test
    fun `范围合计累加各日指标并取暂停最久的一项`() {
        val item = StatsTestData.homework(1L, status = HomeworkStatus.COMPLETED)
        val summaries = listOf(
            StatsCalculations.summarizeDay(
                epochDay = StatsTestData.DAY_20231114_EPOCH,
                items = listOf(item),
                sessions = listOf(
                    StatsTestData.session(
                        sessionId = 10L,
                        homeworkId = 1L,
                        startedAtMillis = StatsTestData.at(0),
                        finishedAtMillis = StatsTestData.at(10),
                        pauses = listOf(StatsTestData.pause(StatsTestData.at(1), StatsTestData.at(3))),
                    ),
                ),
                dayStartMillis = StatsTestData.DAY_20231114_START,
                dayEndMillis = StatsTestData.at(24 * 60),
                referenceMillis = StatsTestData.at(24 * 60),
            ),
            StatsCalculations.summarizeDay(
                epochDay = StatsTestData.DAY_20231114_EPOCH - 1,
                items = listOf(item.copy(status = HomeworkStatus.PENDING)),
                sessions = listOf(
                    StatsTestData.session(
                        sessionId = 11L,
                        homeworkId = 1L,
                        startedAtMillis = StatsTestData.at(-24 * 60),
                        finishedAtMillis = StatsTestData.at(-24 * 60 + 10),
                        pauses = listOf(
                            StatsTestData.pause(StatsTestData.at(-24 * 60 + 1), StatsTestData.at(-24 * 60 + 6)),
                        ),
                    ),
                ),
                dayStartMillis = StatsTestData.at(-24 * 60),
                dayEndMillis = StatsTestData.DAY_20231114_START,
                referenceMillis = StatsTestData.at(24 * 60),
            ),
        )
        val total = StatsCalculations.totalOf(summaries)
        assertEquals(2, total.totalCount)
        assertEquals(1, total.completedCount)
        assertEquals(2, total.pauseCount)
        assertEquals(7 * StatsTestData.MINUTE, total.pausedTotalMillis)
        assertEquals(StatsTestData.DAY_20231114_EPOCH, total.epochDay)
        assertEquals(5 * StatsTestData.MINUTE, total.mostPausedItem?.pausedMillis)
    }

    // ---- 时间换算与格式化 ----

    @Test
    fun `业务时区当日起点按纪元日与时区偏移换算`() {
        val epochDay = StatsTestData.DAY_20231114_EPOCH
        val offsetMillis = zone.rules
            .getOffset(LocalDate.ofEpochDay(epochDay).atTime(12, 0))
            .totalSeconds * 1_000
        assertEquals(
            StatsTestData.DAY_20231114_START,
            StatsCalculations.dayStartMillis(epochDay, offsetMillis),
        )
    }

    @Test
    fun `纪元日换算与展示文案往返一致`() {
        assertEquals(
            StatsTestData.DAY_20231114_EPOCH,
            StatsCalculations.epochDayOf(StatsTestData.DAY_20231114_START, OFFSET_MILLIS),
        )
        assertEquals("2023-11-14", StatsCalculations.dateText(StatsTestData.DAY_20231114_EPOCH))
    }

    // ---- 时区口径统一入口（dayStartMillisOf / epochDayOfToday） ----

    @Test
    fun `某日起点按该日正午偏移换算`() {
        assertEquals(
            StatsTestData.DAY_20231114_START,
            StatsCalculations.dayStartMillisOf(zone, StatsTestData.DAY_20231114_EPOCH),
        )
    }

    @Test
    fun `某日起点与纪元日换算互为逆运算`() {
        val epochDay = StatsTestData.DAY_20231114_EPOCH
        val start = StatsCalculations.dayStartMillisOf(zone, epochDay)
        assertEquals(epochDay, StatsCalculations.epochDayOfToday(zone, start))
        // 该日起点前 1 毫秒仍属前一天（半开区间语义）
        assertEquals(epochDay - 1, StatsCalculations.epochDayOfToday(zone, start - 1))
    }

    @Test
    fun `今天判定与某日起点共用同一时区换算`() {
        val epochDay = StatsTestData.DAY_20231114_EPOCH
        assertEquals(epochDay, StatsCalculations.epochDayOfToday(zone, StatsTestData.at(9)))
        // 「今天」的窗口起点回代后仍解析为同一天（两条路径不存在偏移漂移）
        assertEquals(
            epochDay,
            StatsCalculations.epochDayOfToday(zone, StatsCalculations.dayStartMillisOf(zone, epochDay)),
        )
    }

    @Test
    fun `时长文案按不足一分钟与小时分档`() {
        assertEquals("不到 1 分钟", StatsCalculations.durationText(0L))
        assertEquals("不到 1 分钟", StatsCalculations.durationText(59_000L))
        assertEquals("1 分钟", StatsCalculations.durationText(StatsTestData.MINUTE))
        assertEquals("59 分钟", StatsCalculations.durationText(59 * StatsTestData.MINUTE))
        assertEquals("1 小时", StatsCalculations.durationText(60 * StatsTestData.MINUTE))
        assertEquals("1 小时 30 分钟", StatsCalculations.durationText(90 * StatsTestData.MINUTE))
    }

    @Test
    fun `负时长收敛为不到一分钟`() {
        assertEquals("不到 1 分钟", StatsCalculations.durationText(-5_000L))
    }

    @Test
    fun `百分比文案保留一位小数并收敛到 0 到 100`() {
        assertEquals("0.0%", StatsCalculations.percentText(0.0))
        assertEquals("100.0%", StatsCalculations.percentText(1.0))
        assertEquals("62.5%", StatsCalculations.percentText(0.625))
        assertEquals("100.0%", StatsCalculations.percentText(1.5))
        assertEquals("0.0%", StatsCalculations.percentText(-0.5))
    }

    @Test
    fun `分钟文案复用时长文案口径`() {
        assertEquals("30 分钟", StatsCalculations.minutesText(30L))
        assertEquals("不到 1 分钟", StatsCalculations.minutesText(0L))
    }

    // ---- 预估时长展示口径（P1 回归看护） ----

    @Test
    fun `预估时长文案按分钟口径换算且未设定时为 null`() {
        // P1 回归看护：预估时长以「分钟」落库，展示必须走 minutesText(minutes)；
        // 若误传毫秒（再乘一次 MILLIS_PER_MINUTE）会把 30 分钟放大成 30000 小时。
        assertEquals("30 分钟", itemDetail(estimatedMinutes = 30).estimatedText)
        assertEquals("10 分钟", itemDetail(estimatedMinutes = 10).estimatedText)
        assertEquals("1 小时 30 分钟", itemDetail(estimatedMinutes = 90).estimatedText)
        assertNull(itemDetail(estimatedMinutes = null).estimatedText)
    }

    private fun itemDetail(estimatedMinutes: Int?): ItemDetail = ItemDetail(
        homeworkId = 1L,
        content = "数学口算",
        studentId = 2L,
        status = HomeworkStatus.COMPLETED,
        estimatedMinutes = estimatedMinutes,
        elapsedMillis = 30 * StatsTestData.MINUTE,
        pausedTotalMillis = 5 * StatsTestData.MINUTE,
        pauseCount = 1,
        sessionCount = 1,
        difficulty = DifficultyLevel.NORMAL,
        assessmentHint = "正常",
    )

    private companion object {

        /** 业务时区固定偏移（Asia/Shanghai 无夏令时，全年 +08:00） */
        const val OFFSET_MILLIS = 8 * 60 * 60 * 1000
    }
}