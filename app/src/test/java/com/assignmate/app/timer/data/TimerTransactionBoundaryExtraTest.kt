package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
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
 * 写操作事务边界的补充单测（在 TimerRepositoryImplTest 的事务用例之外）：
 * 恢复路径同样只开一次事务、非法阶段与同步失败路径**不开启**事务（失败不留半成品）、
 * 恢复缺少未结束明细时状态不变、重复暂停清理与收尾在同一事务内完成。
 *
 * 依赖：[TimerTestEnv]（内存 DAO + 事务替身 [FakeTimerTransactionRunner]）。
 */
class TimerTransactionBoundaryExtraTest {

    @Test
    fun `恢复恰好使用一次事务边界`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(10_000L)
        env.pause(session.id)
        env.advance(5_000L)
        val before = env.transactionRunner.invocations

        val result = env.repository.resumeSession(session.id)

        assertTrue(result is TimerResumeResult.Success)
        assertEquals("恢复应恰好使用一次事务边界", before + 1, env.transactionRunner.invocations)
    }

    @Test
    fun `非法阶段的暂停不开启事务也不写入明细`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.repository.completeSession(session.id, Role.STUDENT)
        val before = env.transactionRunner.invocations

        val result = env.repository.pauseSession(session.id)

        assertTrue(result is TimerPauseResult.IllegalPhase)
        assertEquals("非法阶段应在事务之前被拦下", before, env.transactionRunner.invocations)
        assertTrue("不得留下暂停明细", env.pauseRecordDao.all().isEmpty())
    }

    @Test
    fun `恢复缺少未结束明细时只走一次事务且状态不变`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.pause(session.id)
        // 模拟数据不一致：状态为暂停中但未结束明细被删除
        env.pauseRecordDao.delete(env.pauseRecordDao.all().single())
        val before = env.transactionRunner.invocations

        val result = env.repository.resumeSession(session.id)

        assertTrue(result is TimerResumeResult.PauseRecordMissing)
        assertEquals("进入恢复流程即开启一次事务", before + 1, env.transactionRunner.invocations)
        assertEquals("状态不得被改写", TimerPhase.PAUSED.name, env.timerSessionDao.findById(session.id)!!.status)
        assertEquals(0, env.timerSessionDao.findById(session.id)!!.pauseCount)
    }

    @Test
    fun `清理重复未结束暂停与恢复收尾在同一次事务内`() = runTest {
        val env = TimerTestEnv()
        val base = TimerTestEnv.FIXED_MILLIS
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(10_000L)
        env.pause(session.id)
        env.pauseRecordDao.insert(orphanPause(session.id, homework.id, base + 20_000L))
        env.pauseRecordDao.insert(orphanPause(session.id, homework.id, base + 25_000L))
        env.clock.set(base + 40_000L)
        val before = env.transactionRunner.invocations

        val result = env.repository.resumeSession(session.id)

        assertTrue(result is TimerResumeResult.Success)
        assertEquals("清理与收尾必须收敛在同一次事务", before + 1, env.transactionRunner.invocations)
        assertEquals(1, env.pauseRecordDao.all().size)
        assertEquals(30_000L, (result as TimerResumeResult.Success).session.pausedTotalMillis)
    }

    @Test
    fun `完成时作业同步失败不开启事务且会话不收尾`() = runTest {
        val env = TimerTestEnv()
        val homework = timerTestHomework(id = 5L, status = HomeworkStatus.PENDING)
        val fakeHomework = FakeHomeworkRepository().apply { put(homework) }
        val repository = TimerRepositoryImpl(
            timerSessionDao = env.timerSessionDao,
            pauseRecordDao = env.pauseRecordDao,
            homeworkRepository = fakeHomework,
            authRepository = env.authRepository,
            clock = env.clock,
            transactionRunner = env.transactionRunner,
        )
        val session = (repository.startSession(homework.id, Role.STUDENT) as TimerStartResult.Success).session
        env.advance(30_000L)
        fakeHomework.completeOverride = HomeworkStatusResult.IllegalTransition(
            HomeworkStatus.IN_PROGRESS,
            HomeworkStatus.COMPLETED,
        )
        val before = env.transactionRunner.invocations

        val result = repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.HomeworkSyncFailed)
        assertEquals("同步失败不得开启收尾事务", before, env.transactionRunner.invocations)
        val stored = env.timerSessionDao.findById(session.id)!!
        assertEquals(TimerPhase.RUNNING.name, stored.status)
        assertNull(stored.finishedAt)
    }

    private fun orphanPause(sessionId: Long, homeworkId: Long, startMillis: Long): PauseRecordEntity =
        PauseRecordEntity(
            sessionId = sessionId,
            homeworkId = homeworkId,
            pauseStartAt = Instant.ofEpochMilli(startMillis),
        )

    private suspend fun TimerTestEnv.start(homeworkId: Long): com.assignmate.app.timer.domain.TimerSession =
        (repository.startSession(homeworkId, Role.STUDENT) as TimerStartResult.Success).session

    private suspend fun TimerTestEnv.pause(sessionId: Long) {
        repository.pauseSession(sessionId)
    }
}
