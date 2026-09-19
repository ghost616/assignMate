package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.data.timerTestHomework
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离朱独立探针：`TimerCalculations.absoluteDeadlineMillisOf` 的**分流取数边界**。
 *
 * 与被测方（`TimerStageDeadlineSemanticsConsistencyTest`）互补，只做既有用例未覆盖的侧面：
 * 1. 分流入口不可退化为「一律当绝对时刻」——阶段作业必须按业务自然日折算（TODAY 仍原样取）；
 * 2. **按天推进**必须是「自然日推进」而非「固定 24h 平移」：在夏令时切换日（当日仅 23 小时）
 *    的每日 23:00 截止瞬时差值必须等于当地该日的真实时长；
 * 3. 无每日截止时刻必须恒为 null（不得被折算成任何非空兜底）。
 *
 * 时区口径：显式传入业务时区，不依赖系统默认（跨时区/夏季时运行的稳定性）。
 */
class TimerStageProbeTest {

    @Test
    fun `无每日时刻的阶段作业任何一天都取不到截止瞬时`() {
        val item = timerTestHomework(
            id = 91L,
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            stageStartEpochDay = DAY,
            stageDailyTime = null,
            deadlineMillis = null,
        )

        assertNull("起始日本身不得给出截止瞬时", TimerCalculations.absoluteDeadlineMillisOf(item, DAY, ZONE))
        assertNull(
            "往后 7 天逐日都不得给出截止瞬时",
            (0L until 7L).map { TimerCalculations.absoluteDeadlineMillisOf(item, DAY + it, ZONE) }
                .firstOrNull { it != null },
        )
        assertNull(
            "往前一天同样不得给出截止瞬时（不是「只对未来兜底」）",
            TimerCalculations.absoluteDeadlineMillisOf(item, DAY - 1, ZONE),
        )
    }

    @Test
    fun `当天作业仍按绝对 deadline 原样取毫秒`() {
        val absolute = 1_700_000_000_000L
        val item = timerTestHomework(
            id = 92L,
            type = HomeworkType.TODAY,
            deadlineMillis = absolute,
        )

        assertEquals(
            "TODAY 分支不得被阶段口径污染：任何 epochDay 都原样取绝对毫秒",
            absolute,
            TimerCalculations.absoluteDeadlineMillisOf(item, DAY + 3, ZONE),
        )
    }

    @Test
    fun `阶段每日截止时刻按自然日推进而不是固定二十四小时平移`() {
        // 两个对照时区：America/New_York（2026-03-08 有夏令时切换）与 Asia/Shanghai（无夏令时）
        val dstStartDay = LocalDate.of(2026, 3, 8).toEpochDay()
        val item2 = timerTestHomework(
            id = 94L,
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            stageStartEpochDay = DAY,
            stageDailyTime = LocalTime.of(23, 0),
        )
        val item = timerTestHomework(
            id = 93L,
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            stageStartEpochDay = dstStartDay,
            stageDailyTime = LocalTime.of(23, 0),
        )

        val firstDay = TimerCalculations.absoluteDeadlineMillisOf(item, dstStartDay, DST_ZONE)
        val secondDay = TimerCalculations.absoluteDeadlineMillisOf(item, dstStartDay + 1L, DST_ZONE)

        assertTrue("两天的到点瞬时都必须可取得（设了每日时刻）", firstDay != null && secondDay != null)
        // 固定的「当地 23:00」跨夏令时切换日仍相隔 24 小时（真时长按瞬时计算，不随切换日变成 23/25 小时）
        assertEquals(
            "每日 23:00 的到点瞬时必须相隔一个自然日（24h），而不是从阶段起始日做固定 24h 平移",
            24L * 60L * 60L * 1000L,
            secondDay!! - firstDay!!,
        )
        // 无夏令时对照：连续三天的到点瞬时同样恒为 24h，且随 epochDay 逐日推进
        val c1 = TimerCalculations.absoluteDeadlineMillisOf(item2, DAY, ZONE)!!
        val c2 = TimerCalculations.absoluteDeadlineMillisOf(item2, DAY + 1L, ZONE)!!
        val c5 = TimerCalculations.absoluteDeadlineMillisOf(item2, DAY + 5L, ZONE)!!
        assertEquals("无夏令时地区：连续两天到点瞬时恒为 24h", 24L * 60L * 60L * 1000L, c2 - c1)
        assertEquals("按天推进会随 epochDay 线性推进（第 5 天 = 第 1 天 + 5 天）", 5L * 24L * 60L * 60L * 1000L, c5 - c1)
    }

    private companion object Constants {

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        val DST_ZONE: ZoneId = ZoneId.of("America/New_York")

        val DAY: Long = LocalDate.of(2023, 11, 15).toEpochDay()
    }
}