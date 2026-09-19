package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidation
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageDayRecords
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.data.timerTestHomework
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「阶段作业未设每日截止时刻」这一概念的**跨模块单一口径**固化（承接皐陶复审 warning B）。
 *
 * ## 统一口径（homework 与 timer 共用，不得出现第二种解释）
 * **未设每日截止时刻 = 取数返回 `null` = 该天不产生约束**：
 * - 取数：`StageDayRecords.dailyDeadlineOf` 是阶段侧**唯一底层入口**；
 *   `HomeworkItem.absoluteDeadlineAt`（homework 取数）与
 *   `TimerCalculations.absoluteDeadlineMillisOf`（timer 取数）都**委托**它，缺每日时刻一律 `null`；
 * - 校验：`HomeworkValidators.validateScheduleWithinItemDeadline` 遇空每日时刻**直接放行**
 *   （学生录入的阶段作业允许留空，故「23:00 开始 600 分钟」这类跨日排定是被放行的）；
 * - 计时：`TimerCalculations.isHomeworkOverdue` 在取数为 `null` 时不按截止时刻判超时。
 * 历史上的 `END_OF_DAY`（把缺省折算为当天 23:59:59.999）是**第二个口径**，已删除；
 * 若它被重新引入，「未设每日时刻」就不再是 `null`，本类的 null 断言与跨日断言会立刻失败。
 *
 * ## 本类要守住的真实意图
 * 同一「无每日时刻」的阶段作业，**校验侧（含跨日放行）与超时判定侧结论必须一致**——
 * 不得出现「一边放行、一边判超时」。覆盖起始日 23:01 与次日 00:05 两个跨日点。
 *
 * ## 与既有用例的分工
 * - 本类：**一致性**（两侧同结论 + 单一取数入口等价 + 无第二口径）；
 * - [TimerStageOverdueTest]：超时判定自身的边界三态（正点 / 刚过 1ms / 跨午夜重新判定）；
 * - homework 的 `HomeworkStageDeadlineCrossModuleTest`：端到端（真实仓库落库 + UI 预校验入口）。
 *
 * 依赖方向：本类仅**测试期**只读引用 homework 的校验/取数入口作为「校验侧解释」的实证。
 */
class TimerStageDeadlineSemanticsConsistencyTest {

    // ---- 1. 无每日截止时刻：校验侧与超时判定侧结论一致（不设排定时间） ----

    @Test
    fun `未设每日截止时刻时计时侧不判超时而校验侧放行`() {
        val item = stageWithoutDailyTime()

        assertNull("未设每日时刻 ⇒ deadline 列不承载每日时刻编码", item.dailyDeadlineTime)
        assertNull("取数唯一底层入口：该天没有绝对截止瞬时", StageDayRecords.dailyDeadlineOf(item, DAY, ZONE))
        assertNull("timer 取数（委托同一入口）同为 null", TimerCalculations.absoluteDeadlineMillisOf(item, DAY, ZONE))

        (0L until 3L).forEach { offset ->
            assertFalse(
                "第 $offset 天的 23:30：无每日时刻 ⇒ 该天无约束，不得判超时",
                overdueAt(item, at(DAY + offset, 23, 30)),
            )
        }

        assertEquals(
            "校验侧（UI 预校验 / 仓库同源判据）放行＝该天无约束",
            HomeworkValidation.Valid,
            HomeworkValidators.validateScheduleWithinItemDeadline(
                item = item,
                startMillis = at(DAY, 23, 1),
                estimatedMinutes = 60,
                zoneId = ZONE,
            ),
        )
    }

    // ---- 2. 跨日点 起始日 23:01 / 次日 00:05：一边放行一边判超时的分叉必须消失 ----

    @Test
    fun `无每日时刻的阶段作业跨日排定在起始日 23 点 01 与次日 00 点 05 都不判超时`() {
        val startMillis = at(DAY, 23, 1)
        val estimatedMinutes = 600
        val item = stageWithoutDailyTime(
            startTimeMillis = startMillis,
            estimatedMinutes = estimatedMinutes,
        )

        assertEquals(
            "① 校验侧放行「23:01 开始 600 分钟」（次日 09:00 收尾）",
            HomeworkValidation.Valid,
            HomeworkValidators.validateScheduleWithinItemDeadline(item, startMillis, estimatedMinutes, ZONE),
        )
        assertFalse("② 起始日 23:01：预估完成时刻未到，不得判超时", overdueAt(item, startMillis))
        assertFalse(
            "③ 次日 00:05：校验侧已放行的跨日排定，计时侧不得判逆期（同口径，不一边放一边罚）",
            overdueAt(item, at(DAY + 1, 0, 5)),
        )
        assertFalse(
            "④ 次日 23:30：无每日时刻 ⇒ 次日同样无约束（不是「次日 00:00 起即超时」）",
            overdueAt(item, at(DAY + 1, 23, 30)),
        )
    }

    // ---- 3. 取数唯一入口：homework 取数与 timer 取数逐例等价 ----

    @Test
    fun `绝对截止时刻取数与 homework 取数委托同一入口`() {
        val withoutDaily = stageWithoutDailyTime()
        assertNull("无每日时刻：homework 取数", withoutDaily.absoluteDeadlineAt(DAY + 4, ZONE))
        assertNull("无每日时刻：timer 取数", TimerCalculations.absoluteDeadlineMillisOf(withoutDaily, DAY + 4, ZONE))

        val withDaily = stageWithDailyTime()
        (0L until 5L).forEach { offset ->
            val day = DAY + offset
            assertEquals(
                "设了每日时刻：两侧逐例等价（day=$day）",
                withDaily.absoluteDeadlineAt(day, ZONE)?.toEpochMilli(),
                TimerCalculations.absoluteDeadlineMillisOf(withDaily, day, ZONE),
            )
        }
    }

    // ---- 4. 反向对照：不得把缺省折算成「当天结束」（END_OF_DAY 第二口径） ----

    @Test
    fun `未设每日截止时刻不得被折算成当天结束`() {
        val item = stageWithoutDailyTime()
        val endOfDay = at(DAY, 23, 59) + 59_999L

        assertNotEquals(
            "历史 END_OF_DAY 兜底会给出非空结果（第二个口径）——本轮已删除",
            endOfDay,
            TimerCalculations.absoluteDeadlineMillisOf(item, DAY, ZONE),
        )
        assertNull(
            "口径必须是「无约束的 null」，而非「当天 23:59:59.999」",
            StageDayRecords.dailyDeadlineOf(item, DAY, ZONE),
        )
        assertFalse("缺省即无约束：当天最后一刻也不判超时", overdueAt(item, endOfDay))
        assertNull("任何一天都取不到截止瞬时（不是某一天的当日结束）", item.absoluteDeadlineAt(DAY + 30, ZONE))
    }

    // ---- 5. 反向对照：设了每日时刻的阶段作业两侧仍按到点判定（放行只针对「未设」） ----

    @Test
    fun `设了每日截止时刻的阶段作业在校验侧与超时判定侧都按到点生效`() {
        val item = stageWithDailyTime()

        assertFalse("到点前不判逾期", overdueAt(item, at(DAY, 21, 0) - 1L))
        assertFalse("正点等于截止时刻不算超时（与既有边界口径一致）", overdueAt(item, at(DAY, 21, 0)))
        assertTrue("刚过 1ms 即判逾期（仅作提醒/逾期标识，不锁定完成）", overdueAt(item, at(DAY, 21, 0) + 1L))

        assertEquals(
            "校验侧同样以「开始时刻所在自然日」的每日时刻为准：20:00 开始 30 分钟（20:30 < 21:00）放行",
            HomeworkValidation.Valid,
            HomeworkValidators.validateScheduleWithinItemDeadline(item, at(DAY, 20, 0), 30, ZONE),
        )
        assertNotEquals(
            "校验侧同样会拦下跨过每日截止时刻的排定：20:30 开始 60 分钟",
            HomeworkValidation.Valid,
            HomeworkValidators.validateScheduleWithinItemDeadline(item, at(DAY, 20, 30), 60, ZONE),
        )
    }

    // ---- 测试辅助 ----

    /** 以业务时区判定某时刻是否超时（无会话：以「现在」为参考） */
    private fun overdueAt(item: HomeworkItem, nowMillis: Long): Boolean =
        TimerCalculations.isHomeworkOverdue(item, session = null, nowMillis = nowMillis, zoneId = ZONE)

    /** 学生可录入的「未设每日截止时刻」阶段作业（deadline 列为空 ⇒ 该天不产生约束） */
    private fun stageWithoutDailyTime(
        startTimeMillis: Long? = null,
        estimatedMinutes: Int? = null,
    ): HomeworkItem = timerTestHomework(
        id = STAGE_WITHOUT_DAILY_ID,
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        stageStartEpochDay = DAY,
        stageDailyTime = null,
        deadlineMillis = null,
        startTimeMillis = startTimeMillis,
        estimatedMinutes = estimatedMinutes,
    )

    /** 反向对照：设了每日截止时刻（21:00）的阶段作业 */
    private fun stageWithDailyTime(): HomeworkItem = timerTestHomework(
        id = STAGE_WITH_DAILY_ID,
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        stageStartEpochDay = DAY,
        stageDailyTime = LocalTime.of(21, 0),
    )

    /** 某业务自然日某钟面的绝对毫秒（业务时区口径，复用工程内唯一折算入口） */
    private fun at(epochDay: Long, hour: Int, minute: Int): Long =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, LocalTime.of(hour, minute), ZONE).toEpochMilli()

    private companion object Constants {

        const val STAGE_WITHOUT_DAILY_ID = 91L

        const val STAGE_WITH_DAILY_ID = 92L

        /** 业务时区：与 timer / homework 测试的注入口径一致 */
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 业务自然日基准：2023-11-15（与 timer 其余用例的固定时钟同一业务日） */
        val DAY: Long = LocalDate.of(2023, 11, 15).toEpochDay()
    }
}