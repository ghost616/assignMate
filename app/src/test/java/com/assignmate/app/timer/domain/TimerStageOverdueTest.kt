package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkDayState
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageDayRecords
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.data.timerTestHomework
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超时判定对**阶段作业「每日截止时刻」新语义**的适配单测（承接皋陶审查 error 2）。
 *
 * 背景：homework 已把 STAGE 的 `deadline` 改为「每日截止时刻」的载体（`HomeworkDailyDeadlineCodec`
 * 把「阶段起始日 + 当日时刻」编码进原列），该列的裸数值对阶段作业**不是绝对时间戳**。
 * 修复前 `isHomeworkOverdue` 直接按毫秒比较该列，使阶段作业在起始日之后（编码值对应的瞬时之后）恒判超时。
 *
 * 覆盖：
 * 1. 阶段作业在起始日、起始日+1、起始日+2 与阶段末日**到点前一律不超时**；
 * 2. 边界口径：**截止时刻正点不算超时**、刚过 1ms 即算、**跨午夜按新一天重新判定**（不恒判超时）；
 * 3. 到点后仅作逾期/提醒标识；「当天是否算未完成（缺卡）」由 homework 的 [StageDayRecords] 投影表达（本函数不锁定）；
 * 4. 阶段作业的排定时间段只约束它自己那一天（否则会被一个过去的排定段恒判超时）；
 * 5. **TODAY 绝对「日期 + 时刻」语义不变**（含 deadline 跨日、正点边界、无 deadline、预估口径）；
 * 6. 「绝对截止时刻」取数唯一入口 [TimerCalculations.absoluteDeadlineMillisOf] 的类型分流。
 *
 * **反向验证能力**：第 1 组的「起始日+2 / 阶段末日到点前」、第 2 组的「刚过截止」与「跨午夜前」、
 * 第 4 组以及第 6 组的「不得等于裸值」断言都依赖「按类型分流取数」——若修复被回退成
 * 直接比较 `item.deadline.toEpochMilli()`（编码载体 ≈ 起始日+1 的 21:00 UTC），这些用例即会失败。
 */
class TimerStageOverdueTest {

    // ---- 1. 阶段作业：任何一天到点前都不得判超时 ----

    @Test
    fun `阶段作业在起始日与后续各天到点前都不判超时`() {
        val item = stageItem(startEpochDay = TODAY)

        (0L until 7L).forEach { offset ->
            val day = TODAY + offset
            assertFalse(
                "起始日+$offset 的 10:00 未到每日截止时刻（21:00），不得判超时",
                overdueAt(item, at(day, 10, 0)),
            )
        }
    }

    @Test
    fun `阶段作业在阶段末日到点前不超时到点后仅作逾期标识`() {
        val item = stageItem(startEpochDay = TODAY)
        val lastDay = TODAY + 6

        assertFalse(overdueAt(item, at(lastDay, 20, 0)))
        assertTrue("到点后给出逾期标识（不代表锁定或缺卡）", overdueAt(item, at(lastDay, 22, 0)))
    }

    // ---- 2. 边界：正点、刚过截止、跨午夜 ----

    @Test
    fun `阶段作业截止时刻正点不算超时刚过一毫秒即算`() {
        val item = stageItem(startEpochDay = TODAY)
        val deadline = at(TODAY, 21, 0)

        assertFalse("20:59:59.999 未到点", overdueAt(item, deadline - 1))
        assertFalse("正点等于截止时刻，不算超时（与既有边界口径一致）", overdueAt(item, deadline))
        assertTrue("刚过 1ms 即判逾期", overdueAt(item, deadline + 1))
    }

    @Test
    fun `阶段作业跨午夜后按新一天截止时刻重新判定不恒判超时`() {
        val item = stageItem(startEpochDay = TODAY)

        assertTrue("当天 23:50 已过 21:00 截止 → 逾期标识", overdueAt(item, at(TODAY, 23, 50)))
        assertFalse(
            "跨入次日 00:10 后按新一天的 21:00 截止判定 → 不超时（不恒判超时）",
            overdueAt(item, at(TODAY + 1, 0, 10)),
        )
        assertTrue("次日到点后再次给出逾期标识", overdueAt(item, at(TODAY + 1, 21, 0) + 1))

        // 「跨日才判未完成」由 homework 的投影表达：已过去且未完成的天才是缺卡，当天仍可完成
        assertEquals(
            HomeworkDayState.MISSED,
            StageDayRecords.stateOf(TODAY, TODAY, 7, TODAY + 1, isCompleted = false),
        )
        assertEquals(
            HomeworkDayState.PENDING,
            StageDayRecords.stateOf(TODAY + 1, TODAY, 7, TODAY + 1, isCompleted = false),
        )
    }

    // ---- 3. 缺每日截止时刻 / 脏值 ----

    @Test
    fun `阶段作业缺每日截止时刻时不按截止时刻判超时`() {
        val item = timerTestHomework(
            id = 9L,
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            stageStartEpochDay = TODAY,
            stageDailyTime = null,
        )

        assertFalse(
            "无每日时刻 → 不判超时（也不得把裸 deadline 当绝对时刻）",
            overdueAt(item, at(TODAY, 23, 0)),
        )
    }

    @Test
    fun `阶段作业脏 deadline 不崩溃`() {
        val dirty = timerTestHomework(
            id = 9L,
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            stageStartEpochDay = TODAY,
            deadlineMillis = 0L,
        )

        assertTrue(
            "脏值按既有容错口径处理，绝不抛异常",
            runCatching { overdueAt(dirty, at(TODAY, 10, 0)) }.isSuccess,
        )
    }

    // ---- 4. 排定时间段只约束它自己那一天（阶段作业） ----

    @Test
    fun `阶段作业的排定时间段只约束它自己那一天`() {
        val item = stageItem(
            startEpochDay = TODAY,
            startTimeMillis = at(TODAY, 18, 0),
            estimatedMinutes = 30,
        )

        assertTrue("当天排定时间段已过（18:00 + 30 分钟）→ 逾期", overdueAt(item, at(TODAY, 18, 31)))
        assertFalse(
            "次日同一钟点前，昨天的排定时间段不再参与判定（否则会恒判超时）",
            overdueAt(item, at(TODAY + 1, 10, 0)),
        )
        assertTrue("次日仍按新一天的每日截止时刻判定", overdueAt(item, at(TODAY + 1, 21, 0) + 1))
    }

    // ---- 5. 已完成会话以结束时刻所在业务自然日判定 ----

    @Test
    fun `已完成会话以结束时刻所在业务自然日判定`() {
        val item = stageItem(startEpochDay = TODAY)
        val startedAt = at(TODAY, 9, 0)

        assertFalse(
            "次日 10:00 结束（未到点）→ 不超时",
            TimerCalculations.isHomeworkOverdue(
                item,
                finishedSession(startedAt, at(TODAY + 1, 10, 0)),
                at(TODAY + 3, 12, 0),
                ZONE,
            ),
        )
        assertTrue(
            "次日 22:00 结束（已过点）→ 超时",
            TimerCalculations.isHomeworkOverdue(
                item,
                finishedSession(startedAt, at(TODAY + 1, 22, 0)),
                at(TODAY + 3, 12, 0),
                ZONE,
            ),
        )
    }

    // ---- 6. TODAY 语义不变（回归） ----

    @Test
    fun `当天作业保持绝对截止时刻语义`() {
        val deadline = at(TODAY, 12, 0)
        val item = todayItem(deadlineMillis = deadline)

        assertFalse(overdueAt(item, deadline - 1))
        assertFalse("正点不算超时", overdueAt(item, deadline))
        assertTrue("刚过 1ms 即算", overdueAt(item, deadline + 1))
    }

    @Test
    fun `当天作业的绝对截止时刻跨日时今天不判超时`() {
        val item = todayItem(deadlineMillis = at(TODAY + 3, 12, 0))

        assertFalse(
            "TODAY 的 deadline 是绝对日期+时刻，不得被「当日化」",
            overdueAt(item, at(TODAY, 23, 0)),
        )
        assertTrue("过了绝对截止时刻即判超时", overdueAt(item, at(TODAY + 3, 12, 0) + 1))
    }

    @Test
    fun `当天作业无截止时刻时只保留预估时长口径`() {
        assertFalse(
            "既无 deadline 也未排定 → 不超时",
            overdueAt(todayItem(deadlineMillis = null), at(TODAY, 23, 0)),
        )
        assertTrue(
            "排定时间段已过 → 仍按预估口径判超时（该口径不变）",
            overdueAt(
                todayItem(
                    deadlineMillis = null,
                    startTimeMillis = at(TODAY, 9, 0),
                    estimatedMinutes = 30,
                ),
                at(TODAY, 10, 0),
            ),
        )
    }

    // ---- 7. 取数唯一入口的类型分流 ----

    @Test
    fun `绝对截止时刻取数按类型分流`() {
        val stage = stageItem(startEpochDay = TODAY)
        val today = todayItem(deadlineMillis = at(TODAY, 12, 0))

        assertEquals(
            "TODAY 保持原样取毫秒",
            at(TODAY, 12, 0),
            TimerCalculations.absoluteDeadlineMillisOf(today, TODAY, ZONE),
        )
        assertEquals(
            "STAGE 按「该天 + 每日截止时刻」折算",
            at(TODAY + 2, 21, 0),
            TimerCalculations.absoluteDeadlineMillisOf(stage, TODAY + 2, ZONE),
        )
        assertNotEquals(
            "STAGE 绝不可把 deadline 裸值当绝对时刻（本次修复的核心）",
            requireNotNull(stage.deadline).toEpochMilli(),
            TimerCalculations.absoluteDeadlineMillisOf(stage, TODAY + 2, ZONE),
        )
        assertEquals(
            "STAGE 缺每日时刻 → null（不判超时）",
            null,
            TimerCalculations.absoluteDeadlineMillisOf(
                timerTestHomework(
                    id = 9L,
                    type = HomeworkType.STAGE,
                    stageRange = StageRange.ONE_WEEK,
                    stageStartEpochDay = TODAY,
                    stageDailyTime = null,
                    deadlineMillis = null,
                ),
                TODAY,
                ZONE,
            ),
        )
    }

    // ---- 测试辅助 ----

    /** 以业务时区判定某时刻是否超时（无会话：以「现在」为参考） */
    private fun overdueAt(item: HomeworkItem, nowMillis: Long): Boolean =
        TimerCalculations.isHomeworkOverdue(item, session = null, nowMillis = nowMillis, zoneId = ZONE)

    /** 阶段作业（默认：自 [startEpochDay] 起一周、每日 21:00 截止、未排定开始时间） */
    private fun stageItem(
        startEpochDay: Long,
        range: StageRange = StageRange.ONE_WEEK,
        dailyTime: LocalTime = LocalTime.of(21, 0),
        startTimeMillis: Long? = null,
        estimatedMinutes: Int? = null,
        status: HomeworkStatus = HomeworkStatus.PENDING,
    ): HomeworkItem = timerTestHomework(
        id = 9L,
        status = status,
        type = HomeworkType.STAGE,
        stageRange = range,
        stageStartEpochDay = startEpochDay,
        stageDailyTime = dailyTime,
        startTimeMillis = startTimeMillis,
        estimatedMinutes = estimatedMinutes,
    )

    /** 当天作业（deadline 为绝对时刻；[deadlineMillis] 为 null 表示无截止约束） */
    private fun todayItem(
        deadlineMillis: Long?,
        startTimeMillis: Long? = null,
        estimatedMinutes: Int? = null,
    ): HomeworkItem = timerTestHomework(
        id = 1L,
        type = HomeworkType.TODAY,
        deadlineMillis = deadlineMillis,
        startTimeMillis = startTimeMillis,
        estimatedMinutes = estimatedMinutes,
    )

    /** 已收尾会话（[finishedAtMillis] 为结束时刻，即「这次作业当时是否超时」的参考） */
    private fun finishedSession(startedAtMillis: Long, finishedAtMillis: Long) = TimerSession(
        id = 1L,
        homeworkId = 9L,
        studentId = 2L,
        parentAccountId = 1L,
        startedAt = Instant.ofEpochMilli(startedAtMillis),
        finishedAt = Instant.ofEpochMilli(finishedAtMillis),
        phase = TimerPhase.FINISHED,
    )

    /** 某业务自然日某钟面的绝对毫秒（业务时区口径，复用 project 内唯一的折算入口） */
    private fun at(epochDay: Long, hour: Int, minute: Int): Long =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, LocalTime.of(hour, minute), ZONE).toEpochMilli()

    private companion object {

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 固定基准日（与 timer / homework 测试的基准时钟 1_700_000_000_000L 同一业务日） */
        val TODAY: Long = HomeworkDailyDeadlineCodec.absoluteEpochDay(1_700_000_000_000L, ZONE)
    }
}
