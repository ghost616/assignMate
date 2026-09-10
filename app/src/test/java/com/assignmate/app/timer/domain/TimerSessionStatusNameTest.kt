package com.assignmate.app.timer.domain

import com.assignmate.app.timer.data.TimerTestEnv
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话落库状态名（[TimerSession.statusName] → [TimerPhase.persistedName] 转发）单测。
 *
 * 覆盖测试说明第 6 项：`persistedName` 把「库内 status 取值域」从人工约定变成可执行约束，
 * [TimerSession.statusName] 必须**转发**该约束（页面级阶段 IDLE/RESTING 的会话若被写入应当场抛出），
 * 并保证 [TimerPhase.fromSessionStatus] 能把落库名读回同一阶段（读写闭环）。
 */
class TimerSessionStatusNameTest {

    @Test
    fun `会话落库状态名与阶段枚举名一致`() {
        TimerPhase.PERSISTED.forEach { phase ->
            assertEquals(phase.name, session(phase).statusName)
        }
    }

    @Test
    fun `页面级阶段的会话落库状态名同样抛出异常`() {
        listOf(TimerPhase.IDLE, TimerPhase.RESTING).forEach { phase ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                session(phase).statusName
            }

            assertTrue(
                "异常信息应指明不可落库的阶段：${error.message}",
                error.message.orEmpty().contains(phase.name),
            )
            assertTrue(
                "异常信息应说明库内取值域：${error.message}",
                error.message.orEmpty().contains("不可落库"),
            )
        }
    }

    @Test
    fun `落库名可被解析回同一阶段形成读写闭环`() {
        TimerPhase.PERSISTED.forEach { phase ->
            assertEquals(phase, TimerPhase.fromSessionStatus(session(phase).statusName))
        }
    }

    @Test
    fun `未收尾会话的落库名仅进行中与暂停中`() {
        val activeNames = TimerPhase.entries
            .filter { it.isActive }
            .map { session(it).statusName }
            .toSet()

        assertEquals(setOf("RUNNING", "PAUSED"), activeNames)
        assertTrue(TimerPhase.FINISHED in TimerPhase.PERSISTED)
        assertTrue(TimerPhase.FINISHED !in TimerPhase.entries.filter { it.isActive })
    }

    private fun session(phase: TimerPhase): TimerSession = TimerSession(
        id = 1L,
        homeworkId = 1L,
        studentId = TimerTestEnv.STUDENT_ID,
        parentAccountId = TimerTestEnv.PARENT_ID,
        startedAt = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS),
        finishedAt = if (phase == TimerPhase.FINISHED) {
            Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS + 60_000L)
        } else {
            null
        },
        phase = phase,
    )
}
