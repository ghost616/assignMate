package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
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
 * 家长归属围栏统一化单测：所有「按 id 入口」都必须补齐「作业所属学生必须在当前家长名下」维度
 * （改删权 / 调序权 / 执行权三个既有维度语义不变，仅新增归属维）。
 *
 * 覆盖三类：
 * 1. 家长跨学生越权被拒：调序 / 落位调序 / 时间排定 / 撤销排定 / 改内容 / 改类型 / 删除 /
 *    状态流转（待完成 / 进行中 / 完成 / 撤销完成）逐入口断言 PermissionDenied，且失败后
 *    作业数据（内容、优先级、状态、排定、存在性）逐项保持原样——判定顺序为
 *    「会话有效性 + 权限维度在前、归属维随后」，失败不改动任何数据；
 * 2. 学生会话的家属内跨学生分支与时间维度既有口径一致（同一家长名下他人学生一律拒绝）；
 * 3. 回归：家长对名下学生的作业在全部入口仍可成功。
 *
 * 归属口径复用 auth 的统一能力（AuthRepository.isStudentOwnedBy），故本套件同时是
 * 「homework 与 auth 归属口径一致」的守门用例。
 */
class HomeworkRepositoryOwnershipTest {

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

    /** 场景数据：当前会话下的「本人名下作业」与「归属他人（他人家长 / 同家长另一学生）的作业」 */
    private class OwnershipScenario(
        val env: HomeworkTestEnv,
        val own: HomeworkItem,
        val foreign: HomeworkItem,
    ) {
        /** 以作业归属学生那位家长的会话继续操作（越权用例的前置：先合法排定/推进，再切回越权会话） */
        suspend fun loginAsForeignStudentParent() {
            env.authRepository.loginParent("parent002", HomeworkTestEnv.PASSWORD)
        }

        /** 切回原会话（parent001）后发起越权调用 */
        suspend fun loginAsOwner() {
            env.authRepository.loginParent("parent001", HomeworkTestEnv.PASSWORD)
        }
    }

    /**
     * 构造「当前会话为 parent001，而目标作业归属 parent002 名下学生」的场景：
     * 返回时会话停在 parent002（便于用例先以「作业归属家长」合法地排定时间/推进状态），
     * 用例再经 [OwnershipScenario.loginAsOwner] 切回 parent001 发起越权调用。
     */
    private suspend fun crossParentScenario(): OwnershipScenario {
        val env = HomeworkTestEnv()
        val firstParentId = env.loginAsParent("parent001")
        val ownStudentId = env.addStudent(firstParentId, "小明")
        val own = env.addToday(ownStudentId, "小明的作业", createdBy = CreatorRole.PARENT)
        val otherParentId = env.loginAsParent("parent002")
        val otherStudentId = env.addStudent(otherParentId, "小美")
        val foreign = env.addToday(otherStudentId, "小美的作业", createdBy = CreatorRole.PARENT)
        return OwnershipScenario(env, own, foreign)
    }

    /**
     * 构造「当前会话为学生本人，目标作业归属同一家长名下另一个学生」的场景
     * （归属围栏的家属内跨学生分支，必须与时间维度既有口径一致地拒绝）。
     */
    private suspend fun siblingStudentScenario(): OwnershipScenario {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val ownStudentId = env.addStudent(parentId, "小明")
        val otherStudentId = env.addStudent(parentId, "小美")
        val foreign = env.addToday(otherStudentId, "小美自己的作业", createdBy = CreatorRole.STUDENT)
        // 第二位学生名下的第二项由家长会话录入（越权用例需要一项「可调序」的目标位置）
        env.addToday(otherStudentId, "家长布置给小美的作业", createdBy = CreatorRole.PARENT)
        env.loginAsStudent(parentId, ownStudentId)
        val own = env.addToday(ownStudentId, "小明自己的作业", createdBy = CreatorRole.STUDENT)
        return OwnershipScenario(env, own, foreign)
    }

    /** 某学生清单的 (id, priority) 快照，用于断言「越权被拒后顺序未被改动」 */
    private suspend fun HomeworkTestEnv.prioritySnapshot(studentId: Long): List<Pair<Long, Int>> =
        repository.listHomework(studentId).map { it.id to it.priority }

    // ---- 1. 家长跨学生越权：调序维度（reorderHomework / moveHomeworkTo） ----

    @Test
    fun `家长不可调序他人名下学生的作业且优先级不变`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        val studentId = scenario.foreign.studentId
        env.addToday(studentId, "小美的第二项")
        val before = env.prioritySnapshot(studentId)
        scenario.loginAsOwner()

        assertEquals(
            "跨家长调序必须被归属围栏拒绝",
            HomeworkOrderResult.PermissionDenied,
            env.repository.reorderHomework(scenario.foreign.id, ReorderDirection.UP, Role.PARENT),
        )
        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.reorderHomework(scenario.foreign.id, ReorderDirection.DOWN, Role.PARENT),
        )
        assertEquals("越权被拒后顺序与优先级不得改动", before, env.prioritySnapshot(studentId))
    }

    @Test
    fun `家长不可为他人名下学生的作业落位调序且顺序不变`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        val studentId = scenario.foreign.studentId
        env.addToday(studentId, "小美的第二项")
        val before = env.prioritySnapshot(studentId)
        scenario.loginAsOwner()

        assertEquals(
            "跨家长落位调序必须被归属围栏拒绝",
            HomeworkOrderResult.PermissionDenied,
            env.repository.moveHomeworkTo(scenario.foreign.id, 0, Role.PARENT),
        )
        assertEquals("越权被拒后顺序与优先级不得改动", before, env.prioritySnapshot(studentId))
    }

    @Test
    fun `家长不可排定他人名下学生作业的时间且未落库`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        scenario.loginAsOwner()

        assertEquals(
            "跨家长时间排定必须被归属围栏拒绝",
            ScheduleUpdateResult.PermissionDenied,
            env.repository.updateSchedule(scenario.foreign.id, startAt(16), 30, Role.PARENT),
        )
        val current = env.repository.getHomework(scenario.foreign.id)!!
        assertNull("越权被拒后不得写入开始时间", current.startTime)
        assertNull(current.estimatedMinutes)
        assertEquals(HomeworkStatus.RECORDED, current.status)
    }

    @Test
    fun `家长不可撤销他人名下学生作业的排定且排定保持原样`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        // 由作业归属家长（parent002）合法排定后，再切回 parent001 尝试撤销
        env.repository.updateSchedule(scenario.foreign.id, startAt(16), 30, Role.PARENT)
        scenario.loginAsOwner()

        assertEquals(
            "跨家长撤销排定必须被归属围栏拒绝",
            HomeworkOperationResult.PermissionDenied,
            env.repository.clearSchedule(scenario.foreign.id, Role.PARENT),
        )
        val current = env.repository.getHomework(scenario.foreign.id)!!
        assertEquals("越权被拒后排定时间段必须保持原样", startAt(16), current.startTime)
        assertEquals(30, current.estimatedMinutes)
        assertEquals(HomeworkStatus.PENDING, current.status)
    }

    @Test
    fun `家长不可修改他人名下学生作业的内容且内容不变`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        scenario.loginAsOwner()

        assertEquals(
            "跨家长改内容必须被归属围栏拒绝",
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateContent(scenario.foreign.id, "越权修改", Role.PARENT),
        )
        assertEquals(
            "越权被拒后内容不得改动",
            "小美的作业",
            env.repository.getHomework(scenario.foreign.id)?.content,
        )
    }

    @Test
    fun `家长不可修改他人名下学生作业的类型与截止时间`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        scenario.loginAsOwner()

        assertEquals(
            "跨家长改类型必须被归属围栏拒绝",
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateTemplate(
                scenario.foreign.id,
                HomeworkType.TODAY,
                null,
                Instant.ofEpochMilli(millisAt(21)),
                Role.PARENT,
            ),
        )
        assertNull(env.repository.getHomework(scenario.foreign.id)?.deadline)
    }

    @Test
    fun `学生会话冒充家长角色调用改类型同样被拒绝`() = runTest {
        val scenario = siblingStudentScenario()
        val env = scenario.env
        val own = scenario.own
        val before = env.repository.getHomework(own.id)!!

        // 角色一致性属于统一判定的一部分：传入角色与会话真实角色不一致一律拒绝，
        // 否则学生会话误传 Role.PARENT 即可改本人名下作业的类型/阶段范围/截止时间
        val result = env.repository.updateTemplate(
            own.id,
            HomeworkType.STAGE,
            StageRange.ONE_WEEK,
            Instant.ofEpochMilli(millisAt(21, dayOffset = 10)),
            Role.PARENT,
        )

        assertEquals(HomeworkOperationResult.PermissionDenied, result)
        assertEquals("越权被拒后类型不得改动", before.type, env.repository.getHomework(own.id)?.type)
        assertNull(env.repository.getHomework(own.id)?.deadline)
    }

    @Test
    fun `家长不可删除他人名下学生的作业`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        scenario.loginAsOwner()

        assertEquals(
            "跨家长删除必须被归属围栏拒绝",
            HomeworkOperationResult.PermissionDenied,
            env.repository.deleteHomework(scenario.foreign.id, Role.PARENT),
        )
        assertNotNull("越权被拒后作业必须仍然存在", env.repository.getHomework(scenario.foreign.id))
    }

    // ---- 2. 家长跨学生越权：状态流转（markPending / startProgress / complete / reopen） ----

    @Test
    fun `家长不可推进他人名下学生作业的状态且状态不变`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        scenario.loginAsOwner()

        assertEquals(
            "跨家长标记待完成必须被归属围栏拒绝",
            HomeworkStatusResult.PermissionDenied,
            env.repository.markPending(scenario.foreign.id, Role.PARENT),
        )
        // 排定由作业归属家长完成，完成后目标状态为「待完成」，故 startProgress 是合法的下一步流转
        scenario.loginAsForeignStudentParent()
        assertTrue(
            env.repository.updateSchedule(scenario.foreign.id, startAt(16), 30, Role.PARENT)
                is ScheduleUpdateResult.Success,
        )
        scenario.loginAsOwner()
        assertEquals(
            "跨家长开始作业必须被归属围栏拒绝",
            HomeworkStatusResult.PermissionDenied,
            env.repository.startProgress(scenario.foreign.id, Role.PARENT),
        )
        assertEquals(
            "跨家长标记完成必须被归属围栏拒绝",
            HomeworkStatusResult.PermissionDenied,
            env.repository.complete(scenario.foreign.id, Role.PARENT),
        )
        assertEquals(
            HomeworkStatus.PENDING,
            env.repository.getHomework(scenario.foreign.id)?.status,
        )
    }

    @Test
    fun `家长不可撤销他人名下学生作业的完成且状态保持已完成`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        // 由作业归属家长合法推进到「已完成」，再切回 parent001 尝试撤销完成
        env.repository.updateSchedule(scenario.foreign.id, startAt(16), 30, Role.PARENT)
        env.repository.startProgress(scenario.foreign.id, Role.PARENT)
        env.repository.complete(scenario.foreign.id, Role.PARENT)
        assertEquals(HomeworkStatus.COMPLETED, env.repository.getHomework(scenario.foreign.id)?.status)
        scenario.loginAsOwner()

        assertEquals(
            "跨家长撤销完成必须被归属围栏拒绝",
            HomeworkStatusResult.PermissionDenied,
            env.repository.reopen(scenario.foreign.id, Role.PARENT),
        )
        assertEquals(
            "越权被拒后状态必须保持已完成",
            HomeworkStatus.COMPLETED,
            env.repository.getHomework(scenario.foreign.id)?.status,
        )
    }

    @Test
    fun `家长操作他人名下学生的进行中作业报越权而非锁定`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        env.startInProgress(scenario.foreign, Role.PARENT)
        assertEquals(HomeworkStatus.IN_PROGRESS, env.repository.getHomework(scenario.foreign.id)?.status)
        scenario.loginAsOwner()

        // 判定顺序：归属维在锁定之前，故越权一律报权限不足（不泄漏「该作业正在进行中」）
        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.reorderHomework(scenario.foreign.id, ReorderDirection.DOWN, Role.PARENT),
        )
        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.moveHomeworkTo(scenario.foreign.id, 0, Role.PARENT),
        )
        assertEquals(
            ScheduleUpdateResult.PermissionDenied,
            env.repository.updateSchedule(scenario.foreign.id, startAt(17), 30, Role.PARENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.clearSchedule(scenario.foreign.id, Role.PARENT),
        )
        assertEquals(
            HomeworkStatusResult.PermissionDenied,
            env.repository.complete(scenario.foreign.id, Role.PARENT),
        )
        val current = env.repository.getHomework(scenario.foreign.id)!!
        assertEquals(HomeworkStatus.IN_PROGRESS, current.status)
        assertEquals(startAt(16), current.startTime)
    }

    // ---- 3. 学生会话的家属内跨学生越权（与时间维度既有口径一致） ----

    @Test
    fun `学生会话不可调序或落位同一家长名下他人学生的作业`() = runTest {
        val scenario = siblingStudentScenario()
        val env = scenario.env
        val studentId = scenario.foreign.studentId
        val before = env.prioritySnapshot(studentId)

        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.reorderHomework(scenario.foreign.id, ReorderDirection.DOWN, Role.STUDENT),
        )
        assertEquals(
            HomeworkOrderResult.PermissionDenied,
            env.repository.moveHomeworkTo(scenario.foreign.id, 1, Role.STUDENT),
        )
        assertEquals("越权被拒后顺序与优先级不得改动", before, env.prioritySnapshot(studentId))
    }

    @Test
    fun `学生会话不可改删同一家长名下他人学生的作业`() = runTest {
        val scenario = siblingStudentScenario()
        val env = scenario.env

        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateContent(scenario.foreign.id, "越权修改", Role.STUDENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.deleteHomework(scenario.foreign.id, Role.STUDENT),
        )
        assertEquals(
            "小美自己的作业",
            env.repository.getHomework(scenario.foreign.id)?.content,
        )
        assertNotNull(env.repository.getHomework(scenario.foreign.id))
    }

    @Test
    fun `学生会话不可排定或流转同一家长名下他人学生作业的状态`() = runTest {
        val scenario = siblingStudentScenario()
        val env = scenario.env

        assertEquals(
            ScheduleUpdateResult.PermissionDenied,
            env.repository.updateSchedule(scenario.foreign.id, startAt(16), 30, Role.STUDENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.clearSchedule(scenario.foreign.id, Role.STUDENT),
        )
        assertEquals(
            HomeworkStatusResult.PermissionDenied,
            env.repository.markPending(scenario.foreign.id, Role.STUDENT),
        )
        assertEquals(
            HomeworkStatusResult.PermissionDenied,
            env.repository.startProgress(scenario.foreign.id, Role.STUDENT),
        )
        assertEquals(
            HomeworkStatusResult.PermissionDenied,
            env.repository.complete(scenario.foreign.id, Role.STUDENT),
        )
        val current = env.repository.getHomework(scenario.foreign.id)!!
        assertEquals(HomeworkStatus.RECORDED, current.status)
        assertNull(current.startTime)
    }

    // ---- 4. 回归：家长操作名下学生的作业仍全部可用 ----

    @Test
    fun `家长对名下学生的作业全部入口仍可成功`() = runTest {
        val scenario = crossParentScenario()
        val env = scenario.env
        val studentId = scenario.own.studentId
        // 切回本人名下学生的家长（parent001）会话：本用例验证「名下学生」不回归
        scenario.loginAsOwner()
        val second = env.addToday(studentId, "学生录入的作业", createdBy = CreatorRole.STUDENT)

        // 调序：家长可调整名下学生任意来源作业
        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.reorderHomework(scenario.own.id, ReorderDirection.DOWN, Role.PARENT),
        )
        assertEquals(
            listOf("学生录入的作业", "小明的作业"),
            env.repository.listHomework(studentId).map { it.content },
        )
        // 落位调序
        assertEquals(
            HomeworkOrderResult.Success,
            env.repository.moveHomeworkTo(scenario.own.id, 0, Role.PARENT),
        )
        assertEquals(
            listOf("小明的作业", "学生录入的作业"),
            env.repository.listHomework(studentId).map { it.content },
        )
        // 改内容
        assertTrue(
            env.repository.updateContent(scenario.own.id, "小明的作业（已改）", Role.PARENT)
                is HomeworkOperationResult.Success,
        )
        // 改类型 / 截止时间（家长专属）
        assertTrue(
            env.repository.updateTemplate(
                scenario.own.id,
                HomeworkType.TODAY,
                null,
                Instant.ofEpochMilli(millisAt(20)),
                Role.PARENT,
            ) is HomeworkOperationResult.Success,
        )
        // 时间排定与撤销排定
        val scheduled = env.repository.updateSchedule(second.id, startAt(16), 30, Role.PARENT)
        assertEquals(HomeworkStatus.PENDING, (scheduled as ScheduleUpdateResult.Success).item.status)
        val cleared = env.repository.clearSchedule(second.id, Role.PARENT) as HomeworkOperationResult.Success
        assertEquals(HomeworkStatus.RECORDED, cleared.item.status)
        // 状态流转：待完成 → 进行中 → 已完成 → 撤销完成
        assertEquals(
            HomeworkStatus.PENDING,
            (env.repository.markPending(second.id, Role.PARENT) as HomeworkStatusResult.Success).item.status,
        )
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            (env.repository.startProgress(second.id, Role.PARENT) as HomeworkStatusResult.Success).item.status,
        )
        assertEquals(
            HomeworkStatus.COMPLETED,
            (env.repository.complete(second.id, Role.PARENT) as HomeworkStatusResult.Success).item.status,
        )
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            (env.repository.reopen(second.id, Role.PARENT) as HomeworkStatusResult.Success).item.status,
        )
        // 删除
        assertTrue(
            env.repository.deleteHomework(scenario.own.id, Role.PARENT)
                is HomeworkOperationResult.Success,
        )
        assertNull(env.repository.getHomework(scenario.own.id))
    }
}
