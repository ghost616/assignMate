package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单条目可操作性推导单测（[toRowUiState]，纯函数）：
 * 验证「改删权」与「执行权」两个权限维度在 UI 上的投影——
 * 学生必须能对家长布置的本人作业排定时间/标记完成（主闭环），
 * 又不能触碰他人名下作业；改删/调序仍限自己录入的作业。
 */
class HomeworkListRowStateTest {

    // ---- 学生视角 ----

    @Test
    fun `学生可为家长布置的本人作业排定时间与标记完成但不可改删`() {
        val row = item(createdByRole = CreatorRole.PARENT, status = HomeworkStatus.PENDING)
            .toRowUiState(role = Role.STUDENT, sessionStudentId = STUDENT_ID, canMoveUp = true, canMoveDown = false)

        // 执行权：时间排定与完成
        assertTrue("学生应可为家长布置的本人作业排定时间", row.canSchedule)
        assertTrue("待完成作业应可标记完成", row.canComplete)
        assertFalse("已完成才可撤销完成", row.canReopen)
        // 改删/调序权：家长录入项仍不可动
        assertFalse("学生不可编辑家长录入的作业", row.canModify)
        assertFalse("学生不可删除家长录入的作业", row.canDelete)
    }

    @Test
    fun `学生可改删自己录入的作业且同样可排定时间`() {
        val row = item(createdByRole = CreatorRole.STUDENT, status = HomeworkStatus.RECORDED)
            .toRowUiState(role = Role.STUDENT, sessionStudentId = STUDENT_ID, canMoveUp = true, canMoveDown = true)

        assertTrue(row.canModify)
        assertTrue(row.canDelete)
        assertTrue(row.canSchedule)
        // 已记录需先排定时间，不能直接标记完成（见 HomeworkStatus 流转表）
        assertFalse("已记录不可直接完成", row.canComplete)
        assertFalse(row.canReopen)
    }

    @Test
    fun `学生不可操作他人名下作业`() {
        val row = item(studentId = OTHER_STUDENT_ID, createdByRole = CreatorRole.PARENT)
            .toRowUiState(role = Role.STUDENT, sessionStudentId = STUDENT_ID, canMoveUp = false, canMoveDown = false)

        assertFalse(row.canSchedule)
        assertFalse(row.canComplete)
        assertFalse(row.canReopen)
        assertFalse(row.canModify)
        assertFalse(row.canDelete)
    }

    @Test
    fun `学生会话缺少本人 id 时执行权一律不可用`() {
        val row = item(createdByRole = CreatorRole.STUDENT)
            .toRowUiState(role = Role.STUDENT, sessionStudentId = null, canMoveUp = false, canMoveDown = false)

        // 执行权依赖「本人名下」判定：会话未携带 studentId 时不可排定时间/完成
        assertFalse(row.canSchedule)
        assertFalse(row.canComplete)
        assertFalse(row.canReopen)
        // 改删权只看录入者角色（沿用既有改删权限规则），故自己录入项仍可改删；
        // 生产环境学生会话必带 studentId（SessionState.isStudent），该组合不可达
        assertTrue(row.canModify)
        assertTrue(row.canDelete)
    }

    @Test
    fun `学生对已完成作业只能撤销完成不能改时间或再次完成`() {
        val row = item(createdByRole = CreatorRole.STUDENT, status = HomeworkStatus.COMPLETED)
            .toRowUiState(role = Role.STUDENT, sessionStudentId = STUDENT_ID, canMoveUp = false, canMoveDown = false)

        assertFalse(row.canSchedule)
        assertFalse(row.canComplete)
        assertTrue("已完成项应可撤销完成", row.canReopen)
    }

    @Test
    fun `进行中的本人作业可改时间也可标记完成`() {
        val row = item(createdByRole = CreatorRole.PARENT, status = HomeworkStatus.IN_PROGRESS)
            .toRowUiState(role = Role.STUDENT, sessionStudentId = STUDENT_ID, canMoveUp = false, canMoveDown = false)

        assertTrue(row.canSchedule)
        assertTrue(row.canComplete)
    }

    // ---- 家长视角 ----

    @Test
    fun `家长可操作名下学生的全部作业`() {
        val row = item(createdByRole = CreatorRole.STUDENT, status = HomeworkStatus.PENDING)
            .toRowUiState(role = Role.PARENT, sessionStudentId = null, canMoveUp = true, canMoveDown = true)

        assertTrue(row.canModify)
        assertTrue(row.canDelete)
        assertTrue(row.canSchedule)
        assertTrue(row.canComplete)
        assertFalse(row.canReopen)
    }

    @Test
    fun `家长对已完成作业可撤销完成但不可再排定时间`() {
        val row = item(createdByRole = CreatorRole.PARENT, status = HomeworkStatus.COMPLETED)
            .toRowUiState(role = Role.PARENT, sessionStudentId = null, canMoveUp = false, canMoveDown = false)

        assertFalse(row.canSchedule)
        assertFalse(row.canComplete)
        assertTrue(row.canReopen)
        assertTrue("已完成项仍可编辑/删除", row.canModify)
    }

    // ---- 无会话 ----

    @Test
    fun `无会话时所有操作均不可用`() {
        val row = item().toRowUiState(
            role = null,
            sessionStudentId = null,
            canMoveUp = true,
            canMoveDown = true,
        )

        assertFalse(row.canModify)
        assertFalse(row.canDelete)
        assertFalse(row.canSchedule)
        assertFalse(row.canComplete)
        assertFalse(row.canReopen)
        // 移动标记由调用方（列表位置）决定，与权限无关
        assertTrue(row.canMoveUp)
        assertTrue(row.canMoveDown)
    }

    // ---- 测试工具 ----

    private fun item(
        studentId: Long = STUDENT_ID,
        createdByRole: CreatorRole = CreatorRole.PARENT,
        status: HomeworkStatus = HomeworkStatus.RECORDED,
    ): HomeworkItem = HomeworkItem(
        id = 1L,
        parentAccountId = PARENT_ID,
        studentId = studentId,
        content = "作业",
        type = HomeworkType.TODAY,
        stageRange = null,
        deadline = null,
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = status,
        createdByRole = createdByRole,
        createdAt = Instant.ofEpochMilli(1_700_000_000_000L),
    )

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L
        const val OTHER_STUDENT_ID = 9L
    }
}
