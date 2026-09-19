package com.assignmate.app.homework.domain

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.HomeworkTestEnv
import com.assignmate.app.homework.data.ScheduleUpdateResult
import com.assignmate.app.timer.domain.TimerCalculations
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「阶段作业未设每日截止时刻」+ **跨日排定**的跨模块端到端口径一致性（复审 info 项的收口）。
 *
 * 皐陶复审指出的同型跨模块分叉：学生录入的阶段作业允许无每日截止时刻
 * （[HomeworkValidators.validateTemplate] 只对 PARENT 强制「每日截止时刻」），
 * 此时「设时间」页曾放行任意时长（含 23:00 开始 600 分钟跳日），而取数侧若把缺省折算成
 * 「当天 23:59:59.999」（历史 `END_OF_DAY` 兜底），计时侧就会在**次日 00:00 起**判为逆期——
 * 一边放、一边罚。
 *
 * 现统一为**同一口径**：无每日截止时刻 = 该天不产生约束（取数为 null）。
 * 本类用真实仓库 + 真实每天详情替身 + timer 的只读取数/判定函数做端到端固化：
 * 1. 学生录入阶段作业（留空每日时刻）→ 跨日排定在「UI 预校验 / 仓库 / 计时侧」三处一致放行；
 * 2. 绝对截止时刻取数与计时侧唯一入口逐例等价（含未设每日时刻的 null 口径）；
 * 3. 反向对照：**设了**每日时刻的阶段作业到点后计时侧照常判逾期（说明放行只针对「未设」）。
 *
 * 依赖方向说明：本类在**测试期**只读引用 timer 的 `TimerCalculations`（作为「计时侧解释」的实证），
 * homework 生产代码不 import timer（该约束由 framework 的接线契约测试守住）。
 */
class HomeworkStageDeadlineCrossModuleTest {

    @Test
    fun `学生录入的阶段作业留空每日时刻时跨日排定在 UI 仓库与计时侧都是放行口径`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.setEpochDay(DAY, hour = 9, minute = 0)
        // 学生会话录入阶段作业，且**不设**每日截止时刻
        env.loginAsStudent(parentId, studentId)
        val added = env.repository.addHomework(
            HomeworkTemplate(
                content = "每天读课文",
                type = HomeworkType.STAGE,
                stageRange = StageRange.ONE_WEEK,
                deadline = null,
                creatorRole = CreatorRole.STUDENT,
                startEpochDay = env.todayEpochDay(),
                zoneId = ZONE,
            ),
            studentId,
        )
        val item = (added as AddHomeworkResult.Success).items.single()
        assertNull("学生录入的阶段作业允许留空每日时刻", item.dailyDeadlineTime)
        assertNull("留空即 deadline 列不承载每日时刻编码", item.deadline)

        // 23:00 开始 600 分钟 → 次日 09:00 结束（跨日排定）
        val start = instantAt(DAY, 23, 0)
        val estMinutes = 600

        // ① UI 侧（设时间页本地预校验走的就是这一入口）：放行
        assertEquals(
            "UI 预校验必须放行跨日排定（与仓库同源判据）",
            HomeworkValidation.Valid,
            HomeworkValidators.validateScheduleWithinItemDeadline(item, start.toEpochMilli(), estMinutes, ZONE),
        )

        // ② 仓库侧：放行并落库
        val result = env.repository.updateSchedule(item.id, start, estMinutes, Role.STUDENT)
        assertTrue("仓库必须放行跨日排定，实际：$result", result is ScheduleUpdateResult.Success)
        val stored = env.repository.getHomework(item.id)!!
        assertEquals("排定开始时刻落库", start, stored.startTime)
        assertEquals("预估时长落库", estMinutes, stored.estimatedMinutes)

        // ③ 计时侧：起始日不判超时；次日凌晨同样不判（不再「次日 00:00 起即判逆期」）
        assertFalse(
            "起始日 23:01 时预估完成时刻尚未到，不得判超时",
            TimerCalculations.isHomeworkOverdue(
                item = stored,
                session = null,
                nowMillis = start.toEpochMilli() + 60_000L,
                zoneId = ZONE,
            ),
        )
        assertFalse(
            "跨日排定在次日 00:05 不得被判逆期：计时侧与 UI/仓库的放行结论一致",
            TimerCalculations.isHomeworkOverdue(
                item = stored,
                session = null,
                nowMillis = instantAt(DAY + 1, 0, 5).toEpochMilli(),
                zoneId = ZONE,
            ),
        )

        // ④ 取数口径同源：无每日时刻 → 该天没有绝对截止瞬时（不再是「当天 23:59:59.999」）
        assertNull(
            "homework 取数（委托 StageDayRecords.dailyDeadlineOf）",
            stored.absoluteDeadlineAt(DAY + 1, ZONE),
        )
        assertNull(
            "计时侧取数（同一底层入口）",
            TimerCalculations.absoluteDeadlineMillisOf(stored, DAY + 1, ZONE),
        )
    }

    @Test
    fun `绝对截止时刻取数与计时侧唯一入口逐例等价`() {
        // 阶段作业（设了每日时刻）：同一天同一时区下两侧取数完全一致，且随查询日推进
        val stageWithDaily = stage(dailyTime = LocalTime.of(21, 0), startEpochDay = DAY)
        for (day in listOf(DAY, DAY + 1, DAY + 6)) {
            assertEquals(
                "阶段作业取数必须与计时侧一致（day=$day）",
                stageWithDaily.absoluteDeadlineAt(day, ZONE)!!.toEpochMilli(),
                TimerCalculations.absoluteDeadlineMillisOf(stageWithDaily, day, ZONE),
            )
        }

        // 阶段作业（未设每日时刻）：两侧同为 null（= 该天不产生约束，无第二口径）
        val stageWithoutDaily = stage(dailyTime = null, startEpochDay = DAY)
        assertNull(stageWithoutDaily.absoluteDeadlineAt(DAY + 2, ZONE))
        assertNull(TimerCalculations.absoluteDeadlineMillisOf(stageWithoutDaily, DAY + 2, ZONE))

        // 当天作业：任何一天都取同一个绝对时刻，两侧一致
        val deadline = instantAt(DAY, 18, 0)
        val todayItem = today(deadline)
        assertEquals(deadline, todayItem.absoluteDeadlineAt(DAY + 100, ZONE))
        assertEquals(
            deadline.toEpochMilli(),
            TimerCalculations.absoluteDeadlineMillisOf(todayItem, DAY + 100, ZONE),
        )
    }

    @Test
    fun `设了每日时刻的阶段作业到点后计时侧照常判逾期`() {
        // 反向对照：放行只针对「未设每日截止时刻」；设了时刻的仍按到点判定（仅作逾期标识、不锁定完成）
        val item = stage(dailyTime = LocalTime.of(21, 0), startEpochDay = DAY)

        assertFalse(
            "到点前不判逾期",
            TimerCalculations.isHomeworkOverdue(item, null, instantAt(DAY, 20, 59).toEpochMilli(), ZONE),
        )
        assertTrue(
            "到点后判逾期（仅标识，不锁完成）",
            TimerCalculations.isHomeworkOverdue(item, null, instantAt(DAY, 21, 1).toEpochMilli(), ZONE),
        )
    }

    // ---- 测试工具 ----

    private fun HomeworkTestEnv.todayEpochDay(): Long =
        HomeworkValidators.epochDayOf(clock.currentTimeMillis(), ZONE)

    private fun instantAt(epochDay: Long, hour: Int, minute: Int): Instant =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, LocalTime.of(hour, minute), ZONE)

    private fun stage(dailyTime: LocalTime?, startEpochDay: Long): HomeworkItem = HomeworkItem(
        id = STAGE_ID,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = dailyTime?.let { HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, it) },
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.RECORDED,
        createdByRole = CreatorRole.PARENT,
        createdAt = instantAt(startEpochDay, 9, 0),
    )

    private fun today(deadline: Instant?): HomeworkItem = HomeworkItem(
        id = TODAY_ID,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "数学练习册",
        type = HomeworkType.TODAY,
        stageRange = null,
        deadline = deadline,
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.RECORDED,
        createdByRole = CreatorRole.PARENT,
        createdAt = instantAt(DAY, 9, 0),
    )

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L
        const val STAGE_ID = 11L
        const val TODAY_ID = 12L

        /** 业务时区：与仓库/每天详情注入口径一致 */
        val ZONE: ZoneId = HomeworkTestEnv.ZONE

        /** 业务自然日基准：2023-11-15 */
        val DAY: Long = LocalDate.of(2023, 11, 15).toEpochDay()
    }
}