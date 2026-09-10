package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.data.db.dao.MovePositionOutcome
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进行中锁定（需求假设 C）补充单测：覆盖既有 [HomeworkRepositoryImplTest] 未验证的两类关键语义——
 *
 * 1. 拒绝原因的优先级（权限判定先于锁定判定）：
 *    - 时间维度（updateSchedule / clearSchedule）用「执行权（是否本人名下作业）」判定，
 *      学生会话操作他人名下进行中作业返回 PermissionDenied；
 *    - 调序维度（reorderHomework / moveHomeworkTo）在改删权之上**已补「作业归属学生」校验**
 *      （见 [com.assignmate.app.homework.domain.HomeworkValidators.canReorder]），
 *      故他人名下作业同样先命中 PermissionDenied，与时间维度口径一致；本人名下的进行中作业才命中锁定。
 * 2. 锁定范围的精确边界：进行中只锁「调序（reorder/move）」与「时间排定（updateSchedule/clearSchedule）」，
 *    内容修改、类型修改、删除、状态流转（完成 / 撤销完成后仍是进行中，锁定随之恢复）均不受该锁定约束。
 */
class HomeworkRepositoryLockTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 基准日与固定时钟（1_700_000_000_000ms → 上海时区 2023-11-15）保持一致 */
    private fun millisAt(hour: Int, minute: Int = 0, dayOffset: Long = 0L): Long =
        LocalDate.of(2023, 11, 15).plusDays(dayOffset)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun startAt(hour: Int, minute: Int = 0): Instant = Instant.ofEpochMilli(millisAt(hour, minute))

    private suspend fun HomeworkTestEnv.addToday(
        studentId: Long,
        content: String,
        createdBy: CreatorRole = CreatorRole.PARENT,
    ): HomeworkItem = (
        repository.addHomework(
            HomeworkTemplate(
                content = content,
                type = HomeworkType.TODAY,
                creatorRole = createdBy,
                startEpochDay = todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        ) as AddHomeworkResult.Success
        ).items.single()

    private fun HomeworkTestEnv.todayEpochDay(): Long =
        Instant.ofEpochMilli(clock.currentTimeMillis()).atZone(zone).toLocalDate().toEpochDay()

    /** 排定时间并推进到「进行中」，返回该项 id */
    private suspend fun HomeworkTestEnv.startInProgress(item: HomeworkItem, role: Role): Long {
        repository.updateSchedule(item.id, startAt(16), 30, role)
        repository.startProgress(item.id, role)
        return item.id
    }

    // ---- 1. 拒绝原因优先级：越权优先于进行中锁定 ----

    @Test
    fun `越权优先于进行中锁定调序与时间两个维度口径一致`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val ownStudentId = env.addStudent(parentId, "小明")
        val otherStudentId = env.addStudent(parentId, "小美")
        val otherItem = env.addToday(otherStudentId, "小美自己的作业", createdBy = CreatorRole.STUDENT)
        env.advance(1_000)
        val ownItem = env.addToday(ownStudentId, "小明自己的作业", createdBy = CreatorRole.STUDENT)
        env.startInProgress(otherItem, Role.PARENT)
        // 本人作业也推进为进行中（先排定 17:00 避开 16:00 已占时段）
        env.repository.updateSchedule(ownItem.id, startAt(17), 30, Role.PARENT)
        env.repository.startProgress(ownItem.id, Role.PARENT)

        env.loginAsStudent(parentId, ownStudentId)
        // 本人名下的进行中作业：权限通过，命中进行中锁定
        assertEquals(
            "本人对自己录入的进行中作业命中锁定",
            HomeworkOrderResult.LockedWorkInProgress,
            env.repository.reorderHomework(ownItem.id, ReorderDirection.DOWN, Role.STUDENT),
        )
        // 他人名下的进行中作业：调序与时间两个维度都先报越权（口径已统一）
        assertEquals(
            "他人名下作业即使已进行中，也先返回越权而不是锁定",
            HomeworkOrderResult.PermissionDenied,
            env.repository.reorderHomework(otherItem.id, ReorderDirection.DOWN, Role.STUDENT),
        )
        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.moveHomeworkTo(otherItem.id, 0, Role.STUDENT),
        )
        assertEquals(
            ScheduleUpdateResult.PermissionDenied,
            env.repository.updateSchedule(otherItem.id, startAt(18), 30, Role.STUDENT),
        )
        // 被拒后两项作业的顺序、优先级与状态均保持原样
        assertEquals(0, env.repository.getHomework(otherItem.id)?.priority)
        assertEquals(HomeworkStatus.IN_PROGRESS, env.repository.getHomework(otherItem.id)?.status)
        assertEquals(0, env.repository.getHomework(ownItem.id)?.priority)
        assertEquals(HomeworkStatus.IN_PROGRESS, env.repository.getHomework(ownItem.id)?.status)
    }

    @Test
    fun `学生不可调序他人名下的学生录入作业且数据不被改动`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val ownStudentId = env.addStudent(parentId, "小明")
        val otherStudentId = env.addStudent(parentId, "小美")
        val otherStudentItem = env.addToday(otherStudentId, "小美录入的作业", createdBy = CreatorRole.STUDENT)
        val otherParentItem = env.addToday(otherStudentId, "家长布置给小美的作业", createdBy = CreatorRole.PARENT)
        env.advance(1_000)
        env.addToday(otherStudentId, "小美录入的第二项", createdBy = CreatorRole.STUDENT)
        val before = env.repository.listHomework(otherStudentId).map { it.id to it.priority }

        env.loginAsStudent(parentId, ownStudentId)
        // 时间维度：执行权要求「本人名下」，跨学生一律 PermissionDenied
        assertEquals(
            ScheduleUpdateResult.PermissionDenied,
            env.repository.updateSchedule(otherStudentItem.id, startAt(16), 30, Role.STUDENT),
        )
        // 调序维度：家长录入项被改删权拒绝（既有语义）
        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.reorderHomework(otherParentItem.id, ReorderDirection.UP, Role.STUDENT),
        )
        // 调序维度：学生录入项但归属他人名下——归属校验补齐后同样拒绝（跨学生越权面已封）
        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.reorderHomework(otherStudentItem.id, ReorderDirection.DOWN, Role.STUDENT),
        )
        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.moveHomeworkTo(otherStudentItem.id, 1, Role.STUDENT),
        )
        // 顺序与优先级完全未变
        assertEquals(before, env.repository.listHomework(otherStudentId).map { it.id to it.priority })
    }

    @Test
    fun `家长仍可调序名下学生的任意来源作业`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val parentItem = env.addToday(studentId, "家长布置的作业", createdBy = CreatorRole.PARENT)
        env.advance(1_000)
        env.addToday(studentId, "学生录入的作业", createdBy = CreatorRole.STUDENT)

        // 家长会话（无 sessionStudentId）：学生录入项与家长录入项都可调序
        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.reorderHomework(parentItem.id, ReorderDirection.DOWN, Role.PARENT),
        )
        assertEquals(
            listOf("学生录入的作业", "家长布置的作业"),
            env.repository.listHomework(studentId).map { it.content },
        )
        val studentRecorded = env.repository.listHomework(studentId).last()
        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.moveHomeworkTo(studentRecorded.id, 0, Role.PARENT),
        )
        assertEquals(
            listOf("家长布置的作业", "学生录入的作业"),
            env.repository.listHomework(studentId).map { it.content },
        )
    }

    @Test
    fun `越权排定与撤销他人进行中作业时间返回权限不足而非锁定`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val ownStudentId = env.addStudent(parentId, "小明")
        val otherStudentId = env.addStudent(parentId, "小美")
        val otherItem = env.addToday(otherStudentId, "小美的作业")
        val start = startAt(16)
        env.startInProgress(otherItem, Role.PARENT)

        env.loginAsStudent(parentId, ownStudentId)
        assertEquals(
            ScheduleUpdateResult.PermissionDenied,
            env.repository.updateSchedule(otherItem.id, startAt(17), 45, Role.STUDENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.clearSchedule(otherItem.id, Role.STUDENT),
        )
        // 原排定与状态未被改动
        val current = env.repository.getHomework(otherItem.id)!!
        assertEquals(HomeworkStatus.IN_PROGRESS, current.status)
        assertEquals(start, current.startTime)
        assertEquals(30, current.estimatedMinutes)
    }

    // ---- 2. 锁定边界：内容 / 类型 / 删除 / 完成 / 撤销完成后仍锁定 ----

    @Test
    fun `进行中作业仍可修改内容且状态保持进行中`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = env.addToday(studentId, "语文练习")
        env.startInProgress(item, Role.PARENT)

        val updated = env.repository.updateContent(item.id, "  语文练习（已补充）  ", Role.PARENT)
        val success = updated as? HomeworkOperationResult.Success

        assertNotNull("进行中锁定不约束内容修改，实际：$updated", success)
        assertEquals("内容应被修剪后落库", "语文练习（已补充）", success!!.item.content)
        assertEquals("修改内容不得改变状态", HomeworkStatus.IN_PROGRESS, success.item.status)
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            env.repository.getHomework(item.id)?.status,
        )
    }

    @Test
    fun `进行中作业仍可修改类型与截止时间`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = env.addToday(studentId, "作业")
        env.startInProgress(item, Role.PARENT)
        val newDeadline = Instant.ofEpochMilli(millisAt(20))

        val result = env.repository.updateTemplate(
            item.id,
            HomeworkType.TODAY,
            null,
            newDeadline,
            Role.PARENT,
        )

        val success = result as? HomeworkOperationResult.Success

        assertNotNull("进行中锁定不约束类型 / 截止时间修改（家长专属），实际：$result", success)
        assertEquals(newDeadline, success!!.item.deadline)
        assertEquals(HomeworkStatus.IN_PROGRESS, success.item.status)
    }

    @Test
    fun `进行中作业仍可删除`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = env.addToday(studentId, "要删除的进行中作业")
        env.startInProgress(item, Role.PARENT)

        val deleted = env.repository.deleteHomework(item.id, Role.PARENT)

        assertTrue("进行中锁定不约束删除（沿用改删权）", deleted is HomeworkOperationResult.Success)
        assertNull(env.repository.getHomework(item.id))
    }

    @Test
    fun `进行中作业可标记完成并撤销完成且撤销后重新落入锁定`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = env.addToday(studentId, "数学练习")
        val start = startAt(16)
        env.startInProgress(item, Role.PARENT)

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
        // 撤销完成后仍是「进行中」：锁定随之恢复
        assertEquals(
            ScheduleUpdateResult.LockedWorkInProgress,
            env.repository.updateSchedule(item.id, startAt(17), 30, Role.PARENT),
        )
        assertEquals(
            HomeworkOrderResult.LockedWorkInProgress,
            env.repository.reorderHomework(item.id, ReorderDirection.DOWN, Role.PARENT),
        )
        assertEquals(start, env.repository.getHomework(item.id)?.startTime)
    }

    // ---- 3. 既有行为不回归（锁定生效前后的对照） ----

    @Test
    fun `已完成作业仍可调序但不可排定时间而进行中项两者皆禁`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val completed = env.addToday(studentId, "A")
        env.advance(1_000)
        val inProgress = env.addToday(studentId, "B")
        // A：待完成 → 已完成；B：进行中
        env.repository.markPending(completed.id, Role.PARENT)
        env.repository.complete(completed.id, Role.PARENT)
        env.startInProgress(inProgress, Role.PARENT)

        // 已完成：调序成功（既有行为不回归），但不可排定时间（既有约束）
        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.reorderHomework(completed.id, ReorderDirection.DOWN, Role.PARENT),
        )
        assertEquals(
            ScheduleUpdateResult.StatusTransitionDenied,
            env.repository.updateSchedule(completed.id, startAt(18), 30, Role.PARENT),
        )

        // 进行中：调序与排定时间双双被锁定
        assertEquals(
            HomeworkOrderResult.LockedWorkInProgress,
            env.repository.reorderHomework(inProgress.id, ReorderDirection.UP, Role.PARENT),
        )
        assertEquals(
            ScheduleUpdateResult.LockedWorkInProgress,
            env.repository.updateSchedule(inProgress.id, startAt(19), 30, Role.PARENT),
        )

        // 两项状态与条目数未被破坏（仅 completed 完成了一次合法交换）
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            env.repository.getHomework(inProgress.id)?.status,
        )
        assertEquals(
            HomeworkStatus.COMPLETED,
            env.repository.getHomework(completed.id)?.status,
        )
        assertEquals(2, env.repository.listHomework(studentId).size)
    }

    @Test
    fun `待完成作业仍可调序改时间与撤销排定`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val first = env.addToday(studentId, "A")
        env.advance(1_000)
        val second = env.addToday(studentId, "B")
        env.repository.updateSchedule(second.id, startAt(16), 30, Role.PARENT)
        assertEquals(
            HomeworkStatus.PENDING,
            env.repository.getHomework(second.id)?.status,
        )

        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.moveHomeworkTo(second.id, 0, Role.PARENT),
        )
        assertEquals(
            listOf("B", "A"),
            env.repository.listHomework(studentId).map { it.content },
        )
        val rescheduled = env.repository.updateSchedule(second.id, startAt(17), 45, Role.PARENT)
        assertTrue("待完成作业仍可改时间", rescheduled is ScheduleUpdateResult.Success)
        val cleared = env.repository.clearSchedule(second.id, Role.PARENT) as? HomeworkOperationResult.Success
        assertNotNull("待完成作业仍可撤销排定", cleared)
        assertEquals(HomeworkStatus.RECORDED, cleared!!.item.status)
        assertNull(cleared.item.startTime)
        assertNotNull(env.repository.getHomework(first.id))
    }

    // ---- 4. 落位重排的事务化路径（DAO @Transaction 内重读 + 写入前复查锁定） ----

    @Test
    fun `事务化落位重排按新顺序整体重写优先级`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addToday(studentId, "A")
        env.advance(1_000)
        env.addToday(studentId, "B")
        env.advance(1_000)
        val c = env.addToday(studentId, "C")

        // 事务方法签名与密封结果由仓库层传参/映射（这里直接验证 DAO 事务方法的落库语义）
        val outcome = env.homeworkDao.moveToPositionInTransaction(c.id, 0)

        assertEquals(MovePositionOutcome.SUCCESS, outcome)
        assertEquals(
            listOf("C", "A", "B"),
            env.repository.listHomework(studentId).map { it.content },
        )
        assertEquals(listOf(0, 1, 2), env.repository.listHomework(studentId).map { it.priority })
    }

    @Test
    fun `事务方法内复查进行中锁定故不落库且顺序不变`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addToday(studentId, "A")
        env.advance(1_000)
        val locked = env.addToday(studentId, "B")
        val before = env.repository.listHomework(studentId).map { it.id to it.priority }
        env.startInProgress(locked, Role.PARENT)

        val outcome = env.homeworkDao.moveToPositionInTransaction(locked.id, 0)

        assertEquals(MovePositionOutcome.LOCKED, outcome)
        assertEquals(before, env.repository.listHomework(studentId).map { it.id to it.priority })
    }

    @Test
    fun `事务方法对不存在的作业返回未找到`() = runTest {
        val env = HomeworkTestEnv()
        env.loginAsParent()

        assertEquals(
            MovePositionOutcome.NOT_FOUND,
            env.homeworkDao.moveToPositionInTransaction(999L, 0),
        )
    }

    @Test
    fun `并发置为进行中时事务内复查拦下重排且优先级不变`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addToday(studentId, "A")
        env.advance(1_000)
        val target = env.addToday(studentId, "B")
        val before = env.repository.listHomework(studentId).map { it.id to it.priority }
        // 模拟「仓库读取并校验通过之后、事务写入之前」该作业被并发置为进行中：
        // 事务方法内重读状态时应拦下这次已失效的重排
        env.homeworkDao.onNextTransactionEnter = { rows ->
            val index = rows.indexOfFirst { it.id == target.id }
            rows[index] = rows[index].copy(status = HomeworkStatus.IN_PROGRESS.name)
        }

        assertEquals(
            "事务内复查应拦下已失效的重排，实际顺序：${env.repository.listHomework(studentId).map { it.id }}",
            HomeworkOrderResult.LockedWorkInProgress,
            env.repository.moveHomeworkTo(target.id, 0, Role.PARENT),
        )
        assertEquals(before, env.repository.listHomework(studentId).map { it.id to it.priority })
    }

    @Test
    fun `事务方法在目标位置与当前位置相同时幂等成功且不重写优先级`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addToday(studentId, "A")
        env.advance(1_000)
        val b = env.addToday(studentId, "B")
        env.advance(1_000)
        env.addToday(studentId, "C")
        val before = env.repository.listHomework(studentId).map { it.id to it.priority }

        assertEquals(MovePositionOutcome.SUCCESS, env.homeworkDao.moveToPositionInTransaction(b.id, 1))

        assertEquals(listOf("A", "B", "C"), env.repository.listHomework(studentId).map { it.content })
        assertEquals(before, env.repository.listHomework(studentId).map { it.id to it.priority })
    }

    @Test
    fun `事务方法对越界目标位置按边界收敛`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val a = env.addToday(studentId, "A")
        env.advance(1_000)
        env.addToday(studentId, "B")
        env.advance(1_000)
        env.addToday(studentId, "C")

        // 越界（含负值）一律收敛到清单两端，不抛异常
        assertEquals(MovePositionOutcome.SUCCESS, env.homeworkDao.moveToPositionInTransaction(a.id, 99))
        assertEquals(listOf("B", "C", "A"), env.repository.listHomework(studentId).map { it.content })
        assertEquals(listOf(0, 1, 2), env.repository.listHomework(studentId).map { it.priority })

        assertEquals(MovePositionOutcome.SUCCESS, env.homeworkDao.moveToPositionInTransaction(a.id, -5))
        assertEquals(listOf("A", "B", "C"), env.repository.listHomework(studentId).map { it.content })
        assertEquals(listOf(0, 1, 2), env.repository.listHomework(studentId).map { it.priority })
    }

    @Test
    fun `事务方法对进行中作业的锁定判定优先于幂等判定`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addToday(studentId, "A")
        env.advance(1_000)
        val locked = env.addToday(studentId, "B")
        val before = env.repository.listHomework(studentId).map { it.id to it.priority }
        env.startInProgress(locked, Role.PARENT)

        // 目标位置与当前位置一致（1）：仍必须回报 LOCKED 而不是幂等成功，
        // 否则 UI 会把「已开始作业」误判为可调整顺序
        assertEquals(MovePositionOutcome.LOCKED, env.homeworkDao.moveToPositionInTransaction(locked.id, 1))
        assertEquals(before, env.repository.listHomework(studentId).map { it.id to it.priority })
    }

    @Test
    fun `事务前作业被并发删除时落位返回未找到`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.addToday(studentId, "A")
        env.advance(1_000)
        val target = env.addToday(studentId, "B")
        val survivor = env.repository.listHomework(studentId).first { it.id != target.id }
        // 模拟「仓库读取并校验通过之后、事务写入之前」该作业被并发删除
        env.homeworkDao.onNextTransactionEnter = { rows ->
            rows.removeAll { it.id == target.id }
        }

        assertEquals(
            HomeworkOrderResult.NotFound,
            env.repository.moveHomeworkTo(target.id, 0, Role.PARENT),
        )
        assertEquals(listOf(survivor.id), env.repository.listHomework(studentId).map { it.id })
    }
}