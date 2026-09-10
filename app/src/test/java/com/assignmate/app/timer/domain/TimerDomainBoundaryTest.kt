package com.assignmate.app.timer.domain

import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.data.TimerTestEnv
import com.assignmate.app.timer.data.timerTestHomework
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计时领域模型与纯函数边界补充单测（在 TimerCalculationsTest / TimerPhaseTest 之外补齐）：
 * 常量口径自洽、会话与暂停明细模型的派生属性、以及暂停汇总/超时/下一项/倒计时的极限与异常输入。
 */
class TimerDomainBoundaryTest {

    // ---- 常量口径自洽 ----

    @Test
    fun `时间换算常量自洽`() {
        assertEquals(1_000L, TimerConstants.MILLIS_PER_SECOND)
        assertEquals(60_000L, TimerConstants.MILLIS_PER_MINUTE)
        assertEquals(60 * TimerConstants.MILLIS_PER_MINUTE, TimerConstants.MILLIS_PER_HOUR)
        assertEquals(
            TimerConstants.REST_DURATION_MINUTES * TimerConstants.MILLIS_PER_MINUTE,
            TimerConstants.REST_DURATION_MILLIS,
        )
        assertEquals(10, TimerConstants.REST_DURATION_MINUTES)
        assertEquals(10 * 60_000L, TimerConstants.REST_DURATION_MILLIS)
    }

    @Test
    fun `走秒间隔为每秒一跳且超时宽限默认零`() {
        assertEquals(TimerConstants.MILLIS_PER_SECOND, TimerConstants.TICK_INTERVAL_MILLIS)
        assertEquals(0, TimerConstants.OVERDUE_GRACE_MINUTES)
        assertEquals(0L, TimerConstants.OVERDUE_GRACE_MILLIS)
    }

    // ---- 领域模型派生属性 ----

    @Test
    fun `会话未收尾判定与阶段一致`() {
        assertTrue(session(phase = TimerPhase.RUNNING).isActive)
        assertTrue(session(phase = TimerPhase.PAUSED).isActive)
        assertFalse(session(phase = TimerPhase.IDLE).isActive)
        assertFalse(session(phase = TimerPhase.FINISHED, finishedAtMillis = BASE).isActive)
        assertFalse(session(phase = TimerPhase.RESTING).isActive)
    }

    @Test
    fun `会话落库状态字符串与阶段名一致并可回读`() {
        TimerPhase.PERSISTED.forEach { phase ->
            val stored = session(phase = phase, finishedAtMillis = BASE).statusName

            assertEquals(phase.name, stored)
            assertEquals(phase, TimerPhase.fromSessionStatus(stored))
        }
    }

    @Test
    fun `暂停明细结束时刻为空即判定为进行中的暂停`() {
        assertTrue(pause(startMillis = BASE, endMillis = null).isOngoing)
        assertFalse(pause(startMillis = BASE, endMillis = BASE + 1_000L).isOngoing)
    }

    @Test
    fun `会话小结按数据类语义可比较`() {
        val session = session()
        val pauses = listOf(pause(startMillis = BASE + 1_000L, endMillis = BASE + 3_000L))

        assertEquals(
            TimerCalculations.summarize(session, pauses, nowMillis = BASE + 5_000L),
            TimerCalculations.summarize(session, pauses, nowMillis = BASE + 5_000L),
        )
    }

    // ---- 暂停汇总边界 ----

    @Test
    fun `参考时刻早于未结束暂停开始时刻时该段按零计`() {
        val pauses = listOf(pause(startMillis = BASE + 10_000L, endMillis = null))

        assertEquals(0L, TimerCalculations.pauseAccumulatedMillis(pauses, referenceMillis = BASE + 5_000L))
    }

    @Test
    fun `混合已结束与未结束暂停时累计求和`() {
        val pauses = listOf(
            pause(id = 1L, startMillis = BASE, endMillis = BASE + 2_000L),
            pause(id = 2L, startMillis = BASE + 10_000L, endMillis = null),
        )

        assertEquals(7_000L, TimerCalculations.pauseAccumulatedMillis(pauses, referenceMillis = BASE + 15_000L))
    }

    @Test
    fun `暂停明细为空时会话已用时长等于纯走秒时长`() {
        assertEquals(
            8_000L,
            TimerCalculations.elapsedOf(session(), emptyList(), nowMillis = BASE + 8_000L),
        )
    }

    // ---- 超时判定边界 ----

    @Test
    fun `预估未超但超过 deadline 仍判超时`() {
        assertTrue(
            TimerCalculations.isOverdue(
                startTimeMillis = BASE,
                estimatedMinutes = 30,
                deadlineMillis = BASE + 10 * MINUTE,
                referenceMillis = BASE + 10 * MINUTE + 1L,
            ),
        )
    }

    @Test
    fun `恰好等于 deadline 不算超时`() {
        assertFalse(
            TimerCalculations.isOverdue(
                startTimeMillis = null,
                estimatedMinutes = null,
                deadlineMillis = BASE + 10 * MINUTE,
                referenceMillis = BASE + 10 * MINUTE,
            ),
        )
    }

    @Test
    fun `未排定时间且无 deadline 的作业永不判超时`() {
        val homework = timerTestHomework(id = 1L)

        assertFalse(TimerCalculations.isHomeworkOverdue(homework, session = null, nowMillis = BASE + 100 * DAY))
    }

    @Test
    fun `预估时长为零分钟时到点即判超时`() {
        assertFalse(TimerCalculations.isOverdue(BASE, 0, null, BASE))
        assertTrue(TimerCalculations.isOverdue(BASE, 0, null, BASE + 1L))
    }

    // ---- 下一项选取边界 ----

    @Test
    fun `排除项覆盖唯一候选时无下一项`() {
        val single = listOf(timerTestHomework(id = 1L, priority = 100))

        assertNull(TimerCalculations.pickNextItem(single, excludeHomeworkId = 1L))
        assertEquals(1L, TimerCalculations.pickNextItem(single, excludeHomeworkId = null)?.id)
        assertEquals(
            2L,
            TimerCalculations.pickNextItem(
                listOf(
                    timerTestHomework(id = 1L, priority = 100),
                    timerTestHomework(id = 2L, priority = 200),
                ),
                excludeHomeworkId = 1L,
            )?.id,
        )
    }

    @Test
    fun `优先级为负数的作业同样按升序最先被选中`() {
        val items = listOf(
            timerTestHomework(id = 1L, priority = 0),
            timerTestHomework(id = 2L, priority = -5),
            timerTestHomework(id = 3L, priority = 5),
        )

        assertEquals(2L, TimerCalculations.pickNextItem(items)?.id)
    }

    @Test
    fun `优先级与创建时间都相同时保持清单原顺序`() {
        val items = listOf(
            timerTestHomework(id = 7L, priority = 100, createdAtMillis = BASE),
            timerTestHomework(id = 8L, priority = 100, createdAtMillis = BASE),
        )

        assertEquals(7L, TimerCalculations.pickNextItem(items)?.id)
    }

    @Test
    fun `已记录与已完成的作业都不计入剩余数量`() {
        val items = listOf(
            timerTestHomework(id = 1L, status = HomeworkStatus.RECORDED),
            timerTestHomework(id = 2L, status = HomeworkStatus.COMPLETED),
            timerTestHomework(id = 3L, status = HomeworkStatus.IN_PROGRESS),
        )

        assertEquals(1, TimerCalculations.remainingCount(items))
        assertEquals(1, TimerCalculations.completedCount(items))
    }

    @Test
    fun `空清单的剩余与完成数量均为零`() {
        assertEquals(0, TimerCalculations.remainingCount(emptyList()))
        assertEquals(0, TimerCalculations.completedCount(emptyList()))
    }

    // ---- 休息倒计时边界 ----

    @Test
    fun `休息开始时刻晚于现在时剩余大于完整时长不做上限截断`() {
        assertEquals(
            TimerConstants.REST_DURATION_MILLIS + 5_000L,
            TimerCalculations.restRemainingMillis(restStartedAtMillis = BASE + 5_000L, nowMillis = BASE),
        )
    }

    @Test
    fun `展示秒数对整小时取整正确`() {
        assertEquals(3_600L, TimerCalculations.displaySeconds(TimerConstants.MILLIS_PER_HOUR))
        assertEquals(599L, TimerCalculations.displaySeconds(10 * MINUTE - 1_000L))
    }

    // ---- 测试数据构造 ----

    private fun session(
        phase: TimerPhase = TimerPhase.RUNNING,
        finishedAtMillis: Long? = null,
    ): TimerSession = TimerSession(
        id = 1L,
        homeworkId = 10L,
        studentId = TimerTestEnv.STUDENT_ID,
        parentAccountId = TimerTestEnv.PARENT_ID,
        startedAt = Instant.ofEpochMilli(BASE),
        finishedAt = finishedAtMillis?.let { Instant.ofEpochMilli(it) },
        phase = phase,
    )

    private fun pause(
        id: Long = 1L,
        startMillis: Long,
        endMillis: Long? = null,
    ): PauseRecord = PauseRecord(
        id = id,
        sessionId = 1L,
        homeworkId = 10L,
        pauseStartAt = Instant.ofEpochMilli(startMillis),
        pauseEndAt = endMillis?.let { Instant.ofEpochMilli(it) },
    )

    private companion object {

        const val BASE = TimerTestEnv.FIXED_MILLIS
        const val MINUTE = TimerConstants.MILLIS_PER_MINUTE
        const val DAY = 24 * TimerConstants.MILLIS_PER_HOUR
    }
}
