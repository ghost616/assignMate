package com.assignmate.app.timer.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * timer 路由常量与「路由参数 + 会话 -> 目标学生」解析单测。
 *
 * 关键安全口径：学生会话一律取本人 id（忽略路由参数，防越权操作他人作业）；
 * 家长会话必须显式指定学生（route > 0）；无会话返回 null 交由页面提示。
 */
class TimerDestinationTest {

    @Test
    fun `路由模板与路径参数名保持接线契约`() {
        assertEquals("timer/execution/{studentId}/{homeworkId}", TimerDestination.EXECUTION)
        assertEquals("timer/rest/{studentId}/{homeworkId}", TimerDestination.REST)
        assertEquals("timer/next/{studentId}", TimerDestination.NEXT_ITEM)
        assertEquals("timer/completion/{studentId}", TimerDestination.COMPLETION)
        assertEquals("studentId", TimerDestination.ARG_STUDENT_ID)
        assertEquals("homeworkId", TimerDestination.ARG_HOMEWORK_ID)
        assertEquals(0L, TimerDestination.ARG_STUDENT_ID_NONE)
        assertEquals(-1L, TimerDestination.ARG_HOMEWORK_ID_NONE)
    }

    @Test
    fun `路由拼装与模板占位一一对应`() {
        assertEquals("timer/execution/2/7", TimerDestination.executionRoute(studentId = 2L, homeworkId = 7L))
        assertEquals("timer/rest/2/7", TimerDestination.restRoute(studentId = 2L, homeworkId = 7L))
        assertEquals("timer/next/2", TimerDestination.nextItemRoute(studentId = 2L))
        assertEquals("timer/completion/2", TimerDestination.completionRoute(studentId = 2L))
    }

    @Test
    fun `studentId 解析对缺失与非法值回落到未指定`() {
        assertEquals(TimerDestination.ARG_STUDENT_ID_NONE, TimerDestination.studentIdOf(null))
        assertEquals(TimerDestination.ARG_STUDENT_ID_NONE, TimerDestination.studentIdOf(""))
        assertEquals(TimerDestination.ARG_STUDENT_ID_NONE, TimerDestination.studentIdOf("abc"))
        assertEquals(TimerDestination.ARG_STUDENT_ID_NONE, TimerDestination.studentIdOf("2x"))
        assertEquals(0L, TimerDestination.studentIdOf("0"))
        assertEquals(2L, TimerDestination.studentIdOf("2"))
        assertEquals(-3L, TimerDestination.studentIdOf("-3"))
    }

    @Test
    fun `homeworkId 解析对缺失与非法值回落到未指定`() {
        assertEquals(TimerDestination.ARG_HOMEWORK_ID_NONE, TimerDestination.homeworkIdOf(null))
        assertEquals(TimerDestination.ARG_HOMEWORK_ID_NONE, TimerDestination.homeworkIdOf(""))
        assertEquals(TimerDestination.ARG_HOMEWORK_ID_NONE, TimerDestination.homeworkIdOf("x"))
        assertEquals(7L, TimerDestination.homeworkIdOf("7"))
        assertEquals(0L, TimerDestination.homeworkIdOf("0"))
    }

    @Test
    fun `学生会话忽略路由参数取本人 id 防越权`() {
        val session = studentSession(studentId = 2L)

        assertEquals(2L, resolveTimerStudentId(session, routeStudentId = 99L))
        assertEquals(2L, resolveTimerStudentId(session, routeStudentId = TimerDestination.ARG_STUDENT_ID_NONE))
    }

    @Test
    fun `家长会话取路由指定的学生且未指定时返回 null`() {
        val session = SessionState(role = Role.PARENT, parentId = 1L, studentId = null)

        assertEquals(5L, resolveTimerStudentId(session, routeStudentId = 5L))
        assertNull(resolveTimerStudentId(session, routeStudentId = TimerDestination.ARG_STUDENT_ID_NONE))
        assertNull(resolveTimerStudentId(session, routeStudentId = -1L))
    }

    @Test
    fun `无会话时返回 null 交由页面提示登录失效`() {
        assertNull(resolveTimerStudentId(SessionState.NONE, routeStudentId = 5L))
    }

    @Test
    fun `学生角色但缺少学生 id 时返回 null 避免越权`() {
        val incomplete = SessionState(role = Role.STUDENT, parentId = 1L, studentId = null)

        assertNull(resolveTimerStudentId(incomplete, routeStudentId = 5L))
    }

    private fun studentSession(studentId: Long): SessionState =
        SessionState(role = Role.STUDENT, parentId = 1L, studentId = studentId)
}
