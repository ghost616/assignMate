package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidationError
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HomeworkRepositoryImpl 仓库关键流程单测：新增（含阶段展开与模板校验）、优先级调整、
 * 时间设定拦截（deadline 超限 / 时段冲突 / 时长非法）、权限拦截（学生 vs 家长）、
 * 内容与类型修改、状态流转。
 *
 * 依赖：FakeHomeworkItemDao + auth 真实实现（Fake DAO/KeyValueStore）+ 可推进固定时钟。
 */
class HomeworkRepositoryImplTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 基准日与固定时钟（1_700_000_000_000ms → 上海时区 2023-11-15）保持一致，避免偏移量错位 */
    private fun millisAt(hour: Int, minute: Int = 0, dayOffset: Long = 0L): Long =
        LocalDate.of(2023, 11, 15).plusDays(dayOffset)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private suspend fun HomeworkTestEnv.addTodayHomework(
        studentId: Long,
        content: String,
        createdBy: CreatorRole = CreatorRole.PARENT,
        deadline: Instant? = null,
    ): AddHomeworkResult = repository.addHomework(
        HomeworkTemplate(
            content = content,
            type = HomeworkType.TODAY,
            deadline = deadline,
            creatorRole = createdBy,
            startEpochDay = todayEpochDay(),
            zoneId = zone,
        ),
        studentId,
    )

    private fun HomeworkTestEnv.todayEpochDay(): Long =
        Instant.ofEpochMilli(clock.currentTimeMillis()).atZone(zone).toLocalDate().toEpochDay()

    // ---- 新增 ----

    @Test
    fun `家长新增当天作业初始状态为已记录且未排定时间`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)

        val result = env.addTodayHomework(studentId, "语文第 3 课生字")

        assertTrue(result is AddHomeworkResult.Success)
        val item = (result as AddHomeworkResult.Success).items.single()
        assertEquals("语文第 3 课生字", item.content)
        assertEquals(HomeworkStatus.RECORDED, item.status)
        assertEquals(CreatorRole.PARENT, item.createdByRole)
        assertEquals(parentId, item.parentAccountId)
        assertEquals(studentId, item.studentId)
        assertNull(item.startTime)
        assertNull(item.estimatedMinutes)
        assertEquals(HomeworkConstants.MIN_PRIORITY, item.priority)
    }

    @Test
    fun `新增作业追加到清单末尾`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)

        env.addTodayHomework(studentId, "第一项")
        env.advance(1_000)
        val second = env.addTodayHomework(studentId, "第二项")

        val priority = (second as AddHomeworkResult.Success).items.single().priority
        assertEquals(1, priority)
        assertEquals(listOf("第一项", "第二项"), env.repository.listHomework(studentId).map { it.content })
    }

    @Test
    fun `家长录入阶段作业必须带阶段范围与截止时间`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)

        val missingRange = env.repository.addHomework(
            HomeworkTemplate(
                content = "每天读课文",
                type = HomeworkType.STAGE,
                stageRange = null,
                creatorRole = CreatorRole.PARENT,
                startEpochDay = env.todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        )
        assertEquals(
            AddHomeworkResult.TemplateInvalid(HomeworkValidationError.MISSING_STAGE_RANGE),
            missingRange,
        )

        val missingDeadline = env.repository.addHomework(
            HomeworkTemplate(
                content = "每天读课文",
                type = HomeworkType.STAGE,
                stageRange = StageRange.ONE_WEEK,
                deadline = null,
                creatorRole = CreatorRole.PARENT,
                startEpochDay = env.todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        )
        assertEquals(
            AddHomeworkResult.TemplateInvalid(HomeworkValidationError.MISSING_STAGE_DEADLINE),
            missingDeadline,
        )
        assertTrue(env.homeworkDao.all().isEmpty())
    }

    @Test
    fun `一周阶段作业固定产出一条且带每日截止时刻`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)

        val result = env.repository.addHomework(
            HomeworkTemplate(
                content = "每天读课文",
                type = HomeworkType.STAGE,
                stageRange = StageRange.ONE_WEEK,
                deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
                creatorRole = CreatorRole.PARENT,
                startEpochDay = env.todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        )

        val items = (result as AddHomeworkResult.Success).items
        // 阶段作业 = 一个在阶段内需完成的作业项：固定 1 条（不再逐日展开）
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(0, item.priority)
        assertEquals(HomeworkType.STAGE, item.type)
        assertEquals(StageRange.ONE_WEEK, item.stageRange)
        assertEquals(HomeworkStatus.RECORDED, item.status)
        assertEquals(LocalTime.of(21, 0), item.dailyDeadlineTime)
        assertEquals(env.todayEpochDay(), item.stageStartEpochDay)
        assertEquals(env.todayEpochDay() + 6L, item.stageLastEpochDay)
    }

    @Test
    fun `无有效会话时新增返回未登录`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.logout()

        val result = env.addTodayHomework(studentId, "无会话作业")
        assertEquals(AddHomeworkResult.NoActiveSession, result)
    }

    // ---- 优先级调整 ----

    @Test
    fun `上移下移通过与相邻项交换优先级实现`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addTodayHomework(studentId, "A")
        env.advance(1_000)
        env.addTodayHomework(studentId, "B")
        env.advance(1_000)
        env.addTodayHomework(studentId, "C")
        val ids = env.repository.listHomework(studentId).map { it.id }

        val up = env.repository.reorderHomework(ids[2], ReorderDirection.UP, Role.PARENT)
        assertEquals(HomeworkOrderResult.Success, up)
        assertEquals(
            listOf("A", "C", "B"),
            env.repository.listHomework(studentId).map { it.content },
        )

        val down = env.repository.reorderHomework(ids[2], ReorderDirection.DOWN, Role.PARENT)
        assertEquals(HomeworkOrderResult.Success, down)
        assertEquals(
            listOf("A", "B", "C"),
            env.repository.listHomework(studentId).map { it.content },
        )
    }

    @Test
    fun `已在边界时调整顺序为幂等成功`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addTodayHomework(studentId, "唯一一项")
        val id = env.repository.listHomework(studentId).single().id

        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.reorderHomework(id, ReorderDirection.UP, Role.PARENT),
        )
        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.reorderHomework(id, ReorderDirection.DOWN, Role.PARENT),
        )
    }

    @Test
    fun `落位到指定位置后其余项顺延`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addTodayHomework(studentId, "A")
        env.advance(1_000)
        env.addTodayHomework(studentId, "B")
        env.advance(1_000)
        env.addTodayHomework(studentId, "C")
        val ids = env.repository.listHomework(studentId).map { it.id }

        val result = env.repository.moveHomeworkTo(ids[2], 0, Role.PARENT)
        assertEquals(HomeworkOrderResult.Success, result)
        val ordered = env.repository.listHomework(studentId)
        assertEquals(listOf("C", "A", "B"), ordered.map { it.content })
        assertEquals(listOf(0, 1, 2), ordered.map { it.priority })
    }

    @Test
    fun `学生不可调整家长录入项的优先级`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addTodayHomework(studentId, "家长布置")
        env.advance(1_000)
        env.addTodayHomework(studentId, "家长布置2")
        val ids = env.repository.listHomework(studentId).map { it.id }
        env.loginAsStudent(parentId, studentId)

        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.reorderHomework(ids[1], ReorderDirection.UP, Role.STUDENT),
        )
        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.moveHomeworkTo(ids[1], 0, Role.STUDENT),
        )
    }

    @Test
    fun `不存在的作业调整顺序返回未找到`() = runTest {
        val env = HomeworkTestEnv()
        env.loginAsParent()
        assertEquals(
            HomeworkOrderResult.NotFound,
            env.repository.reorderHomework(999L, ReorderDirection.UP, Role.PARENT),
        )
    }

    // ---- 进行中锁定（需求假设 C）：已开始 / 已完成的作业不允许调整优先级与时间 ----

    @Test
    fun `进行中作业调序被拦截且顺序与时间保持不变`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addTodayHomework(studentId, "A")
        env.advance(1_000)
        val second = (env.addTodayHomework(studentId, "B") as AddHomeworkResult.Success).items.single()
        val start = Instant.ofEpochMilli(millisAt(16))
        env.repository.updateSchedule(second.id, start, 30, Role.PARENT)
        env.repository.startProgress(second.id, Role.PARENT)

        assertEquals(
            HomeworkOrderResult.LockedWorkInProgress,
            env.repository.reorderHomework(second.id, ReorderDirection.UP, Role.PARENT),
        )
        assertEquals(
            HomeworkOrderResult.LockedWorkInProgress,
            env.repository.moveHomeworkTo(second.id, 0, Role.PARENT),
        )
        // 被拒后顺序与优先级、排定时间均保持原样
        val ordered = env.repository.listHomework(studentId)
        assertEquals(listOf("A", "B"), ordered.map { it.content })
        assertEquals(listOf(0, 1), ordered.map { it.priority })
        assertEquals(HomeworkStatus.IN_PROGRESS, ordered[1].status)
        assertEquals(start, ordered[1].startTime)
        assertEquals(30, ordered[1].estimatedMinutes)
    }

    @Test
    fun `进行中作业不可重新排定时间也不可撤销排定`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "语文练习") as AddHomeworkResult.Success).items.single()
        val start = Instant.ofEpochMilli(millisAt(16))
        env.repository.updateSchedule(item.id, start, 30, Role.PARENT)
        env.repository.startProgress(item.id, Role.PARENT)

        assertEquals(
            ScheduleUpdateResult.LockedWorkInProgress,
            env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(17)), 45, Role.PARENT),
        )
        assertEquals(
            HomeworkOperationResult.LockedWorkInProgress,
            env.repository.clearSchedule(item.id, Role.PARENT),
        )
        // 原排定与状态均未被改动（未留下「半撤销」的中间态）
        val current = env.repository.getHomework(item.id)!!
        assertEquals(HomeworkStatus.IN_PROGRESS, current.status)
        assertEquals(start, current.startTime)
        assertEquals(30, current.estimatedMinutes)
    }

    @Test
    fun `学生对自己录入的进行中作业同样受锁定约束`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.loginAsStudent(parentId, studentId)
        val item = (
            env.addTodayHomework(studentId, "自己记的作业", createdBy = CreatorRole.STUDENT)
                as AddHomeworkResult.Success
            ).items.single()
        env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(16)), 30, Role.STUDENT)
        env.repository.startProgress(item.id, Role.STUDENT)

        // 改删/调序权本可通过（学生自己录入项），但进行中锁定使其同样被拒
        assertEquals(
            ScheduleUpdateResult.LockedWorkInProgress,
            env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(17)), 30, Role.STUDENT),
        )
        assertEquals(
            HomeworkOperationResult.LockedWorkInProgress,
            env.repository.clearSchedule(item.id, Role.STUDENT),
        )
        assertEquals(
            HomeworkOrderResult.LockedWorkInProgress,
            env.repository.reorderHomework(item.id, ReorderDirection.UP, Role.STUDENT),
        )
    }

    @Test
    fun `待完成作业仍可正常调序排定与撤销排定`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val first = (env.addTodayHomework(studentId, "A") as AddHomeworkResult.Success).items.single()
        env.advance(1_000)
        val second = (env.addTodayHomework(studentId, "B") as AddHomeworkResult.Success).items.single()
        // 排定后即进入「待完成」（未开始计时，不触发进行中锁定）
        env.repository.updateSchedule(second.id, Instant.ofEpochMilli(millisAt(16)), 30, Role.PARENT)

        // 调序仍可用
        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.reorderHomework(second.id, ReorderDirection.UP, Role.PARENT),
        )
        assertEquals(listOf("B", "A"), env.repository.listHomework(studentId).map { it.content })

        // 改时间仍可用（同上一条作业自身排定不冲突）
        val rescheduled = env.repository.updateSchedule(
            second.id,
            Instant.ofEpochMilli(millisAt(17)),
            45,
            Role.PARENT,
        )
        assertTrue(rescheduled is ScheduleUpdateResult.Success)
        assertEquals(
            HomeworkStatus.PENDING,
            (rescheduled as ScheduleUpdateResult.Success).item.status,
        )

        // 撤销排定仍可用：待完成回退为已记录
        val cleared = env.repository.clearSchedule(second.id, Role.PARENT)
        assertTrue(cleared is HomeworkOperationResult.Success)
        assertEquals(
            HomeworkStatus.RECORDED,
            (cleared as HomeworkOperationResult.Success).item.status,
        )
        assertEquals("A", env.repository.getHomework(first.id)?.content)
    }

    @Test
    fun `已完成作业仍可调序但不可排定时间`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val first = (env.addTodayHomework(studentId, "A") as AddHomeworkResult.Success).items.single()
        env.advance(1_000)
        val second = (env.addTodayHomework(studentId, "B") as AddHomeworkResult.Success).items.single()
        env.repository.markPending(second.id, Role.PARENT)
        env.repository.complete(second.id, Role.PARENT)

        // 已完成的既有约束保持不变：可调序（仅受改删权限制），但不可再排定时间
        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.reorderHomework(second.id, ReorderDirection.UP, Role.PARENT),
        )
        assertEquals(listOf("B", "A"), env.repository.listHomework(studentId).map { it.content })
        assertEquals(
            ScheduleUpdateResult.StatusTransitionDenied,
            env.repository.updateSchedule(
                second.id,
                Instant.ofEpochMilli(millisAt(18)),
                30,
                Role.PARENT,
            ),
        )
        assertEquals("A", env.repository.getHomework(first.id)?.content)
    }

    // ---- 时间设定：deadline 约束与时段冲突拦截 ----

    @Test
    fun `时间设定成功后状态推进为待完成`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "数学练习") as AddHomeworkResult.Success).items.single()

        val start = Instant.ofEpochMilli(millisAt(16))
        val result = env.repository.updateSchedule(item.id, start, 45, Role.PARENT)

        assertTrue(result is ScheduleUpdateResult.Success)
        val updated = (result as ScheduleUpdateResult.Success).item
        assertEquals(HomeworkStatus.PENDING, updated.status)
        assertEquals(start, updated.startTime)
        assertEquals(45, updated.estimatedMinutes)
    }

    @Test
    fun `开始时间加时长超过截止时间被拦截`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val deadline = Instant.ofEpochMilli(millisAt(17))
        val item = (
            env.addTodayHomework(studentId, "语文背诵", deadline = deadline) as AddHomeworkResult.Success
            ).items.single()

        val result = env.repository.updateSchedule(
            item.id,
            Instant.ofEpochMilli(millisAt(16, 30)),
            45,
            Role.PARENT,
        )
        assertEquals(ScheduleUpdateResult.DeadlineExceeded, result)
        // 拦截后数据保持不变
        assertEquals(HomeworkStatus.RECORDED, env.repository.getHomework(item.id)?.status)
    }

    @Test
    fun `与其它作业时间段重叠被拦截`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val first = (env.addTodayHomework(studentId, "第一项") as AddHomeworkResult.Success).items.single()
        env.advance(1_000)
        val second = (env.addTodayHomework(studentId, "第二项") as AddHomeworkResult.Success).items.single()

        env.repository.updateSchedule(first.id, Instant.ofEpochMilli(millisAt(16)), 60, Role.PARENT)
        val conflict = env.repository.updateSchedule(
            second.id,
            Instant.ofEpochMilli(millisAt(16, 30)),
            30,
            Role.PARENT,
        )
        assertEquals(ScheduleUpdateResult.TimeConflict, conflict)

        // 换成不重叠的时段即可通过
        val ok = env.repository.updateSchedule(
            second.id,
            Instant.ofEpochMilli(millisAt(17)),
            30,
            Role.PARENT,
        )
        assertTrue(ok is ScheduleUpdateResult.Success)
    }

    @Test
    fun `修改自身时间不与原排定冲突`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "第一项") as AddHomeworkResult.Success).items.single()
        env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(16)), 60, Role.PARENT)

        val result = env.repository.updateSchedule(
            item.id,
            Instant.ofEpochMilli(millisAt(16, 30)),
            30,
            Role.PARENT,
        )
        assertTrue(result is ScheduleUpdateResult.Success)
        assertEquals(
            Instant.ofEpochMilli(millisAt(16, 30)),
            (result as ScheduleUpdateResult.Success).item.startTime,
        )
    }

    @Test
    fun `预估时长非法被拦截`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "第一项") as AddHomeworkResult.Success).items.single()

        assertEquals(
            ScheduleUpdateResult.InvalidEstimatedMinutes,
            env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(16)), 0, Role.PARENT),
        )
        assertEquals(
            ScheduleUpdateResult.InvalidEstimatedMinutes,
            env.repository.updateSchedule(
                item.id,
                Instant.ofEpochMilli(millisAt(16)),
                HomeworkConstants.MAX_ESTIMATED_MINUTES + 1,
                Role.PARENT,
            ),
        )
    }

    @Test
    fun `已完成作业不允许再排定时间`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "第一项") as AddHomeworkResult.Success).items.single()
        env.repository.markPending(item.id, Role.PARENT)
        env.repository.complete(item.id, Role.PARENT)

        val result = env.repository.updateSchedule(
            item.id,
            Instant.ofEpochMilli(millisAt(18)),
            30,
            Role.PARENT,
        )
        assertEquals(ScheduleUpdateResult.StatusTransitionDenied, result)
    }

    @Test
    fun `撤销时间排定后状态回退为已记录`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "第一项") as AddHomeworkResult.Success).items.single()
        env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(16)), 30, Role.PARENT)

        val result = env.repository.clearSchedule(item.id, Role.PARENT)
        assertTrue(result is HomeworkOperationResult.Success)
        val updated = (result as HomeworkOperationResult.Success).item
        assertNull(updated.startTime)
        assertNull(updated.estimatedMinutes)
        assertEquals(HomeworkStatus.RECORDED, updated.status)
    }

    // ---- 执行权：学生可为本人名下（含家长布置的）作业排定时间与推进状态 ----

    @Test
    fun `学生可为家长布置的作业排定时间`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (
            env.addTodayHomework(studentId, "家长布置的语文作业", createdBy = CreatorRole.PARENT)
                as AddHomeworkResult.Success
            ).items.single()
        env.loginAsStudent(parentId, studentId)

        val start = Instant.ofEpochMilli(millisAt(16))
        val result = env.repository.updateSchedule(item.id, start, 45, Role.STUDENT)

        assertTrue(result is ScheduleUpdateResult.Success)
        val updated = (result as ScheduleUpdateResult.Success).item
        assertEquals(HomeworkStatus.PENDING, updated.status)
        assertEquals(start, updated.startTime)
        assertEquals(CreatorRole.PARENT, updated.createdByRole)
    }

    @Test
    fun `学生可为家长布置的作业开始计时并标记完成`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (
            env.addTodayHomework(studentId, "家长布置的数学作业") as AddHomeworkResult.Success
            ).items.single()
        env.loginAsStudent(parentId, studentId)

        val pending = env.repository.markPending(item.id, Role.STUDENT)
        assertEquals(HomeworkStatus.PENDING, (pending as HomeworkStatusResult.Success).item.status)

        val inProgress = env.repository.startProgress(item.id, Role.STUDENT)
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            (inProgress as HomeworkStatusResult.Success).item.status,
        )

        val completed = env.repository.complete(item.id, Role.STUDENT)
        assertEquals(
            HomeworkStatus.COMPLETED,
            (completed as HomeworkStatusResult.Success).item.status,
        )
    }

    @Test
    fun `学生可为家长布置的作业撤销时间排定`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "家长布置") as AddHomeworkResult.Success).items.single()
        env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(16)), 30, Role.PARENT)
        env.loginAsStudent(parentId, studentId)

        val result = env.repository.clearSchedule(item.id, Role.STUDENT)

        assertTrue(result is HomeworkOperationResult.Success)
        val updated = (result as HomeworkOperationResult.Success).item
        assertNull(updated.startTime)
        assertEquals(HomeworkStatus.RECORDED, updated.status)
    }

    @Test
    fun `学生不可为他人的作业排定时间或推进状态`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val ownStudentId = env.addStudent(parentId, "小明")
        val otherStudentId = env.addStudent(parentId, "小美")
        val otherItem = (
            env.addTodayHomework(otherStudentId, "小美的作业") as AddHomeworkResult.Success
            ).items.single()
        // 学生会话指向本人，目标作业归属另一个学生
        env.loginAsStudent(parentId, ownStudentId)

        assertEquals(
            ScheduleUpdateResult.PermissionDenied,
            env.repository.updateSchedule(
                otherItem.id,
                Instant.ofEpochMilli(millisAt(16)),
                30,
                Role.STUDENT,
            ),
        )
        assertEquals(
            HomeworkStatusResult.PermissionDenied,
            env.repository.complete(otherItem.id, Role.STUDENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.clearSchedule(otherItem.id, Role.STUDENT),
        )
        // 数据保持原状
        assertEquals(HomeworkStatus.RECORDED, env.repository.getHomework(otherItem.id)?.status)
        assertNull(env.repository.getHomework(otherItem.id)?.startTime)
    }

    @Test
    fun `家长会话不可为他人名下学生新增作业`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val otherStudentId = env.addStudent(parentId, "小美")
        env.logout()

        val result = env.addTodayHomework(otherStudentId, "无会话越权作业")

        assertEquals(AddHomeworkResult.NoActiveSession, result)
        assertTrue(env.repository.listHomework(otherStudentId).isEmpty())
    }

    @Test
    fun `学生会话不可为他人学生新增作业`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val ownStudentId = env.addStudent(parentId, "小明")
        val otherStudentId = env.addStudent(parentId, "小美")
        env.loginAsStudent(parentId, ownStudentId)

        val result = env.addTodayHomework(otherStudentId, "越权作业")

        assertEquals(AddHomeworkResult.NoActiveSession, result)
        assertTrue(env.repository.listHomework(otherStudentId).isEmpty())
    }

    @Test
    fun `家长不可操作他人名下学生的作业`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent("parent001")
        val otherParentId = env.loginAsParent("parent002")
        val otherStudentId = env.addStudent(otherParentId, "小美")
        val otherItem = (
            env.repository.addHomework(
                HomeworkTemplate(
                    content = "小美的作业",
                    type = HomeworkType.TODAY,
                    creatorRole = CreatorRole.PARENT,
                    startEpochDay = env.todayEpochDay(),
                    zoneId = zone,
                ),
                otherStudentId,
            ) as AddHomeworkResult.Success
            ).items.single()
        // 切回第一位家长的会话：此时作业归属第二位家长名下的学生
        env.authRepository.loginParent("parent001", HomeworkTestEnv.PASSWORD)

        // 新增落库在「当前会话家长」名下，归属校验以作业归属为准
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateTemplate(
                otherItem.id,
                HomeworkType.STAGE,
                StageRange.ONE_WEEK,
                HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
                Role.PARENT,
            ),
        )
        assertEquals(otherParentId, otherItem.parentAccountId)
        assertEquals(parentId, env.currentSession().parentId)
    }

    // ---- 权限拦截：学生 vs 家长（改删维度） ----

    @Test
    fun `学生不可修改或删除家长录入的作业`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val parentItem = (env.addTodayHomework(studentId, "家长布置") as AddHomeworkResult.Success).items.single()
        env.loginAsStudent(parentId, studentId)

        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateContent(parentItem.id, "学生改内容", Role.STUDENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.deleteHomework(parentItem.id, Role.STUDENT),
        )
        // 改删被拒；但执行权（排定时间/推进状态）学生可用，故此处不再断言 PermissionDenied
        assertTrue(env.repository.updateSchedule(
            parentItem.id,
            Instant.ofEpochMilli(millisAt(16)),
            30,
            Role.STUDENT,
        ) is ScheduleUpdateResult.Success)
        assertTrue(env.repository.markPending(parentItem.id, Role.STUDENT) is HomeworkStatusResult.Success)
        // 内容未被改动
        assertEquals("家长布置", env.repository.getHomework(parentItem.id)?.content)
    }

    @Test
    fun `学生可修改删除自己录入的作业`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.loginAsStudent(parentId, studentId)
        val ownItem = (
            env.addTodayHomework(studentId, "自己记的作业", createdBy = CreatorRole.STUDENT)
                as AddHomeworkResult.Success
            ).items.single()

        val updated = env.repository.updateContent(ownItem.id, "自己记的作业（已改）", Role.STUDENT)
        assertTrue(updated is HomeworkOperationResult.Success)
        assertEquals(
            "自己记的作业（已改）",
            (updated as HomeworkOperationResult.Success).item.content,
        )

        val deleted = env.repository.deleteHomework(ownItem.id, Role.STUDENT)
        assertTrue(deleted is HomeworkOperationResult.Success)
        assertNull(env.repository.getHomework(ownItem.id))
    }

    // ---- 内容与类型修改 ----

    @Test
    fun `家长可修改任意来源作业的内容且内容被修剪`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "原内容") as AddHomeworkResult.Success).items.single()

        val result = env.repository.updateContent(item.id, "  新内容  ", Role.PARENT)
        assertTrue(result is HomeworkOperationResult.Success)
        assertEquals("新内容", (result as HomeworkOperationResult.Success).item.content)
    }

    @Test
    fun `空白内容修改被拦截`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "原内容") as AddHomeworkResult.Success).items.single()

        val result = env.repository.updateContent(item.id, "   ", Role.PARENT)
        assertEquals(
            HomeworkOperationResult.ContentInvalid(HomeworkValidationError.BLANK_CONTENT),
            result,
        )
        assertEquals("原内容", env.repository.getHomework(item.id)?.content)
    }

    @Test
    fun `修改类型为阶段作业时校验阶段范围与每日截止时刻`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "作业") as AddHomeworkResult.Success).items.single()

        assertEquals(
            HomeworkOperationResult.TemplateInvalid(HomeworkValidationError.MISSING_STAGE_RANGE),
            env.repository.updateTemplate(item.id, HomeworkType.STAGE, null, null, Role.PARENT),
        )
        assertEquals(
            HomeworkOperationResult.TemplateInvalid(HomeworkValidationError.MISSING_STAGE_DEADLINE),
            env.repository.updateTemplate(
                item.id,
                HomeworkType.STAGE,
                StageRange.ONE_WEEK,
                null,
                Role.PARENT,
            ),
        )

        // 阶段截止时间只承载「每日时刻」：传入时刻载体后落库为可唯一还原的每日时刻
        val ok = env.repository.updateTemplate(
            item.id,
            HomeworkType.STAGE,
            StageRange.ONE_WEEK,
            HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(20, 30)),
            Role.PARENT,
        )
        assertTrue(ok is HomeworkOperationResult.Success)
        val updated = (ok as HomeworkOperationResult.Success).item
        assertEquals(HomeworkType.STAGE, updated.type)
        assertEquals(StageRange.ONE_WEEK, updated.stageRange)
        assertEquals(LocalTime.of(20, 30), updated.dailyDeadlineTime)
        // 阶段作业的起始日取自创建日，覆盖最后一天 = 起始日 + 6
        assertEquals(env.todayEpochDay(), updated.stageStartEpochDay)
        assertEquals(env.todayEpochDay() + 6L, updated.stageLastEpochDay)
    }

    @Test
    fun `改回当天作业时清空阶段范围`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val stage = env.repository.addHomework(
            HomeworkTemplate(
                content = "阶段作业",
                type = HomeworkType.STAGE,
                stageRange = StageRange.ONE_WEEK,
                deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
                creatorRole = CreatorRole.PARENT,
                startEpochDay = env.todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        )
        val first = (stage as AddHomeworkResult.Success).items.single()

        val result = env.repository.updateTemplate(first.id, HomeworkType.TODAY, null, null, Role.PARENT)
        assertTrue(result is HomeworkOperationResult.Success)
        val updated = (result as HomeworkOperationResult.Success).item
        assertEquals(HomeworkType.TODAY, updated.type)
        assertNull(updated.stageRange)
    }

    @Test
    fun `已完成作业撤销时间排定后回退为进行中并落入锁定`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "数学练习") as AddHomeworkResult.Success).items.single()
        env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(16)), 30, Role.PARENT)
        env.repository.complete(item.id, Role.PARENT)

        // 已完成不可直接重排（保留历史排定口径）
        assertEquals(
            ScheduleUpdateResult.StatusTransitionDenied,
            env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(17)), 30, Role.PARENT),
        )

        // 撤销排定后状态回退为进行中（不再留「永远无法再排定」的死局），
        // 但进行中按锁定规则同样不可再改时间：重新排定前须先显式纠正状态（如完成后再处理）
        val cleared = env.repository.clearSchedule(item.id, Role.PARENT)
        assertTrue(cleared is HomeworkOperationResult.Success)
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            (cleared as HomeworkOperationResult.Success).item.status,
        )
        assertNull(cleared.item.startTime)

        assertEquals(
            ScheduleUpdateResult.LockedWorkInProgress,
            env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(17)), 30, Role.PARENT),
        )
    }

    @Test
    fun `撤销完成后的进行中作业不再允许改时间`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "数学练习") as AddHomeworkResult.Success).items.single()
        val start = Instant.ofEpochMilli(millisAt(16))
        env.repository.updateSchedule(item.id, start, 30, Role.PARENT)
        env.repository.startProgress(item.id, Role.PARENT)
        env.repository.complete(item.id, Role.PARENT)

        val reopened = env.repository.reopen(item.id, Role.PARENT)
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            (reopened as HomeworkStatusResult.Success).item.status,
        )
        assertEquals(
            ScheduleUpdateResult.LockedWorkInProgress,
            env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(17)), 30, Role.PARENT),
        )
        assertEquals(start, env.repository.getHomework(item.id)?.startTime)
    }

    @Test
    fun `修改截止时间不可短于既有排定时间段`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (
            env.addTodayHomework(studentId, "作业", deadline = Instant.ofEpochMilli(millisAt(20)))
                as AddHomeworkResult.Success
            ).items.single()
        env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(19)), 60, Role.PARENT)

        // 19:00 + 60min 结束于 20:00；把 deadline 提前到 19:30 应被拦截
        val tooTight = env.repository.updateTemplate(
            item.id,
            HomeworkType.TODAY,
            null,
            Instant.ofEpochMilli(millisAt(19, 30)),
            Role.PARENT,
        )
        assertEquals(
            HomeworkOperationResult.TemplateInvalid(HomeworkValidationError.DEADLINE_EXCEEDED),
            tooTight,
        )
        assertEquals(
            Instant.ofEpochMilli(millisAt(20)),
            env.repository.getHomework(item.id)?.deadline,
        )

        // 与排定结束时刻恰好相等则通过
        val exact = env.repository.updateTemplate(
            item.id,
            HomeworkType.TODAY,
            null,
            Instant.ofEpochMilli(millisAt(20)),
            Role.PARENT,
        )
        assertTrue(exact is HomeworkOperationResult.Success)
    }

    @Test
    fun `改为阶段作业时既有排定时间段须落在每日截止时刻之前`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "作业") as AddHomeworkResult.Success).items.single()

        // 既有排定：16:00 开始 120 分钟（结束 18:00）
        env.repository.updateSchedule(item.id, Instant.ofEpochMilli(millisAt(16)), 120, Role.PARENT)

        // 每日截止时刻 17:00：18:00 结束跨过该时刻，拦截
        assertEquals(
            HomeworkOperationResult.TemplateInvalid(HomeworkValidationError.DEADLINE_EXCEEDED),
            env.repository.updateTemplate(
                item.id,
                HomeworkType.STAGE,
                StageRange.ONE_WEEK,
                HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(17, 0)),
                Role.PARENT,
            ),
        )

        // 每日截止时刻 18:00：正好等于排定结束时刻，通过
        assertTrue(
            env.repository.updateTemplate(
                item.id,
                HomeworkType.STAGE,
                StageRange.ONE_WEEK,
                HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(18, 0)),
                Role.PARENT,
            ) is HomeworkOperationResult.Success,
        )
    }

    // ---- 状态流转 ----

    @Test
    fun `状态流转按合法路径推进`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "第一项") as AddHomeworkResult.Success).items.single()

        val pending = env.repository.markPending(item.id, Role.PARENT)
        assertEquals(
            HomeworkStatus.PENDING,
            (pending as HomeworkStatusResult.Success).item.status,
        )

        val inProgress = env.repository.startProgress(item.id, Role.PARENT)
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            (inProgress as HomeworkStatusResult.Success).item.status,
        )

        val completed = env.repository.complete(item.id, Role.PARENT)
        assertEquals(
            HomeworkStatus.COMPLETED,
            (completed as HomeworkStatusResult.Success).item.status,
        )

        val reopened = env.repository.reopen(item.id, Role.PARENT)
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            (reopened as HomeworkStatusResult.Success).item.status,
        )
    }

    @Test
    fun `已记录不可直接流转为进行中`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "第一项") as AddHomeworkResult.Success).items.single()

        val result = env.repository.startProgress(item.id, Role.PARENT)
        assertEquals(
            HomeworkStatusResult.IllegalTransition(HomeworkStatus.RECORDED, HomeworkStatus.IN_PROGRESS),
            result,
        )
        assertEquals(HomeworkStatus.RECORDED, env.repository.getHomework(item.id)?.status)
    }

    @Test
    fun `幂等流转不报错`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (env.addTodayHomework(studentId, "第一项") as AddHomeworkResult.Success).items.single()

        val first = env.repository.markPending(item.id, Role.PARENT)
        assertTrue(first is HomeworkStatusResult.Success)
        val second = env.repository.markPending(item.id, Role.PARENT)
        assertTrue(second is HomeworkStatusResult.Success)
    }

    @Test
    fun `不存在的作业状态流转返回未找到`() = runTest {
        val env = HomeworkTestEnv()
        env.loginAsParent()
        assertEquals(HomeworkStatusResult.NotFound, env.repository.complete(999L, Role.PARENT))
    }

    // ---- 查询 ----

    @Test
    fun `观察清单按优先级升序发射`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addTodayHomework(studentId, "A")
        env.advance(1_000)
        env.addTodayHomework(studentId, "B")

        val snapshot = env.repository.observeHomework(studentId).first()
        assertEquals(listOf("A", "B"), snapshot.map { it.content })
    }
}
