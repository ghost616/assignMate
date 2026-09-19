package com.assignmate.app.homework.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离朱补充探测（本轮 homework 审查修复）：**deadline 类型契约 + 单一入口校验**的时区口径与边界。
 *
 * 与既有套件的分工：`HomeworkTimeSetStageDeadlineTest` 覆盖「时间设定页 ViewModel 端到端」，
 * 本类只打领域层两个对外契约，用来锁定「消费方不再各自解释同一列」这件事本身：
 *
 * 1. [HomeworkItem.absoluteDeadlineAt]：当天作业与 epochDay 无关、阶段作业与该天每日时刻绑定；
 *    **时区必须是参与折算的入参**（同一 epochDay 在 UTC+8 与 UTC 下相差 8 小时），
 *    若实现退回 systemDefault 或 UTC 毫秒折算，本类即失败；
 *    **未设每日截止时刻时返回 null**（= 该天不产生约束，与计时侧取数同口径，不得再有非空兜底）。
 * 2. [HomeworkItem.stageStartEpochDayOr]：编码可还原时不得使用创建日兜底；
 *    不可还原时回退**注入时区口径**的创建日（同一创建瞬时在 UTC+8 / UTC 下回退到不同自然日）。
 * 3. [HomeworkValidators.validateScheduleWithinItemDeadline]：STAGE 按「该天每日截止时刻」、
 *    当天作业按绝对时刻；边界（恰好等于通过 / 超 1 分钟拦下 / 跨日时长拦下）与「未设每日时刻不产生约束」。
 */
class HomeworkDeadlineContractProbeTest {

    // ---- 1. absoluteDeadlineAt 契约 ----

    @Test
    fun `阶段作业 absoluteDeadlineAt 把当天每日时刻按注入时区折算为绝对瞬时`() {
        val item = stage(dailyTime = LocalTime.of(21, 0), startEpochDay = DAY)
        val day = DAY + 3

        val inShanghai = item.absoluteDeadlineAt(day, SHANGHAI)
        val inUtc = item.absoluteDeadlineAt(day, UTC)

        assertEquals(instantAt(day, LocalTime.of(21, 0), SHANGHAI), inShanghai)
        assertEquals(instantAt(day, LocalTime.of(21, 0), UTC), inUtc)
        assertEquals(
            "同一 epochDay + 同一钟面值在 UTC+8 与 UTC 下必须相差 8 小时：时区是入参而非写死",
            8L * 60L * 60L * 1000L,
            inUtc!!.toEpochMilli() - inShanghai!!.toEpochMilli(),
        )
        assertEquals(
            "返回瞬时落在请求的业务自然日",
            LocalDate.ofEpochDay(day),
            inShanghai.atZone(SHANGHAI).toLocalDate(),
        )
    }

    @Test
    fun `当天作业 absoluteDeadlineAt 与 epochDay 和时区都无关`() {
        val deadline = instantAt(DAY, LocalTime.of(18, 0), SHANGHAI)
        val item = today(deadline = deadline)

        assertEquals(deadline, item.absoluteDeadlineAt(DAY, SHANGHAI))
        assertEquals("任何一天都取同一绝对时刻", deadline, item.absoluteDeadlineAt(DAY + 365, SHANGHAI))
        assertEquals("同一绝对瞬时与查询时区无关", deadline, item.absoluteDeadlineAt(DAY, UTC))
        assertNull("无 deadline 的当天作业没有绝对截止约束", today(deadline = null).absoluteDeadlineAt(DAY, SHANGHAI))
    }

    @Test
    fun `阶段作业未设每日时刻时不产生截止取数（与计时侧同口径）`() {
        val item = stageWithDeadline(deadline = null)

        assertNull(item.dailyDeadlineTime)
        // 统一口径：缺每日截止时刻 = 该天不产生约束，取数必须为 null——
        // 历史上「折算为当天 23:59:59.999」的非空兜底会让取数侧与计时侧
        // （TimerCalculations.absoluteDeadlineMillisOf 的 null 口径）分叉，已删除。
        assertNull("未设每日时刻时该天没有绝对截止瞬时", item.absoluteDeadlineAt(DAY, SHANGHAI))
        assertNull("任何一天都取不到截止瞬时（不是某一天的当日结束）", item.absoluteDeadlineAt(DAY + 30, SHANGHAI))
        // 校验入口在每日时刻缺失时直接放行：23:59 开始 600 分钟（跨日）也不拦
        assertEquals(
            HomeworkValidation.Valid,
            validate(item, start = at(DAY, 23, 59), minutes = 600),
        )
    }

    @Test
    fun `阶段作业绝对截止时刻随查询日推进而推进`() {
        val item = stage(dailyTime = LocalTime.of(7, 5), startEpochDay = DAY)

        assertTrue(
            "不同自然日的每日截止瞬时必须不同",
            item.absoluteDeadlineAt(DAY + 1, SHANGHAI)!!.toEpochMilli() >
                item.absoluteDeadlineAt(DAY, SHANGHAI)!!.toEpochMilli(),
        )
        assertNotEquals(
            item.absoluteDeadlineAt(DAY, SHANGHAI),
            item.absoluteDeadlineAt(DAY + 1, SHANGHAI),
        )
    }

    // ---- 2. stageStartEpochDayOr 契约 ----

    @Test
    fun `编码可还原时起始日不退回创建日`() {
        // 起始日编码为 DAY，但作业实际创建于 DAY+5：必须以编码值为准（用户改过阶段范围会整体平移）
        val item = stage(
            dailyTime = LocalTime.of(21, 0),
            startEpochDay = DAY,
            createdEpochDay = DAY + 5,
        )

        assertEquals(DAY, item.stageStartEpochDay)
        assertEquals(DAY, item.stageStartEpochDayOr(SHANGHAI))
        assertEquals("可还原时与时区无关", DAY, item.stageStartEpochDayOr(UTC))
        assertEquals(DAY + 6L, item.stageLastEpochDay)
    }

    @Test
    fun `编码不可还原时按注入时区回退创建日`() {
        // 创建瞬时：业务时区 2023-11-15 00:30（+08:00）= 2023-11-14T16:30Z
        val createdAt = instantAt(DAY, LocalTime.of(0, 30), SHANGHAI)
        val item = stageWithDeadline(
            deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
            createdAt = createdAt,
        )

        assertNull("载体不承载起始日", item.stageStartEpochDay)
        assertEquals("UTC+8 口径的创建日", DAY, item.stageStartEpochDayOr(SHANGHAI))
        assertEquals(
            "UTC 口径的创建日是前一天：回退必须用注入时区（systemDefault/UTC 写死都会错）",
            DAY - 1L,
            item.stageStartEpochDayOr(UTC),
        )
    }

    @Test
    fun `脏值 deadline 的起始日与每日时刻都不可还原`() {
        val item = stageWithDeadline(
            deadline = Instant.ofEpochMilli(-1L),
            createdAt = instantAt(DAY, LocalTime.of(9, 0), SHANGHAI),
        )

        assertNull(item.stageStartEpochDay)
        assertNull("负值不得构造出非法/误导的每日时刻", item.dailyDeadlineTime)
        assertEquals(DAY, item.stageStartEpochDayOr(SHANGHAI))
    }

    @Test
    fun `非阶段作业不产生起始日与每日时刻`() {
        val item = today(deadline = instantAt(DAY, LocalTime.of(18, 0), SHANGHAI))

        assertNull(item.stageStartEpochDay)
        assertNull(item.stageStartEpochDayOr(SHANGHAI))
        assertNull(item.dailyDeadlineTime)
        assertNull(item.stageLastEpochDay)
    }

    // ---- 3. validateScheduleWithinItemDeadline：类型分流 + 边界 ----

    @Test
    fun `阶段作业按开始时刻所在自然日的每日截止时刻判定`() {
        val item = stage(dailyTime = LocalTime.of(21, 0), startEpochDay = DAY)

        assertValid(item, start = at(DAY, 20, 0), minutes = 60, reason = "20:00 + 60min 恰好等于 21:00")
        assertValid(item, start = at(DAY, 20, 59), minutes = 1, reason = "20:59 + 1min 恰好等于 21:00")
        assertExceeded(item, start = at(DAY, 21, 0), minutes = 1, reason = "正点再走 1 分钟即越限")
        assertExceeded(item, start = at(DAY, 20, 30), minutes = 60, reason = "20:30 + 60min = 21:30")

        // 次日（仍在阶段内）按「次日 21:00」判定，而不是沿用起始日的截止
        assertValid(item, start = at(DAY + 1, 20, 0), minutes = 60, reason = "次日 20:00 + 60min")
        assertExceeded(item, start = at(DAY + 1, 20, 1), minutes = 60, reason = "次日 20:01 + 60min")
    }

    @Test
    fun `阶段作业跨日时长按绝对瞬时拦下而非钟面比较`() {
        val item = stage(dailyTime = LocalTime.of(21, 0), startEpochDay = DAY)

        // 23:00 开始 120 分钟 -> 结束钟面 01:00（钟面比较会误判「早于 21:00」而放过）
        assertExceeded(item, start = at(DAY, 23, 0), minutes = 120, reason = "跨日时长必须按绝对瞬时拦下")
        assertExceeded(item, start = at(DAY + 2, 23, 0), minutes = 120, reason = "阶段内任意一天同理")
    }

    @Test
    fun `同一瞬时在不同业务时区下每日截止判定不同`() {
        val item = stage(dailyTime = LocalTime.of(21, 0), startEpochDay = DAY)
        // 该瞬时在 UTC+8 下是 DAY 20:00（+120min = 22:00 越限），在 UTC 下是 DAY 12:00（+120min = 14:00 未越限）
        val start = at(DAY, 20, 0)

        assertExceeded(item, start = start, minutes = 120, reason = "UTC+8 口径越限", zone = SHANGHAI)
        assertEquals(
            "同一瞬时换个业务时区即不再越限：时区必须参与判定",
            HomeworkValidation.Valid,
            validate(item, start = start, minutes = 120, zone = UTC),
        )
    }

    @Test
    fun `当天作业按绝对截止时刻判定且不随日界重置`() {
        val item = today(deadline = instantAt(DAY, LocalTime.of(18, 0), SHANGHAI))

        assertValid(item, start = at(DAY, 17, 0), minutes = 60, reason = "17:00 + 60min 恰好等于绝对 deadline")
        assertExceeded(item, start = at(DAY, 17, 1), minutes = 60, reason = "超出绝对 deadline 1 分钟")
        assertExceeded(item, start = at(DAY + 1, 9, 0), minutes = 30, reason = "次日仍受同一绝对时刻约束")
        assertValid(item, start = at(DAY - 1, 9, 0), minutes = 30, reason = "早于绝对时刻即通过")
    }

    @Test
    fun `当天作业无 deadline 时任何开始时刻都通过`() {
        val item = today(deadline = null)

        assertValid(item, start = at(DAY + 30, 23, 0), minutes = 600, reason = "无约束时任何时刻都通过")
    }

    // ---- 测试工具 ----

    private fun validate(
        item: HomeworkItem,
        start: Long,
        minutes: Int,
        zone: ZoneId = SHANGHAI,
    ): HomeworkValidation = HomeworkValidators.validateScheduleWithinItemDeadline(
        item = item,
        startMillis = start,
        estimatedMinutes = minutes,
        zoneId = zone,
    )

    private fun assertValid(
        item: HomeworkItem,
        start: Long,
        minutes: Int,
        reason: String = "应通过",
        zone: ZoneId = SHANGHAI,
    ) = assertEquals(reason, HomeworkValidation.Valid, validate(item, start, minutes, zone))

    private fun assertExceeded(
        item: HomeworkItem,
        start: Long,
        minutes: Int,
        reason: String = "应被拦下",
        zone: ZoneId = SHANGHAI,
    ) = assertEquals(
        reason,
        HomeworkValidation.Invalid(HomeworkValidationError.DEADLINE_EXCEEDED),
        validate(item, start, minutes, zone),
    )

    /** 某业务自然日的钟面时刻 -> epoch 毫秒 */
    private fun at(epochDay: Long, hour: Int, minute: Int, zone: ZoneId = SHANGHAI): Long =
        instantAt(epochDay, LocalTime.of(hour, minute), zone).toEpochMilli()

    private fun instantAt(epochDay: Long, time: LocalTime, zone: ZoneId): Instant =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, time, zone)

    private fun stage(
        dailyTime: LocalTime,
        startEpochDay: Long,
        createdEpochDay: Long = startEpochDay,
    ): HomeworkItem = stageWithDeadline(
        deadline = HomeworkDailyDeadlineCodec.encodeStageDaily(startEpochDay, dailyTime),
        createdAt = instantAt(createdEpochDay, LocalTime.of(9, 0), SHANGHAI),
    )

    private fun stageWithDeadline(
        deadline: Instant?,
        createdAt: Instant = instantAt(DAY, LocalTime.of(9, 0), SHANGHAI),
    ): HomeworkItem = HomeworkItem(
        id = STAGE_ID,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = deadline,
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.RECORDED,
        createdByRole = CreatorRole.PARENT,
        createdAt = createdAt,
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
        createdAt = instantAt(DAY, LocalTime.of(9, 0), SHANGHAI),
    )

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L
        const val STAGE_ID = 11L
        const val TODAY_ID = 12L

        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        val UTC: ZoneId = ZoneId.of("UTC")

        /** 业务自然日基准：2023-11-15（与既有套件一致） */
        val DAY: Long = LocalDate.of(2023, 11, 15).toEpochDay()
    }
}
