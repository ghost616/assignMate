package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.domain.TimerPhase
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TimerRepositoryImpl] 补充边界单测（在 TimerRepositoryImplTest 主流程之外补齐未覆盖分支）：
 *
 * - 权限口径：执行权取「当前会话角色」而非入参角色；家长会话误传学生角色时同步失败且不建会话（失败安全）；
 * - 幂等：暂停中的未结束会话同样被复用，且不打断已有暂停明细；
 * - 脏值兜底：库内未知 status 一律按「已完成」处理，四类操作均被拒绝且不改数据；
 * - 统计口径：完成后小结不再随时间增长；暂停中小结的累计暂停随时间增长而已用时长冻结；
 * - 写入顺序：作业已完成时收尾会话仍成功（homework 同步幂等），不会留下未收尾会话。
 */
class TimerRepositoryEdgeCaseTest {

    @Test
    fun `暂停中的未结束会话在再次开始时被复用且保持暂停`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(10_000L)
        env.repository.pauseSession(session.id)
        env.advance(5_000L)

        val again = env.repository.startSession(homework.id, Role.STUDENT)

        assertTrue(again is TimerStartResult.Success)
        val reused = (again as TimerStartResult.Success).session
        assertEquals(session.id, reused.id)
        assertEquals(TimerPhase.PAUSED, reused.phase)
        assertEquals(1, env.timerSessionDao.all().size)
        assertEquals(1, env.pauseRecordDao.all().size)
        // 暂停明细仍为未结束状态：再次进入不会「偷偷恢复走秒」
        assertNull(env.pauseRecordDao.all().single().pauseEndAt)
        assertEquals(HomeworkStatus.IN_PROGRESS, env.homeworkRepository.getHomework(homework.id)?.status)
    }

    @Test
    fun `执行权以当前会话角色为准而非入参角色`() = runTest {
        val env = TimerTestEnv()
        val othersHomework = env.seedHomework(studentId = 99L)

        // 学生会话 + 入参家长角色：仍按学生会话判定，越权被拒绝
        val result = env.repository.startSession(othersHomework.id, Role.PARENT)

        assertTrue(result is TimerStartResult.PermissionDenied)
        assertTrue(env.timerSessionDao.all().isEmpty())
    }

    @Test
    fun `家长会话误传学生角色时同步失败且不创建会话`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.useParentSession()

        val result = env.repository.startSession(homework.id, Role.STUDENT)

        assertTrue(result is TimerStartResult.HomeworkSyncFailed)
        assertEquals(
            HomeworkStatusResult.PermissionDenied,
            (result as TimerStartResult.HomeworkSyncFailed).reason,
        )
        assertTrue(env.timerSessionDao.all().isEmpty())
        assertEquals(HomeworkStatus.PENDING, env.homeworkRepository.getHomework(homework.id)?.status)
    }

    @Test
    fun `库内未知会话状态按已完成兜底且四类操作均被拒绝`() = runTest {
        val env = TimerTestEnv()
        val dirtySessionId = env.timerSessionDao.insert(
            TimerSessionEntity(
                homeworkId = 1L,
                studentId = TimerTestEnv.STUDENT_ID,
                parentAccountId = TimerTestEnv.PARENT_ID,
                // 实体已移除 epochDay 的 Kotlin 默认值：此处显式写「未指定」哨兵 0（历史脏行口径）
                epochDay = UNSPECIFIED_EPOCH_DAY,
                startedAt = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS),
                finishedAt = null,
                pausedTotalMillis = 0L,
                pauseCount = 0,
                status = "BROKEN",
            ),
        )

        assertTrue(env.repository.pauseSession(dirtySessionId) is TimerPauseResult.IllegalPhase)
        assertTrue(env.repository.resumeSession(dirtySessionId) is TimerResumeResult.IllegalPhase)
        assertTrue(env.repository.completeSession(dirtySessionId, Role.STUDENT) is TimerCompleteResult.IllegalPhase)
        assertTrue(env.repository.loadSession(dirtySessionId)?.phase == TimerPhase.FINISHED)
        // 兜底为终态后不再被视作「未结束会话」，避免脏数据继续累计走秒
        assertNull(env.repository.findActiveSessionByHomework(1L))
        assertNull(env.repository.findActiveSession(TimerTestEnv.STUDENT_ID))
        // 未被任何操作改写
        assertEquals("BROKEN", env.timerSessionDao.findById(dirtySessionId)!!.status)
        assertTrue(env.pauseRecordDao.all().isEmpty())
    }

    @Test
    fun `完成后的会话小结不再随时间增长`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(60_000L)
        env.repository.completeSession(session.id, Role.STUDENT)

        val rightAfter = env.repository.summarize(session.id)
        env.advance(10 * 60_000L)
        val muchLater = env.repository.summarize(session.id)

        assertEquals(60_000L, rightAfter?.elapsedMillis)
        assertEquals(TimerPhase.FINISHED, rightAfter?.phase)
        assertEquals(rightAfter, muchLater)
    }

    @Test
    fun `暂停中小结暂停累计随时间增长而已用时长冻结`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(30_000L)
        env.repository.pauseSession(session.id)
        env.advance(10_000L)

        val early = env.repository.summarize(session.id)
        env.advance(20_000L)
        val later = env.repository.summarize(session.id)

        assertEquals(30_000L, early?.elapsedMillis)
        assertEquals(10_000L, early?.pausedTotalMillis)
        assertEquals(1, early?.pauseCount)
        // 暂停期间已用时长冻结，累计暂停继续增长
        assertEquals(30_000L, later?.elapsedMillis)
        assertEquals(30_000L, later?.pausedTotalMillis)
    }

    @Test
    fun `按作业维度查询暂停明细跨多次会话返回`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()

        val first = env.start(homework.id)
        env.repository.pauseSession(first.id)
        env.advance(5_000L)
        env.repository.resumeSession(first.id)
        env.repository.completeSession(first.id, Role.STUDENT)
        // 同一作业再次计时需先撤销完成（作业侧状态机：已完成仅可回退为进行中）
        env.homeworkRepository.reopen(homework.id, Role.STUDENT)

        val second = env.start(homework.id)
        env.repository.pauseSession(second.id)
        env.advance(3_000L)

        val pauses = env.repository.loadPausesByHomework(homework.id)

        assertEquals(2, pauses.size)
        assertEquals(listOf(first.id, second.id), pauses.map { it.sessionId })
        assertEquals(
            listOf(1L, 2L),
            env.repository.loadSessionsByHomework(homework.id).map { it.id },
        )
    }

    @Test
    fun `作业已完成时会话仍可正常收尾`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(20_000L)
        // 模拟并发：作业已被其它入口置为已完成（完成后重复完成是幂等成功）
        env.homeworkRepository.complete(homework.id, Role.STUDENT)

        val result = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.Success)
        assertEquals(HomeworkStatus.COMPLETED, (result as TimerCompleteResult.Success).homework.status)
        val stored = env.timerSessionDao.findById(session.id)!!
        assertEquals(TimerPhase.FINISHED.name, stored.status)
        assertEquals(TimerTestEnv.FIXED_MILLIS + 20_000L, stored.finishedAt?.toEpochMilli())
        assertNull(env.repository.findActiveSessionByHomework(homework.id))
    }

    @Test
    fun `按作业取最近会话返回最后一次开始的会话`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val first = env.start(homework.id)
        env.advance(1_000L)
        env.repository.completeSession(first.id, Role.STUDENT)
        env.advance(1_000L)
        env.homeworkRepository.reopen(homework.id, Role.STUDENT)
        val second = env.start(homework.id)

        assertEquals(second.id, env.repository.findLatestSession(homework.id)?.id)
        assertEquals(
            listOf(first.id, second.id),
            env.repository.loadSessionsByStudent(TimerTestEnv.STUDENT_ID).map { it.id },
        )
    }

    private suspend fun TimerTestEnv.start(homeworkId: Long): com.assignmate.app.timer.domain.TimerSession =
        (repository.startSession(homeworkId, Role.STUDENT) as TimerStartResult.Success).session

    private companion object {

        /** `epoch_day` 的「未指定」哨兵（列默认值 0）：仅用于构造历史脏行，正常写入必须显式折算 */
        const val UNSPECIFIED_EPOCH_DAY = 0L
    }
}
