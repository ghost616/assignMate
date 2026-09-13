package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.db.entity.HomeworkItemEntity
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidationError
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「统一家长归属围栏」的补充验证套件（与既有 HomeworkRepositoryOwnershipTest 互补，不改动其内容）：
 *
 * 1. 边界值 / 极限值：不存在的作业 id、无会话（登出）、会话无家长 id、角色与会话不一致、
 *    归属档案被删除后的悬空归属、内容空白与超长边界；
 * 2. 权限与状态机：进行中锁定语义不回归（本人名下锁定 vs 他人名下越权优先）、
 *    学生主闭环（家长布置 → 学生排定/流转）与 canModify 语义边界；
 * 3. 失败路径不改动数据：内容 / 优先级 / 状态 / 排定 / 存在性逐项断言；
 * 4. 归属口径与 auth 一致：以 AuthRepository.isStudentOwnedBy 与仓库结果交叉验证。
 */
class HomeworkOwnershipFenceExtraTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 与 FIXED_MILLIS（2023-11-14T22:13:20Z → 上海 2023-11-15）同一基准日 */
    private fun millisAt(hour: Int, minute: Int = 0): Long =
        LocalDate.of(2023, 11, 15)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun startAt(hour: Int): Instant = Instant.ofEpochMilli(millisAt(hour))

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

    /** 全部按 id 入口（含 reopen 共 11 个）在给定会话角色下的调用结果断言为「无权限」 */
    private suspend fun assertAllEntriesDenied(
        repository: HomeworkRepository,
        id: Long,
        role: Role,
        includeTemplate: Boolean = true,
    ) {
        assertEquals(
            "reorderHomework 应拒绝",
            HomeworkOrderResult.PermissionDenied,
            repository.reorderHomework(id, ReorderDirection.UP, role),
        )
        assertEquals(
            "moveHomeworkTo 应拒绝",
            HomeworkOrderResult.PermissionDenied,
            repository.moveHomeworkTo(id, 0, role),
        )
        assertEquals(
            "updateSchedule 应拒绝",
            ScheduleUpdateResult.PermissionDenied,
            repository.updateSchedule(id, startAt(16), 30, role),
        )
        assertEquals(
            "clearSchedule 应拒绝",
            HomeworkOperationResult.PermissionDenied,
            repository.clearSchedule(id, role),
        )
        assertEquals(
            "updateContent 应拒绝",
            HomeworkOperationResult.PermissionDenied,
            repository.updateContent(id, "越权修改", role),
        )
        if (includeTemplate) {
            assertEquals(
                "updateTemplate 应拒绝",
                HomeworkOperationResult.PermissionDenied,
                repository.updateTemplate(id, HomeworkType.TODAY, null, Instant.ofEpochMilli(millisAt(21)), role),
            )
        }
        assertEquals(
            "deleteHomework 应拒绝",
            HomeworkOperationResult.PermissionDenied,
            repository.deleteHomework(id, role),
        )
        assertEquals(
            "markPending 应拒绝",
            HomeworkStatusResult.PermissionDenied,
            repository.markPending(id, role),
        )
        assertEquals(
            "startProgress 应拒绝",
            HomeworkStatusResult.PermissionDenied,
            repository.startProgress(id, role),
        )
        assertEquals(
            "complete 应拒绝",
            HomeworkStatusResult.PermissionDenied,
            repository.complete(id, role),
        )
        assertEquals(
            "reopen 应拒绝",
            HomeworkStatusResult.PermissionDenied,
            repository.reopen(id, role),
        )
    }

    private suspend fun HomeworkTestEnv.prioritySnapshot(studentId: Long): List<Pair<Long, Int>> =
        repository.listHomework(studentId).map { it.id to it.priority }

    // ---- 1. 边界值：作业不存在 ----

    @Test
    fun `不存在的作业 id 在全部入口返回 NotFound`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent("parent001")
        env.addStudent(parentId, "小明")
        val missing = 987_654_321L

        assertEquals(
            HomeworkOrderResult.NotFound,
            env.repository.reorderHomework(missing, ReorderDirection.UP, Role.PARENT),
        )
        assertEquals(HomeworkOrderResult.NotFound, env.repository.moveHomeworkTo(missing, 0, Role.PARENT))
        assertEquals(
            ScheduleUpdateResult.NotFound,
            env.repository.updateSchedule(missing, startAt(16), 30, Role.PARENT),
        )
        assertEquals(HomeworkOperationResult.NotFound, env.repository.clearSchedule(missing, Role.PARENT))
        assertEquals(HomeworkOperationResult.NotFound, env.repository.updateContent(missing, "x", Role.PARENT))
        assertEquals(
            "updateTemplate 的「不存在」必须先于角色/归属判定",
            HomeworkOperationResult.NotFound,
            env.repository.updateTemplate(missing, HomeworkType.TODAY, null, null, Role.PARENT),
        )
        assertEquals(
            "学生会话下不存在的作业同样返回 NotFound（存在性判定最前）",
            HomeworkOperationResult.NotFound,
            env.repository.updateTemplate(missing, HomeworkType.TODAY, null, null, Role.STUDENT),
        )
        assertEquals(HomeworkOperationResult.NotFound, env.repository.deleteHomework(missing, Role.PARENT))
        assertEquals(HomeworkStatusResult.NotFound, env.repository.markPending(missing, Role.PARENT))
        assertEquals(HomeworkStatusResult.NotFound, env.repository.startProgress(missing, Role.PARENT))
        assertEquals(HomeworkStatusResult.NotFound, env.repository.complete(missing, Role.PARENT))
        assertEquals(HomeworkStatusResult.NotFound, env.repository.reopen(missing, Role.PARENT))
    }

    // ---- 2. 边界值：无会话（登出）/ 会话无家长 id / 角色与会话不一致 ----

    @Test
    fun `登出后全部入口一律拒绝且作业数据保持原样`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent("parent001")
        val studentId = env.addStudent(parentId, "小明")
        val item = env.addToday(studentId, "小明的作业")
        env.addToday(studentId, "小明的第二项")
        val before = env.prioritySnapshot(studentId)
        env.logout()

        assertAllEntriesDenied(env.repository, item.id, Role.PARENT)

        val current = env.repository.getHomework(item.id)
        assertNotNull("无会话被拒后作业必须仍然存在", current)
        assertEquals("小明的作业", current?.content)
        assertEquals(HomeworkStatus.RECORDED, current?.status)
        assertNull(current?.startTime)
        assertNull(current?.estimatedMinutes)
        assertEquals("无会话被拒后优先级顺序不得改动", before, env.prioritySnapshot(studentId))
    }

    @Test
    fun `家长会话缺少家长 id 时全部入口一律拒绝`() = runTest {
        // 会话标记为家长但缺少归属主体（异常会话）：归属围栏必须收敛为拒绝
        val direct = DirectSessionEnv(SessionState(role = Role.PARENT, parentId = null, studentId = null))
        val item = direct.seed(studentId = 7L)

        assertAllEntriesDenied(direct.repository, item.id, Role.PARENT)
        assertNotNull(direct.repository.getHomework(item.id))
        assertEquals(HomeworkStatus.RECORDED, direct.repository.getHomework(item.id)?.status)
    }

    @Test
    fun `角色与会话不一致时全部入口按无权限拒绝`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent("parent001")
        val studentId = env.addStudent(parentId, "小明")
        val item = env.addToday(studentId, "小明的作业")
        env.addToday(studentId, "小明的第二项")
        val before = env.prioritySnapshot(studentId)

        // 家长会话冒充学生会话：全部入口一律拒绝
        assertAllEntriesDenied(env.repository, item.id, Role.STUDENT)
        assertEquals("角色不一致被拒后数据不得改动", before, env.prioritySnapshot(studentId))

        // 学生会话冒充家长会话：统一判定入口一律拒绝（updateTemplate 单列，见探测套件）
        env.loginAsStudent(parentId, studentId)
        assertAllEntriesDenied(env.repository, item.id, Role.PARENT, includeTemplate = false)
        assertEquals(HomeworkStatus.RECORDED, env.repository.getHomework(item.id)?.status)
        assertEquals("小明的作业", env.repository.getHomework(item.id)?.content)
        assertEquals(before, env.prioritySnapshot(studentId))
    }

    @Test
    fun `学生档案不再归属该家长后家长对既有作业失去全部入口`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent("parent001")
        val studentId = env.addStudent(parentId, "小明")
        val item = env.addToday(studentId, "小明的作业")
        env.addToday(studentId, "小明的第二项")
        val before = env.prioritySnapshot(studentId)

        // 归属判定与 auth 同源：档案被删除后 isStudentOwnedBy 收敛为 false
        env.authRepository.deleteStudent(studentId)
        assertFalse(
            "归属档案缺失时 auth 统一口径必须返回 false",
            env.authRepository.isStudentOwnedBy(parentId, studentId),
        )

        assertAllEntriesDenied(env.repository, item.id, Role.PARENT)
        assertEquals("悬空归属被拒后数据不得改动", before, env.prioritySnapshot(studentId))
        assertEquals("小明的作业", env.repository.getHomework(item.id)?.content)
    }

    // ---- 3. 权限与状态机：进行中锁定 / 越权优先 ----

    @Test
    fun `本人名下进行中作业的调序与时间锁定语义不回归`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent("parent001")
        val studentId = env.addStudent(parentId, "小明")
        val item = env.addToday(studentId, "小明的作业")
        env.addToday(studentId, "小明的第二项")
        val before = env.prioritySnapshot(studentId)

        assertTrue(
            env.repository.updateSchedule(item.id, startAt(16), 30, Role.PARENT)
                is ScheduleUpdateResult.Success,
        )
        assertTrue(env.repository.startProgress(item.id, Role.PARENT) is HomeworkStatusResult.Success)
        assertEquals(HomeworkStatus.IN_PROGRESS, env.repository.getHomework(item.id)?.status)

        assertEquals(
            HomeworkOrderResult.LockedWorkInProgress,
            env.repository.reorderHomework(item.id, ReorderDirection.DOWN, Role.PARENT),
        )
        assertEquals(
            HomeworkOrderResult.LockedWorkInProgress,
            env.repository.moveHomeworkTo(item.id, 0, Role.PARENT),
        )
        assertEquals(
            ScheduleUpdateResult.LockedWorkInProgress,
            env.repository.updateSchedule(item.id, startAt(17), 45, Role.PARENT),
        )
        assertEquals(
            HomeworkOperationResult.LockedWorkInProgress,
            env.repository.clearSchedule(item.id, Role.PARENT),
        )
        // 锁定分支同样不改动数据（排定时间/时长/状态/优先级）
        assertEquals(before, env.prioritySnapshot(studentId))
        val current = env.repository.getHomework(item.id)!!
        assertEquals(startAt(16), current.startTime)
        assertEquals(30, current.estimatedMinutes)
        assertEquals(HomeworkStatus.IN_PROGRESS, current.status)
    }

    @Test
    fun `他人名下进行中作业在内容类型删除入口同样报越权而非锁定`() = runTest {
        val env = HomeworkTestEnv()
        val firstParentId = env.loginAsParent("parent001")
        env.addStudent(firstParentId, "小明")
        val otherParentId = env.loginAsParent("parent002")
        val otherStudentId = env.addStudent(otherParentId, "小美")
        val foreign = env.addToday(otherStudentId, "小美的作业")
        assertTrue(
            env.repository.updateSchedule(foreign.id, startAt(16), 30, Role.PARENT)
                is ScheduleUpdateResult.Success,
        )
        assertTrue(env.repository.startProgress(foreign.id, Role.PARENT) is HomeworkStatusResult.Success)

        // 切回 parent001：类型/内容/删除入口一律越权（不泄漏进行中状态，也不改动数据）
        env.authRepository.loginParent("parent001", HomeworkTestEnv.PASSWORD)
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateContent(foreign.id, "越权修改", Role.PARENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateTemplate(
                foreign.id,
                HomeworkType.TODAY,
                null,
                Instant.ofEpochMilli(millisAt(21)),
                Role.PARENT,
            ),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.deleteHomework(foreign.id, Role.PARENT),
        )
        val current = env.repository.getHomework(foreign.id)!!
        assertEquals("小美的作业", current.content)
        assertNull(current.deadline)
        assertEquals(HomeworkStatus.IN_PROGRESS, current.status)
        assertEquals(startAt(16), current.startTime)
    }

    // ---- 4. 权限语义边界：学生主闭环 vs canModify ----

    @Test
    fun `学生本人名下家长布置的作业可排定与流转但不可改删`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent("parent001")
        val studentId = env.addStudent(parentId, "小明")
        val assigned = env.addToday(studentId, "家长布置的作业", createdBy = CreatorRole.PARENT)
        val confirmed = env.addToday(studentId, "家长布置的第二项", createdBy = CreatorRole.PARENT)
        env.loginAsStudent(parentId, studentId)

        // 执行权：学生可为家长布置的本人作业排定时间与流转状态（主闭环）
        val scheduled = env.repository.updateSchedule(assigned.id, startAt(16), 30, Role.STUDENT)
        assertEquals(HomeworkStatus.PENDING, (scheduled as ScheduleUpdateResult.Success).item.status)
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            (env.repository.startProgress(assigned.id, Role.STUDENT) as HomeworkStatusResult.Success).item.status,
        )
        assertEquals(
            HomeworkStatus.COMPLETED,
            (env.repository.complete(assigned.id, Role.STUDENT) as HomeworkStatusResult.Success).item.status,
        )
        val cleared = env.repository.clearSchedule(assigned.id, Role.STUDENT) as HomeworkOperationResult.Success
        assertEquals(HomeworkStatus.IN_PROGRESS, cleared.item.status)
        assertNull("撤销排定必须清空开始时间", cleared.item.startTime)
        env.repository.complete(assigned.id, Role.STUDENT)
        assertEquals(
            HomeworkStatus.IN_PROGRESS,
            (env.repository.reopen(assigned.id, Role.STUDENT) as HomeworkStatusResult.Success).item.status,
        )
        assertEquals(
            HomeworkStatus.PENDING,
            (env.repository.markPending(confirmed.id, Role.STUDENT) as HomeworkStatusResult.Success).item.status,
        )

        // 改删权：学生不可改删家长录入项（即使归属本人名下），且类型修改为家长专属
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateContent(confirmed.id, "学生越权改家长录入项", Role.STUDENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.deleteHomework(confirmed.id, Role.STUDENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateTemplate(confirmed.id, HomeworkType.TODAY, null, null, Role.STUDENT),
        )
        assertEquals("家长布置的第二项", env.repository.getHomework(confirmed.id)?.content)

        // 学生自己录入项：可改可删
        val own = env.addToday(studentId, "学生自己录入的作业", createdBy = CreatorRole.STUDENT)
        assertTrue(
            env.repository.updateContent(own.id, "学生自己录入的作业（已改）", Role.STUDENT)
                is HomeworkOperationResult.Success,
        )
        assertTrue(
            env.repository.deleteHomework(own.id, Role.STUDENT) is HomeworkOperationResult.Success,
        )
        assertNull(env.repository.getHomework(own.id))
    }

    // ---- 5. 边界值与判定顺序：内容校验 vs 越权 ----

    @Test
    fun `内容边界值在已授权会话下返回 ContentInvalid 且不改动数据`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent("parent001")
        val studentId = env.addStudent(parentId, "小明")
        val item = env.addToday(studentId, "原始内容")

        assertEquals(
            HomeworkOperationResult.ContentInvalid(HomeworkValidationError.BLANK_CONTENT),
            env.repository.updateContent(item.id, "   ", Role.PARENT),
        )
        val tooLong = "字".repeat(HomeworkConstants.MAX_CONTENT_LENGTH + 1)
        assertEquals(
            HomeworkOperationResult.ContentInvalid(HomeworkValidationError.CONTENT_TOO_LONG),
            env.repository.updateContent(item.id, tooLong, Role.PARENT),
        )
        assertEquals("内容校验失败不得改动数据", "原始内容", env.repository.getHomework(item.id)?.content)

        // 边界内最大值可成功
        val maxContent = "字".repeat(HomeworkConstants.MAX_CONTENT_LENGTH)
        assertTrue(
            env.repository.updateContent(item.id, maxContent, Role.PARENT)
                is HomeworkOperationResult.Success,
        )
        assertEquals(maxContent, env.repository.getHomework(item.id)?.content)
    }

    @Test
    fun `越权判定优先于内容校验非法输入`() = runTest {
        val env = HomeworkTestEnv()
        val firstParentId = env.loginAsParent("parent001")
        env.addStudent(firstParentId, "小明")
        val otherParentId = env.loginAsParent("parent002")
        val otherStudentId = env.addStudent(otherParentId, "小美")
        val foreign = env.addToday(otherStudentId, "小美的作业")
        env.authRepository.loginParent("parent001", HomeworkTestEnv.PASSWORD)

        // 非法内容（空白/超长）也必须是 PermissionDenied：归属维先于内容校验
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateContent(foreign.id, "", Role.PARENT),
        )
        assertEquals(
            HomeworkOperationResult.PermissionDenied,
            env.repository.updateContent(
                foreign.id,
                "字".repeat(HomeworkConstants.MAX_CONTENT_LENGTH + 1),
                Role.PARENT,
            ),
        )
        assertEquals("小美的作业", env.repository.getHomework(foreign.id)?.content)
    }

    /**
     * 直接装配内存环境（不走 auth 真实实现）：用于构造「会话缺家长 id」这类正常注册流程
     * 产不出的异常会话，验证归属围栏的兜底分支。
     */
    private class DirectSessionEnv(session: SessionState) {

        val dao = FakeHomeworkItemDao()
        val auth = FakeAuthRepository(session)
        private val clock = MutableClock(HomeworkTestEnv.FIXED_MILLIS)
        val repository = HomeworkRepositoryImpl(dao, auth, clock, ZoneId.of("Asia/Shanghai"))

        suspend fun seed(
            studentId: Long,
            status: HomeworkStatus = HomeworkStatus.RECORDED,
            parentAccountId: Long = 1L,
        ): HomeworkItem {
            val id = dao.insert(
                HomeworkItemEntity(
                    parentAccountId = parentAccountId,
                    studentId = studentId,
                    content = "直连作业",
                    type = HomeworkType.TODAY.name,
                    stageRange = null,
                    deadline = null,
                    priority = HomeworkConstants.MIN_PRIORITY,
                    startTime = null,
                    estimatedMinutes = null,
                    status = status.name,
                    createdByRole = CreatorRole.PARENT.name,
                    createdAt = Instant.ofEpochMilli(HomeworkTestEnv.FIXED_MILLIS),
                ),
            )
            return requireNotNull(repository.getHomework(id))
        }
    }
}