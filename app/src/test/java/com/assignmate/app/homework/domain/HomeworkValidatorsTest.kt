package com.assignmate.app.homework.domain

import com.assignmate.app.auth.domain.Role
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * homework 纯函数校验单测：deadline 约束、时间段防冲突、权限规则（改删权 + 执行权）、
 * 状态流转、录入模板校验与业务时间口径。
 *
 * 覆盖分支：deadline 边界（恰好等于截止时刻 / 超出 1 分钟）、时间区间相接与重叠、
 * 排除自身的自比对、学生/家长两个权限维度的矩阵、状态机合法与非法流转、
 * 阶段范围与 deadline 组合、epochDay 的时区折算。
 */
class HomeworkValidatorsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 基准时刻：2025-01-01T00:00:00+08:00 */
    private val baseMillis: Long = LocalDate.of(2025, 1, 1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    private fun millisOf(dayOffset: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.of(2025, 1, 1).plusDays(dayOffset)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun item(
        id: Long = 1L,
        content: String = "语文生字",
        type: HomeworkType = HomeworkType.TODAY,
        stageRange: StageRange? = null,
        deadline: Instant? = null,
        priority: Int = 0,
        startTime: Instant? = null,
        estimatedMinutes: Int? = null,
        status: HomeworkStatus = HomeworkStatus.RECORDED,
        createdByRole: CreatorRole = CreatorRole.PARENT,
    ): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = 1L,
        studentId = 10L,
        content = content,
        type = type,
        stageRange = stageRange,
        deadline = deadline,
        priority = priority,
        startTime = startTime,
        estimatedMinutes = estimatedMinutes,
        status = status,
        createdByRole = createdByRole,
        createdAt = Instant.ofEpochMilli(baseMillis),
    )

    // ---- 1. deadline 约束 ----

    @Test
    fun `无截止时间时不做 deadline 约束`() {
        val result = HomeworkValidators.validateDeadline(
            startMillis = millisOf(0, 20),
            estimatedMinutes = 120,
            deadlineMillis = null,
        )
        assertEquals(HomeworkValidation.Valid, result)
    }

    @Test
    fun `开始时间加预估时长恰好等于截止时间时通过`() {
        val result = HomeworkValidators.validateDeadline(
            startMillis = millisOf(0, 19),
            estimatedMinutes = 60,
            deadlineMillis = millisOf(0, 20),
        )
        assertEquals(HomeworkValidation.Valid, result)
    }

    @Test
    fun `开始时间加预估时长超过截止时间被拦截`() {
        val result = HomeworkValidators.validateDeadline(
            startMillis = millisOf(0, 19),
            estimatedMinutes = 61,
            deadlineMillis = millisOf(0, 20),
        )
        assertEquals(
            HomeworkValidation.Invalid(HomeworkValidationError.DEADLINE_EXCEEDED),
            result,
        )
    }

    // ---- 2. 时间段防冲突 ----

    @Test
    fun `两个时间段重叠被判定冲突`() {
        val existing = listOf(
            item(
                id = 1L,
                startTime = Instant.ofEpochMilli(millisOf(0, 9)),
                estimatedMinutes = 60,
            ),
        )
        val candidate = HomeworkTimeSlot.of(millisOf(0, 9, 30), 30)

        assertEquals(
            HomeworkValidation.Invalid(HomeworkValidationError.TIME_CONFLICT),
            HomeworkValidators.validateTimeSlot(candidate, existing),
        )
    }

    @Test
    fun `前一段结束时刻等于后一段开始时刻不算冲突`() {
        val existing = listOf(
            item(
                id = 1L,
                startTime = Instant.ofEpochMilli(millisOf(0, 9)),
                estimatedMinutes = 60,
            ),
        )
        // 候选 10:00-10:30，与既有 09:00-10:00 端点相接
        val candidate = HomeworkTimeSlot.of(millisOf(0, 10), 30)

        assertEquals(HomeworkValidation.Valid, HomeworkValidators.validateTimeSlot(candidate, existing))
    }

    @Test
    fun `未排定开始时间的作业不参与冲突判定`() {
        val existing = listOf(item(id = 1L, startTime = null, estimatedMinutes = 60))
        val candidate = HomeworkTimeSlot.of(millisOf(0, 9), 60)

        assertEquals(HomeworkValidation.Valid, HomeworkValidators.validateTimeSlot(candidate, existing))
    }

    @Test
    fun `排除自身后修改时间不与原时间段冲突`() {
        val self = item(
            id = 7L,
            startTime = Instant.ofEpochMilli(millisOf(0, 16)),
            estimatedMinutes = 30,
            status = HomeworkStatus.PENDING,
        )
        val candidate = HomeworkTimeSlot.of(millisOf(0, 16, 10), 20)

        assertEquals(
            HomeworkValidation.Invalid(HomeworkValidationError.TIME_CONFLICT),
            HomeworkValidators.validateTimeSlot(candidate, listOf(self)),
        )
        assertEquals(
            HomeworkValidation.Valid,
            HomeworkValidators.validateTimeSlot(candidate, listOf(self), excludeItemId = 7L),
        )
    }

    @Test
    fun `缺失预估时长的既有作业按一分钟占用判定`() {
        val existing = listOf(
            item(
                id = 1L,
                startTime = Instant.ofEpochMilli(millisOf(0, 9)),
                estimatedMinutes = null,
            ),
        )
        // 09:00 整点开始的一分钟占用会与 09:00-09:10 冲突
        val candidate = HomeworkTimeSlot.of(millisOf(0, 9), 10)
        assertEquals(
            HomeworkValidation.Invalid(HomeworkValidationError.TIME_CONFLICT),
            HomeworkValidators.validateTimeSlot(candidate, existing),
        )
        // 09:10 起不再重叠
        val later = HomeworkTimeSlot.of(millisOf(0, 9, 10), 10)
        assertEquals(HomeworkValidation.Valid, HomeworkValidators.validateTimeSlot(later, existing))
    }

    // ---- 3. 权限规则 ----

    @Test
    fun `家长可修改与删除任何录入来源的作业`() {
        val parentItem = item(createdByRole = CreatorRole.PARENT)
        val studentItem = item(id = 2L, createdByRole = CreatorRole.STUDENT)

        assertTrue(HomeworkValidators.canModify(parentItem, Role.PARENT))
        assertTrue(HomeworkValidators.canModify(studentItem, Role.PARENT))
        assertTrue(HomeworkValidators.canDelete(parentItem, Role.PARENT))
        assertTrue(HomeworkValidators.canDelete(studentItem, Role.PARENT))
    }

    @Test
    fun `学生仅可修改删除自己录入的作业`() {
        val parentItem = item(createdByRole = CreatorRole.PARENT)
        val ownItem = item(id = 2L, createdByRole = CreatorRole.STUDENT)

        assertFalse(HomeworkValidators.canModify(parentItem, Role.STUDENT))
        assertFalse(HomeworkValidators.canDelete(parentItem, Role.STUDENT))
        assertFalse(HomeworkValidators.canReorder(parentItem, Role.STUDENT, sessionStudentId = 10L))
        assertTrue(HomeworkValidators.canModify(ownItem, Role.STUDENT))
        assertTrue(HomeworkValidators.canDelete(ownItem, Role.STUDENT))
    }

    @Test
    fun `调序权在改删权之上补作业归属维度`() {
        // 作业归属学生 10；会话学生 99 时，即使作业由学生录入也不可调序（跨学生越权面）
        val ownRecorded = item(createdByRole = CreatorRole.STUDENT)
        val parentRecorded = item(id = 2L, createdByRole = CreatorRole.PARENT)

        assertTrue(HomeworkValidators.canReorder(ownRecorded, Role.STUDENT, sessionStudentId = 10L))
        assertFalse(
            "家长录入项即使在自己名下也不可调序（改删权维度仍然生效）",
            HomeworkValidators.canReorder(parentRecorded, Role.STUDENT, sessionStudentId = 10L),
        )
        assertFalse(
            "他人名下的学生录入项不可调序（改删权只看录入者角色，故必须补归属维度）",
            HomeworkValidators.canReorder(ownRecorded, Role.STUDENT, sessionStudentId = 99L),
        )
        assertFalse(
            "学生会话缺少本人 id 时一律拒绝",
            HomeworkValidators.canReorder(ownRecorded, Role.STUDENT, sessionStudentId = null),
        )
        // 家长不受归属维度影响（家长侧归属范围由仓库层统一归属判定圈定）
        assertTrue(HomeworkValidators.canReorder(ownRecorded, Role.PARENT, sessionStudentId = null))
        assertTrue(HomeworkValidators.canReorder(parentRecorded, Role.PARENT, sessionStudentId = 99L))
    }

    // ---- 3.1 执行权（时间排定与状态流转，与改删权刻意分离） ----

    @Test
    fun `家长可对名下学生的任何作业行使执行权`() {
        val parentItem = item(createdByRole = CreatorRole.PARENT)
        val studentItem = item(id = 2L, createdByRole = CreatorRole.STUDENT)

        // 家长归属范围由仓库层统一归属判定按会话圈定，此处只验证角色维度一律放行
        assertTrue(HomeworkValidators.canOperate(parentItem, Role.PARENT, sessionStudentId = null))
        assertTrue(HomeworkValidators.canOperate(studentItem, Role.PARENT, sessionStudentId = null))
    }

    @Test
    fun `学生可对本人名下作业行使执行权含家长布置的`() {
        val parentItem = item(createdByRole = CreatorRole.PARENT)
        val studentItem = item(id = 2L, createdByRole = CreatorRole.STUDENT)

        // 关键：家长布置的作业也必须可排定时间/开始计时/标记完成，否则主闭环不可用
        assertTrue(HomeworkValidators.canOperate(parentItem, Role.STUDENT, sessionStudentId = 10L))
        assertTrue(HomeworkValidators.canOperate(studentItem, Role.STUDENT, sessionStudentId = 10L))
    }

    @Test
    fun `学生不可对他人名下作业行使执行权`() {
        val parentItem = item(createdByRole = CreatorRole.PARENT)
        val studentItem = item(id = 2L, createdByRole = CreatorRole.STUDENT)

        // 作业归属学生 10，会话学生为 99：不得操作（与录入者角色无关）
        assertFalse(HomeworkValidators.canOperate(parentItem, Role.STUDENT, sessionStudentId = 99L))
        assertFalse(HomeworkValidators.canOperate(studentItem, Role.STUDENT, sessionStudentId = 99L))
    }

    @Test
    fun `学生会话缺少本人id时执行权一律拒绝`() {
        val studentItem = item(createdByRole = CreatorRole.STUDENT)

        assertFalse(HomeworkValidators.canOperate(studentItem, Role.STUDENT, sessionStudentId = null))
    }

    @Test
    fun `改删权与执行权对同一条作业可给出不同结论`() {
        val parentItem = item(createdByRole = CreatorRole.PARENT)

        // 家长录入的作业：学生不能改删，但可以排定时间与推进状态
        assertFalse(HomeworkValidators.canModify(parentItem, Role.STUDENT))
        assertFalse(HomeworkValidators.canDelete(parentItem, Role.STUDENT))
        assertTrue(HomeworkValidators.canOperate(parentItem, Role.STUDENT, sessionStudentId = 10L))
    }

    // ---- 4. 状态流转 ----

    @Test
    fun `已记录仅可流转到待完成`() {
        assertTrue(HomeworkValidators.canTransition(HomeworkStatus.RECORDED, HomeworkStatus.PENDING))
        assertFalse(
            HomeworkValidators.canTransition(HomeworkStatus.RECORDED, HomeworkStatus.IN_PROGRESS),
        )
        assertFalse(
            HomeworkValidators.canTransition(HomeworkStatus.RECORDED, HomeworkStatus.COMPLETED),
        )
        assertEquals(
            HomeworkValidation.Invalid(HomeworkValidationError.ILLEGAL_STATUS_TRANSITION),
            HomeworkValidators.validateStatusTransition(
                HomeworkStatus.RECORDED,
                HomeworkStatus.COMPLETED,
            ),
        )
    }

    @Test
    fun `待完成可开始计时也可直接完成`() {
        assertTrue(HomeworkValidators.canTransition(HomeworkStatus.PENDING, HomeworkStatus.IN_PROGRESS))
        assertTrue(HomeworkValidators.canTransition(HomeworkStatus.PENDING, HomeworkStatus.COMPLETED))
        assertTrue(HomeworkValidators.canTransition(HomeworkStatus.PENDING, HomeworkStatus.RECORDED))
    }

    @Test
    fun `已完成仅可回退为进行中且幂等流转视为合法`() {
        assertTrue(HomeworkValidators.canTransition(HomeworkStatus.COMPLETED, HomeworkStatus.IN_PROGRESS))
        assertFalse(HomeworkValidators.canTransition(HomeworkStatus.COMPLETED, HomeworkStatus.PENDING))
        // 幂等：同一状态视为合法，便于「重复点击完成」不报错
        assertTrue(HomeworkValidators.canTransition(HomeworkStatus.COMPLETED, HomeworkStatus.COMPLETED))
    }

    // ---- 5. 录入模板校验 ----

    @Test
    fun `家长录入空白内容被拦截`() {
        val template = HomeworkTemplate(
            content = "   ",
            type = HomeworkType.TODAY,
            creatorRole = CreatorRole.PARENT,
            startEpochDay = 0L,
            zoneId = zone,
        )
        val error = HomeworkValidators.validateTemplate(template).exceptionOrNull()
        assertEquals(
            HomeworkValidationError.BLANK_CONTENT,
            (error as HomeworkValidationException).error,
        )
    }

    @Test
    fun `学生录入允许内容为空`() {
        val template = HomeworkTemplate(
            content = "",
            type = HomeworkType.TODAY,
            creatorRole = CreatorRole.STUDENT,
            startEpochDay = 0L,
            zoneId = zone,
        )
        assertTrue(HomeworkValidators.validateTemplate(template).isSuccess)
    }

    @Test
    fun `内容超长被拦截`() {
        val template = HomeworkTemplate(
            content = "长".repeat(HomeworkConstants.MAX_CONTENT_LENGTH + 1),
            type = HomeworkType.TODAY,
            creatorRole = CreatorRole.PARENT,
            startEpochDay = 0L,
            zoneId = zone,
        )
        val error = HomeworkValidators.validateTemplate(template).exceptionOrNull()
        assertEquals(
            HomeworkValidationError.CONTENT_TOO_LONG,
            (error as HomeworkValidationException).error,
        )
    }

    @Test
    fun `阶段作业未选阶段范围被拦截`() {
        val template = HomeworkTemplate(
            content = "每天读课文",
            type = HomeworkType.STAGE,
            stageRange = null,
            creatorRole = CreatorRole.PARENT,
            startEpochDay = 0L,
            zoneId = zone,
        )
        val error = HomeworkValidators.validateTemplate(template).exceptionOrNull()
        assertEquals(
            HomeworkValidationError.MISSING_STAGE_RANGE,
            (error as HomeworkValidationException).error,
        )
    }

    @Test
    fun `家长录入阶段作业未设每日截止时刻被拦截`() {
        val template = HomeworkTemplate(
            content = "每天读课文",
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = null,
            creatorRole = CreatorRole.PARENT,
            startEpochDay = 0L,
            zoneId = zone,
        )
        val error = HomeworkValidators.validateTemplate(template).exceptionOrNull()
        assertEquals(
            HomeworkValidationError.MISSING_STAGE_DEADLINE,
            (error as HomeworkValidationException).error,
        )
        // 只填时刻（每日到点截止）即通过——不再要求「阶段覆盖末日 ≤ deadline 所在日」
        val withTime = template.copy(
            deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
        )
        assertTrue(HomeworkValidators.validateTemplate(withTime).isSuccess)

        val items = withTime.toItems(
            parentAccountId = 1L,
            studentId = 10L,
            createdAt = Instant.ofEpochMilli(baseMillis),
            firstPriority = 0,
        )
        assertEquals(1, items.size)
        assertEquals(LocalTime.of(21, 0), items.single().dailyDeadlineTime)
    }

    @Test
    fun `阶段截止时间只表达每日时刻不再有覆盖末日约束`() {
        val startEpochDay = LocalDate.of(2025, 1, 1).toEpochDay()
        // 旧规则（阶段覆盖最后一天不得晚于 deadline 所在日）已推翻：
        // 同一天开始的一周阶段 + 每日 21:00 截止，覆盖跨到 1/7 也应通过
        val template = HomeworkTemplate(
            content = "每天读课文",
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
            creatorRole = CreatorRole.PARENT,
            startEpochDay = startEpochDay,
            zoneId = zone,
        )
        assertTrue(HomeworkValidators.validateTemplate(template).isSuccess)
    }

    @Test
    fun `当天开始加预估时长不得超过当天每日截止时刻`() {
        val day = LocalDate.of(2025, 1, 1)
        val cases = listOf(
            Triple(20, 60, 21 to 0), // 20:00 + 60min = 21:00，正好等于截止时刻 → 通过
            Triple(20, 61, 21 to 0), // 20:00 + 61min 跨过 21:00 → 拦截
            Triple(21, 0, 21 to 0), // 21:00 开始且零时长（按 0 分钟算）→ 通过
        )
        cases.forEach { (hour, minutes, deadline) ->
            val start = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            val result = HomeworkValidators.validateScheduleWithinDailyDeadline(
                startMillis = start,
                estimatedMinutes = minutes,
                dailyDeadlineTime = LocalTime.of(deadline.first, deadline.second),
                zoneId = zone,
            )
            val expected = minutes <= 60
            assertEquals("$hour:$minutes 的每日时刻约束", expected, result is HomeworkValidation.Valid)
        }
    }

    @Test
    fun `每日截止时刻为空或无时刻时不受约束`() {
        val start = LocalDate.of(2025, 1, 1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            HomeworkValidation.Valid,
            HomeworkValidators.validateScheduleWithinDailyDeadline(
                startMillis = start,
                estimatedMinutes = 120,
                dailyDeadlineTime = null,
                zoneId = zone,
            ),
        )
    }

    @Test
    fun `阶段作业带每日截止时刻时通过且固定产出一条`() {
        val startEpochDay = LocalDate.of(2025, 1, 1).toEpochDay()
        val template = HomeworkTemplate(
            content = "每天读课文",
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
            creatorRole = CreatorRole.PARENT,
            startEpochDay = startEpochDay,
            zoneId = zone,
        )
        val validated = HomeworkValidators.validateTemplate(template).getOrNull()
        assertEquals(template, validated?.template)

        val items = template.toItems(
            parentAccountId = 1L,
            studentId = 10L,
            createdAt = Instant.ofEpochMilli(baseMillis),
            firstPriority = 3,
        )
        // 阶段作业 = 一个在阶段内需完成的作业项：固定 1 条，优先级即首条优先级
        assertEquals(1, items.size)
        assertEquals(listOf(3), items.map { it.priority })
        assertTrue(items.all { it.status == HomeworkStatus.RECORDED })
        assertTrue(items.all { it.startTime == null })
        assertEquals(startEpochDay, items.single().stageStartEpochDay)
        assertEquals(startEpochDay + 6L, items.single().stageLastEpochDay)
        assertEquals(StageRange.ONE_WEEK.days, items.single().stageCoveredDays)
    }

    @Test
    fun `当天作业仅展开一条且不带阶段范围`() {
        val template = HomeworkTemplate(
            content = "数学练习册 p10",
            type = HomeworkType.TODAY,
            stageRange = StageRange.TWO_WEEKS,
            creatorRole = CreatorRole.PARENT,
            startEpochDay = 0L,
            zoneId = zone,
        )
        val items = template.toItems(
            parentAccountId = 1L,
            studentId = 10L,
            createdAt = Instant.ofEpochMilli(baseMillis),
            firstPriority = 0,
        )
        assertEquals(1, items.size)
        assertEquals(null, items.single().stageRange)
        assertEquals(HomeworkType.TODAY, items.single().type)
    }

    @Test
    fun `预估时长越界被拦截`() {
        assertTrue(HomeworkValidators.validateEstimatedMinutes(null) is HomeworkValidation.Invalid)
        assertTrue(HomeworkValidators.validateEstimatedMinutes(0) is HomeworkValidation.Invalid)
        assertTrue(
            HomeworkValidators.validateEstimatedMinutes(HomeworkConstants.MAX_ESTIMATED_MINUTES + 1)
                is HomeworkValidation.Invalid,
        )
        assertEquals(HomeworkValidation.Valid, HomeworkValidators.validateEstimatedMinutes(1))
        assertEquals(
            HomeworkValidation.Valid,
            HomeworkValidators.validateEstimatedMinutes(HomeworkConstants.MAX_ESTIMATED_MINUTES),
        )
    }

    @Test
    fun `修改类型为阶段作业时校验阶段范围与每日截止时刻`() {
        assertEquals(
            HomeworkValidation.Invalid(HomeworkValidationError.MISSING_STAGE_RANGE),
            HomeworkValidators.validateTypeChange(
                type = HomeworkType.STAGE,
                stageRange = null,
                creatorRole = CreatorRole.PARENT,
                dailyDeadlineTime = LocalTime.of(21, 0),
            ),
        )
        assertEquals(
            HomeworkValidation.Invalid(HomeworkValidationError.MISSING_STAGE_DEADLINE),
            HomeworkValidators.validateTypeChange(
                type = HomeworkType.STAGE,
                stageRange = StageRange.ONE_WEEK,
                creatorRole = CreatorRole.PARENT,
                dailyDeadlineTime = null,
            ),
        )
        // 学生录入阶段作业可不设每日截止时刻
        assertEquals(
            HomeworkValidation.Valid,
            HomeworkValidators.validateTypeChange(
                type = HomeworkType.STAGE,
                stageRange = StageRange.ONE_WEEK,
                creatorRole = CreatorRole.STUDENT,
                dailyDeadlineTime = null,
            ),
        )
        // 改回当天作业：阶段范围被忽略
        assertEquals(
            HomeworkValidation.Valid,
            HomeworkValidators.validateTypeChange(
                type = HomeworkType.TODAY,
                stageRange = null,
                creatorRole = CreatorRole.PARENT,
                dailyDeadlineTime = null,
            ),
        )
    }

    @Test
    fun `枚举字符串解析对脏值返回 null`() {
        assertEquals(HomeworkType.STAGE, HomeworkType.fromName("STAGE"))
        assertEquals(null, HomeworkType.fromName("stage"))
        assertEquals(null, HomeworkType.fromName(null))
        assertEquals(StageRange.ONE_MONTH, StageRange.fromName("ONE_MONTH"))
        assertEquals(null, StageRange.fromName("ONE_YEAR"))
        assertEquals(HomeworkStatus.IN_PROGRESS, HomeworkStatus.fromName("IN_PROGRESS"))
        assertEquals(null, HomeworkStatus.fromName("DONE"))
        assertEquals(CreatorRole.STUDENT, CreatorRole.fromName("STUDENT"))
        assertEquals(CreatorRole.PARENT, CreatorRole.fromSessionRole(Role.PARENT))
        assertEquals(CreatorRole.STUDENT, CreatorRole.fromSessionRole(Role.STUDENT))
    }

    @Test
    fun `阶段范围折算天数与最后一天计算正确`() {
        assertEquals(7, StageRange.ONE_WEEK.days)
        assertEquals(14, StageRange.TWO_WEEKS.days)
        assertEquals(21, StageRange.THREE_WEEKS.days)
        assertEquals(HomeworkConstants.DAYS_PER_MONTH, StageRange.ONE_MONTH.days)

        val start = LocalDate.of(2025, 1, 1).toEpochDay()
        assertEquals(
            LocalDate.of(2025, 1, 7).toEpochDay(),
            StageRange.ONE_WEEK.lastEpochDay(start),
        )
        assertEquals(
            LocalDate.of(2025, 1, 30).toEpochDay(),
            StageRange.ONE_MONTH.lastEpochDay(start),
        )
        // 覆盖日集合（进度分母来源）：长度 = days、升序、含首尾
        val covered = StageRange.ONE_WEEK.coveredEpochDays(start)
        assertEquals(7, covered.size)
        assertEquals(start, covered.first())
        assertEquals(start + 6L, covered.last())
        assertTrue(StageRange.ONE_WEEK.covers(start, start + 3L))
        assertTrue(!StageRange.ONE_WEEK.covers(start, start + 7L))
    }

    // ---- 业务时间口径 ----

    @Test
    fun `epochDay 按业务时区折算 凌晨时刻不得取到昨天`() {
        // UTC+8 的 2025-01-01 00:30 == UTC 2024-12-31 16:30
        val millis = millisOf(dayOffset = 0, hour = 0, minute = 30)

        assertEquals(
            LocalDate.of(2025, 1, 1).toEpochDay(),
            HomeworkValidators.epochDayOf(millis, zone),
        )
        // 回归保护：同一时刻若按 UTC 折算会落到前一天——禁止再用 millis / 86_400_000 的口径
        assertEquals(
            LocalDate.of(2024, 12, 31).toEpochDay(),
            HomeworkValidators.epochDayOf(millis, ZoneOffset.UTC),
        )
    }

    @Test
    fun `epochDay 与当天零点口径自洽`() {
        val millis = millisOf(dayOffset = 3, hour = 23, minute = 59)
        val millisPerDay = 24 * 60 * 60 * 1000L

        val epochDay = HomeworkValidators.epochDayOf(millis, zone)
        val startOfDay = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(LocalDate.of(2025, 1, 4).toEpochDay(), epochDay)
        assertTrue("当天零点不应晚于该时刻", startOfDay <= millis)
        assertTrue("次日零点应晚于该时刻", startOfDay + millisPerDay > millis)
    }
}