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
 * 计时纯函数单测：已用时长（含暂停扣除的边界）、暂停累计与次数汇总、
 * 超时判定（预估时长/deadline/边界时刻）、下一项选取、休息倒计时与展示取整。
 *
 * 基准时刻 [BASE] 与 [TimerTestEnv.FIXED_MILLIS] 一致，便于与仓库层测试共用同一时间心智。
 */
class TimerCalculationsTest {

    // ---- 1. 已用时长 ----

    @Test
    fun `已用时长等于参考时刻减开始时刻减暂停累计`() {
        assertEquals(
            65_000L,
            TimerCalculations.elapsedMillis(startedAtMillis = 1_000L, pausedTotalMillis = 4_000L, referenceMillis = 70_000L),
        )
    }

    @Test
    fun `无暂停时已用时长等于纯走秒时长`() {
        assertEquals(
            30_000L,
            TimerCalculations.elapsedMillis(startedAtMillis = 1_000L, pausedTotalMillis = 0L, referenceMillis = 31_000L),
        )
    }

    @Test
    fun `时钟回拨导致参考时刻早于开始时刻时已用时长收敛为零`() {
        assertEquals(
            0L,
            TimerCalculations.elapsedMillis(startedAtMillis = 50_000L, pausedTotalMillis = 0L, referenceMillis = 10_000L),
        )
    }

    @Test
    fun `暂停累计超过已走时长时已用时长收敛为零`() {
        assertEquals(
            0L,
            TimerCalculations.elapsedMillis(startedAtMillis = BASE, pausedTotalMillis = 60_000L, referenceMillis = BASE + 10_000L),
        )
    }

    @Test
    fun `已完成会话的参考时刻固定为结束时刻不再随现在增长`() {
        val session = session(finishedAtMillis = BASE + 60_000L)
        val pauses = listOf(pause(startMillis = BASE + 10_000L, endMillis = BASE + 20_000L))

        val atFinish = TimerCalculations.elapsedOf(session, pauses, nowMillis = BASE + 60_000L)
        val muchLater = TimerCalculations.elapsedOf(session, pauses, nowMillis = BASE + 600_000L)

        assertEquals(50_000L, atFinish)
        assertEquals(atFinish, muchLater)
    }

    @Test
    fun `暂停中的会话已用时长在暂停开始时刻冻结`() {
        val session = session()
        val pauses = listOf(pause(startMillis = BASE + 20_000L, endMillis = null))

        val atPauseStart = TimerCalculations.elapsedOf(session, pauses, nowMillis = BASE + 20_000L)
        val duringPause = TimerCalculations.elapsedOf(session, pauses, nowMillis = BASE + 50_000L)

        assertEquals(20_000L, atPauseStart)
        assertEquals(20_000L, duringPause)
    }

    @Test
    fun `恢复后已用时长只扣减已完成暂停段`() {
        val session = session()
        val pauses = listOf(
            pause(id = 1L, startMillis = BASE + 10_000L, endMillis = BASE + 15_000L),
            pause(id = 2L, startMillis = BASE + 30_000L, endMillis = BASE + 40_000L),
        )

        // 现在 = 开始 + 60s，两段暂停共 15s 不计入
        assertEquals(45_000L, TimerCalculations.elapsedOf(session, pauses, nowMillis = BASE + 60_000L))
    }

    // ---- 2. 暂停累计与次数 ----

    @Test
    fun `暂停累计为各已结束暂停段之和`() {
        val pauses = listOf(
            pause(id = 1L, startMillis = BASE, endMillis = BASE + 5_000L),
            pause(id = 2L, startMillis = BASE + 20_000L, endMillis = BASE + 33_000L),
        )

        assertEquals(18_000L, TimerCalculations.pauseAccumulatedMillis(pauses, referenceMillis = BASE + 100_000L))
    }

    @Test
    fun `未结束暂停按参考时刻折算并随时间增长`() {
        val pauses = listOf(pause(startMillis = BASE + 10_000L, endMillis = null))

        assertEquals(5_000L, TimerCalculations.pauseAccumulatedMillis(pauses, referenceMillis = BASE + 15_000L))
        assertEquals(30_000L, TimerCalculations.pauseAccumulatedMillis(pauses, referenceMillis = BASE + 40_000L))
    }

    @Test
    fun `暂停结束时刻晚于参考时刻时按参考时刻截断`() {
        val pauses = listOf(pause(startMillis = BASE, endMillis = BASE + 100_000L))

        assertEquals(20_000L, TimerCalculations.pauseAccumulatedMillis(pauses, referenceMillis = BASE + 20_000L))
    }

    @Test
    fun `异常暂停记录（开始晚于结束）按零计`() {
        val pauses = listOf(pause(startMillis = BASE + 10_000L, endMillis = BASE + 5_000L))

        assertEquals(0L, TimerCalculations.pauseAccumulatedMillis(pauses, referenceMillis = BASE + 20_000L))
    }

    @Test
    fun `无暂停明细时暂停累计为零`() {
        assertEquals(0L, TimerCalculations.pauseAccumulatedMillis(emptyList(), referenceMillis = BASE))
    }

    @Test
    fun `暂停次数等于明细条数且包含未结束的暂停`() {
        val pauses = listOf(
            pause(id = 1L, startMillis = BASE, endMillis = BASE + 1_000L),
            pause(id = 2L, startMillis = BASE + 5_000L, endMillis = null),
        )

        assertEquals(2, TimerCalculations.pauseCount(pauses))
    }

    @Test
    fun `会话小结同时给出已用时长暂停累计与暂停次数`() {
        val session = session()
        val pauses = listOf(
            pause(id = 1L, startMillis = BASE + 10_000L, endMillis = BASE + 15_000L),
            pause(id = 2L, startMillis = BASE + 40_000L, endMillis = null),
        )

        val summary = TimerCalculations.summarize(session, pauses, nowMillis = BASE + 60_000L)

        assertEquals(session.id, summary.sessionId)
        assertEquals(TimerPhase.RUNNING, summary.phase)
        assertEquals(25_000L, summary.pausedTotalMillis)
        assertEquals(2, summary.pauseCount)
        assertEquals(35_000L, summary.elapsedMillis)
    }

    // ---- 3. 超时判定 ----

    @Test
    fun `未超过预估完成时刻不算超时`() {
        assertFalse(
            TimerCalculations.isOverdue(
                startTimeMillis = BASE,
                estimatedMinutes = 30,
                deadlineMillis = null,
                referenceMillis = BASE + 29 * MINUTE,
            ),
        )
    }

    @Test
    fun `恰好在预估完成时刻不算超时超过一毫秒即算超时`() {
        assertFalse(
            TimerCalculations.isOverdue(BASE, 30, null, BASE + 30 * MINUTE),
        )
        assertTrue(
            TimerCalculations.isOverdue(BASE, 30, null, BASE + 30 * MINUTE + 1L),
        )
    }

    @Test
    fun `未排定开始时间或预估时长时不做预估超时判定`() {
        assertFalse(TimerCalculations.isOverdue(null, 30, null, BASE + DAY))
        assertFalse(TimerCalculations.isOverdue(BASE, null, null, BASE + DAY))
        assertFalse(TimerCalculations.isOverdue(null, null, null, BASE + DAY))
    }

    @Test
    fun `超过 deadline 即算超时即使未排定开始时间`() {
        assertTrue(
            TimerCalculations.isOverdue(
                startTimeMillis = null,
                estimatedMinutes = null,
                deadlineMillis = BASE + 10 * MINUTE,
                referenceMillis = BASE + 10 * MINUTE + 1L,
            ),
        )
    }

    @Test
    fun `已完成的作业按会话结束时刻判定当时是否超时`() {
        val homework = timerTestHomework(
            id = 1L,
            startTimeMillis = BASE,
            estimatedMinutes = 10,
        )
        val finishedLate = session(finishedAtMillis = BASE + 15 * MINUTE)
        val finishedEarly = session(finishedAtMillis = BASE + 5 * MINUTE)

        assertTrue(TimerCalculations.isHomeworkOverdue(homework, finishedLate, nowMillis = BASE + HOUR))
        assertFalse(TimerCalculations.isHomeworkOverdue(homework, finishedEarly, nowMillis = BASE + HOUR))
    }

    @Test
    fun `未开始计时的作业以当前时刻判定是否超时`() {
        val homework = timerTestHomework(id = 1L, startTimeMillis = BASE, estimatedMinutes = 10)

        assertFalse(TimerCalculations.isHomeworkOverdue(homework, session = null, nowMillis = BASE + 5 * MINUTE))
        assertTrue(TimerCalculations.isHomeworkOverdue(homework, session = null, nowMillis = BASE + 11 * MINUTE))
    }

    // ---- 4. 下一项选取 ----

    @Test
    fun `下一项取优先级最靠前的待完成作业`() {
        val items = listOf(
            timerTestHomework(id = 3L, priority = 300),
            timerTestHomework(id = 1L, priority = 100),
            timerTestHomework(id = 2L, priority = 200),
        )

        assertEquals(1L, TimerCalculations.pickNextItem(items)?.id)
    }

    @Test
    fun `下一项跳过已完成的作业`() {
        val items = listOf(
            timerTestHomework(id = 1L, priority = 100, status = HomeworkStatus.COMPLETED),
            timerTestHomework(id = 2L, priority = 200),
        )

        assertEquals(2L, TimerCalculations.pickNextItem(items)?.id)
    }

    @Test
    fun `下一项把进行中的作业视为可继续`() {
        val items = listOf(timerTestHomework(id = 1L, priority = 100, status = HomeworkStatus.IN_PROGRESS))

        assertEquals(1L, TimerCalculations.pickNextItem(items)?.id)
    }

    @Test
    fun `已记录（未排定时间）的作业不作为下一项`() {
        val items = listOf(
            timerTestHomework(id = 1L, priority = 100, status = HomeworkStatus.RECORDED),
            timerTestHomework(id = 2L, priority = 200, status = HomeworkStatus.PENDING),
        )

        assertEquals(2L, TimerCalculations.pickNextItem(items)?.id)
    }

    @Test
    fun `同优先级按创建时间升序取更早的一项`() {
        val items = listOf(
            timerTestHomework(id = 2L, priority = 100, createdAtMillis = BASE + 1_000L),
            timerTestHomework(id = 1L, priority = 100, createdAtMillis = BASE),
        )

        assertEquals(1L, TimerCalculations.pickNextItem(items)?.id)
    }

    @Test
    fun `下一项可显式排除指定作业`() {
        val items = listOf(
            timerTestHomework(id = 1L, priority = 100),
            timerTestHomework(id = 2L, priority = 200),
        )

        assertEquals(2L, TimerCalculations.pickNextItem(items, excludeHomeworkId = 1L)?.id)
    }

    @Test
    fun `清单全部完成时无下一项`() {
        val items = listOf(timerTestHomework(id = 1L, status = HomeworkStatus.COMPLETED))

        assertNull(TimerCalculations.pickNextItem(items))
        assertNull(TimerCalculations.pickNextItem(emptyList()))
    }

    @Test
    fun `剩余数量与已完成数量按状态统计`() {
        val items = listOf(
            timerTestHomework(id = 1L, status = HomeworkStatus.COMPLETED),
            timerTestHomework(id = 2L, status = HomeworkStatus.PENDING),
            timerTestHomework(id = 3L, status = HomeworkStatus.IN_PROGRESS),
            timerTestHomework(id = 4L, status = HomeworkStatus.RECORDED),
        )

        assertEquals(2, TimerCalculations.remainingCount(items))
        assertEquals(1, TimerCalculations.completedCount(items))
    }

    // ---- 5. 休息倒计时 ----

    @Test
    fun `休息开始时剩余等于完整休息时长`() {
        assertEquals(
            TimerConstants.REST_DURATION_MILLIS,
            TimerCalculations.restRemainingMillis(restStartedAtMillis = BASE, nowMillis = BASE),
        )
        assertEquals(10 * MINUTE, TimerConstants.REST_DURATION_MILLIS)
    }

    @Test
    fun `休息过程中剩余按已过时间递减`() {
        assertEquals(
            5 * MINUTE,
            TimerCalculations.restRemainingMillis(BASE, BASE + 5 * MINUTE),
        )
    }

    @Test
    fun `休息到点或超时后剩余收敛为零并判定结束`() {
        assertEquals(0L, TimerCalculations.restRemainingMillis(BASE, BASE + 10 * MINUTE))
        assertEquals(0L, TimerCalculations.restRemainingMillis(BASE, BASE + 11 * MINUTE))
        assertTrue(TimerCalculations.isRestFinished(BASE, BASE + 10 * MINUTE))
        assertFalse(TimerCalculations.isRestFinished(BASE, BASE + 10 * MINUTE - 1L))
    }

    @Test
    fun `休息时长可显式传入便于自定义`() {
        assertEquals(
            0L,
            TimerCalculations.restRemainingMillis(BASE, BASE + 60_000L, durationMillis = 60_000L),
        )
    }

    @Test
    fun `倒计时展示秒数向上取整`() {
        assertEquals(0L, TimerCalculations.displaySeconds(0L))
        assertEquals(0L, TimerCalculations.displaySeconds(-100L))
        assertEquals(1L, TimerCalculations.displaySeconds(1L))
        assertEquals(1L, TimerCalculations.displaySeconds(999L))
        assertEquals(1L, TimerCalculations.displaySeconds(1_000L))
        assertEquals(2L, TimerCalculations.displaySeconds(1_001L))
        assertEquals(600L, TimerCalculations.displaySeconds(10 * MINUTE))
    }

    // ---- 测试数据构造 ----

    private fun session(
        startedAtMillis: Long = BASE,
        finishedAtMillis: Long? = null,
    ): TimerSession = TimerSession(
        id = 1L,
        homeworkId = 10L,
        studentId = TimerTestEnv.STUDENT_ID,
        parentAccountId = TimerTestEnv.PARENT_ID,
        startedAt = Instant.ofEpochMilli(startedAtMillis),
        finishedAt = finishedAtMillis?.let { Instant.ofEpochMilli(it) },
        pausedTotalMillis = 0L,
        pauseCount = 0,
        phase = if (finishedAtMillis == null) TimerPhase.RUNNING else TimerPhase.FINISHED,
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
        const val HOUR = TimerConstants.MILLIS_PER_HOUR
        const val DAY = 24 * HOUR
    }
}
