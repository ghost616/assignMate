package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.domain.TimerCalculations
import com.assignmate.app.timer.domain.TimerPhase
import com.assignmate.app.timer.domain.TimerSession
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TimerRepositoryImpl 仓库关键流程单测：开始（含幂等/权限/状态不可开始/同步失败）、
 * 暂停（明细落库与非法阶段）、恢复（补齐结束时刻并累计汇总、明细缺失分支）、
 * 完成（结束时刻与汇总写入、暂停中直接完成、同步失败不收尾）、
 * 以及供 stats 使用的查询面（按作业/学生取会话与暂停明细、会话小结）。
 *
 * 依赖：[TimerTestEnv]（内存计时 DAO + 真实 homework 仓库实现 + 可推进固定时钟）。
 */
class TimerRepositoryImplTest {

    // ---- 开始计时 ----

    @Test
    fun `开始计时把作业置为进行中并写入进行中会话`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()

        val result = env.repository.startSession(homework.id, Role.STUDENT)

        assertTrue(result is TimerStartResult.Success)
        val session = (result as TimerStartResult.Success).session
        assertEquals(TimerPhase.RUNNING, session.phase)
        assertEquals(homework.id, session.homeworkId)
        assertEquals(TimerTestEnv.STUDENT_ID, session.studentId)
        assertEquals(TimerTestEnv.PARENT_ID, session.parentAccountId)
        assertEquals(TimerTestEnv.FIXED_MILLIS, session.startedAt.toEpochMilli())
        assertNull(session.finishedAt)
        assertEquals(0L, session.pausedTotalMillis)
        assertEquals(0, session.pauseCount)
        assertEquals(HomeworkStatus.IN_PROGRESS, env.homeworkRepository.getHomework(homework.id)?.status)
        assertEquals(TimerPhase.RUNNING.name, env.timerSessionDao.all().single().status)
    }

    @Test
    fun `已有未结束会话时开始计时幂等复用不新建`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()

        val first = env.start(homework.id)
        env.advance(30_000L)
        val second = env.start(homework.id)

        assertEquals(first.id, second.id)
        assertEquals(TimerTestEnv.FIXED_MILLIS, second.startedAt.toEpochMilli())
        assertEquals(1, env.timerSessionDao.all().size)
    }

    @Test
    fun `已完成作业不可开始计时`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(status = HomeworkStatus.COMPLETED)

        val result = env.repository.startSession(homework.id, Role.STUDENT)

        assertTrue(result is TimerStartResult.NotStartable)
        assertEquals(HomeworkStatus.COMPLETED, (result as TimerStartResult.NotStartable).status)
        assertTrue(env.timerSessionDao.all().isEmpty())
    }

    @Test
    fun `已记录（未排定时间）作业不可开始计时`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(status = HomeworkStatus.RECORDED)

        val result = env.repository.startSession(homework.id, Role.STUDENT)

        assertTrue(result is TimerStartResult.NotStartable)
        assertEquals(HomeworkStatus.RECORDED, (result as TimerStartResult.NotStartable).status)
    }

    @Test
    fun `作业不存在时开始计时返回作业不存在`() = runTest {
        val env = TimerTestEnv()

        val result = env.repository.startSession(999L, Role.STUDENT)

        assertTrue(result is TimerStartResult.HomeworkNotFound)
    }

    @Test
    fun `学生不能为他人名下作业计时`() = runTest {
        val env = TimerTestEnv()
        val othersHomework = env.seedHomework(studentId = 99L)

        val result = env.repository.startSession(othersHomework.id, Role.STUDENT)

        assertTrue(result is TimerStartResult.PermissionDenied)
        assertTrue(env.timerSessionDao.all().isEmpty())
        assertEquals(HomeworkStatus.PENDING, env.homeworkRepository.getHomework(othersHomework.id)?.status)
    }

    @Test
    fun `无有效会话时开始计时被拒绝`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.logout()

        val result = env.repository.startSession(homework.id, Role.STUDENT)

        assertTrue(result is TimerStartResult.NoActiveSession)
        assertEquals(HomeworkStatus.PENDING, env.homeworkRepository.getHomework(homework.id)?.status)
    }

    @Test
    fun `家长会话可为自己学生名下作业计时`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.useParentSession()

        val result = env.repository.startSession(homework.id, Role.PARENT)

        assertTrue(result is TimerStartResult.Success)
        assertEquals(HomeworkStatus.IN_PROGRESS, env.homeworkRepository.getHomework(homework.id)?.status)
    }

    @Test
    fun `作业状态同步失败时不创建会话`() = runTest {
        val env = TimerTestEnv()
        val homework = timerTestHomework(id = 5L, status = HomeworkStatus.PENDING)
        val fakeHomework = FakeHomeworkRepository().apply {
            put(homework)
            startProgressOverride = HomeworkStatusResult.IllegalTransition(
                HomeworkStatus.PENDING,
                HomeworkStatus.IN_PROGRESS,
            )
        }
        val repository = TimerRepositoryImpl(
            timerSessionDao = env.timerSessionDao,
            pauseRecordDao = env.pauseRecordDao,
            homeworkRepository = fakeHomework,
            authRepository = env.authRepository,
            clock = env.clock,
            transactionRunner = env.transactionRunner,
            dailyRecordRepository = env.dailyRecordRepository,
        )

        val result = repository.startSession(homework.id, Role.STUDENT)

        assertTrue(result is TimerStartResult.HomeworkSyncFailed)
        assertEquals(1, fakeHomework.startProgressCalls)
        assertTrue(env.timerSessionDao.all().isEmpty())
    }

    // ---- 暂停 ----

    @Test
    fun `暂停写入未结束暂停明细并置会话为暂停中`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(40_000L)

        val result = env.repository.pauseSession(session.id)

        assertTrue(result is TimerPauseResult.Success)
        assertEquals(TimerPhase.PAUSED, (result as TimerPauseResult.Success).session.phase)
        val row = env.pauseRecordDao.all().single()
        assertEquals(session.id, row.sessionId)
        assertEquals(homework.id, row.homeworkId)
        assertEquals(TimerTestEnv.FIXED_MILLIS + 40_000L, row.pauseStartAt.toEpochMilli())
        assertNull(row.pauseEndAt)
        // 会话汇总为「恢复/完成时才刷新的快照」，暂停瞬间只改状态
        val stored = env.timerSessionDao.findById(session.id)!!
        assertEquals(TimerPhase.PAUSED.name, stored.status)
        assertEquals(0L, stored.pausedTotalMillis)
        assertEquals(0, stored.pauseCount)
    }

    @Test
    fun `重复暂停被拒绝且不新增明细`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.repository.pauseSession(session.id)

        val result = env.repository.pauseSession(session.id)

        assertTrue(result is TimerPauseResult.IllegalPhase)
        assertEquals(TimerPhase.PAUSED, (result as TimerPauseResult.IllegalPhase).phase)
        assertEquals(1, env.pauseRecordDao.all().size)
    }

    @Test
    fun `会话不存在时暂停返回会话不存在`() = runTest {
        val env = TimerTestEnv()

        val result = env.repository.pauseSession(404L)

        assertTrue(result is TimerPauseResult.SessionNotFound)
    }

    @Test
    fun `已完成的会话不能再暂停`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.repository.completeSession(session.id, Role.STUDENT)

        val result = env.repository.pauseSession(session.id)

        assertTrue(result is TimerPauseResult.IllegalPhase)
        assertEquals(TimerPhase.FINISHED, (result as TimerPauseResult.IllegalPhase).phase)
        assertTrue(env.pauseRecordDao.all().isEmpty())
    }

    // ---- 恢复 ----

    @Test
    fun `恢复补齐暂停结束时刻并累计暂停汇总`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(30_000L)
        env.pause(session.id)
        env.advance(20_000L)

        val result = env.repository.resumeSession(session.id)

        assertTrue(result is TimerResumeResult.Success)
        val resumed = (result as TimerResumeResult.Success).session
        assertEquals(TimerPhase.RUNNING, resumed.phase)
        assertEquals(20_000L, resumed.pausedTotalMillis)
        assertEquals(1, resumed.pauseCount)
        val stored = env.timerSessionDao.findById(session.id)!!
        assertEquals(TimerPhase.RUNNING.name, stored.status)
        assertEquals(20_000L, stored.pausedTotalMillis)
        assertEquals(1, stored.pauseCount)
        val row = env.pauseRecordDao.all().single()
        assertEquals(TimerTestEnv.FIXED_MILLIS + 30_000L, row.pauseStartAt.toEpochMilli())
        assertEquals(TimerTestEnv.FIXED_MILLIS + 50_000L, row.pauseEndAt?.toEpochMilli())
        // 已用时长扣掉暂停段：总跨度 50s - 暂停 20s = 30s
        assertEquals(
            30_000L,
            TimerCalculations.elapsedOf(resumed, env.repository.loadPauses(session.id), env.clock.currentTimeMillis()),
        )
    }

    @Test
    fun `多次暂停恢复后汇总累计全部暂停段`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(10_000L)
        env.pause(session.id)
        env.advance(5_000L)
        env.resume(session.id)
        env.advance(10_000L)
        env.pause(session.id)
        env.advance(7_000L)

        val result = env.repository.resumeSession(session.id)

        val resumed = (result as TimerResumeResult.Success).session
        assertEquals(12_000L, resumed.pausedTotalMillis)
        assertEquals(2, resumed.pauseCount)
        assertEquals(2, env.pauseRecordDao.all().size)
        assertEquals(2, env.repository.loadPauses(session.id).size)
    }

    @Test
    fun `未暂停的会话不能恢复`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)

        val result = env.repository.resumeSession(session.id)

        assertTrue(result is TimerResumeResult.IllegalPhase)
        assertEquals(TimerPhase.RUNNING, (result as TimerResumeResult.IllegalPhase).phase)
    }

    @Test
    fun `会话不存在时恢复返回会话不存在`() = runTest {
        val env = TimerTestEnv()

        val result = env.repository.resumeSession(404L)

        assertTrue(result is TimerResumeResult.SessionNotFound)
    }

    @Test
    fun `暂停中的会话缺少未结束明细时恢复返回明细缺失`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.pause(session.id)
        // 模拟数据不一致：状态为暂停中但未结束明细被删除
        val row = env.pauseRecordDao.all().single()
        env.pauseRecordDao.delete(row)

        val result = env.repository.resumeSession(session.id)

        assertTrue(result is TimerResumeResult.PauseRecordMissing)
        assertEquals(TimerPhase.PAUSED.name, env.timerSessionDao.findById(session.id)!!.status)
    }

    // ---- 完成作业 ----

    @Test
    fun `完成作业写入结束时刻与暂停汇总并同步作业状态`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(60_000L)
        env.pause(session.id)
        env.advance(10_000L)
        env.resume(session.id)
        env.advance(90_000L)

        val result = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.Success)
        val success = result as TimerCompleteResult.Success
        assertEquals(TimerPhase.FINISHED, success.session.phase)
        assertEquals(TimerTestEnv.FIXED_MILLIS + 160_000L, success.session.finishedAt?.toEpochMilli())
        assertEquals(10_000L, success.session.pausedTotalMillis)
        assertEquals(1, success.session.pauseCount)
        assertEquals(HomeworkStatus.COMPLETED, success.homework.status)
        assertEquals(HomeworkStatus.COMPLETED, env.homeworkRepository.getHomework(homework.id)?.status)
        val stored = env.timerSessionDao.findById(session.id)!!
        assertEquals(TimerPhase.FINISHED.name, stored.status)
        assertEquals(TimerTestEnv.FIXED_MILLIS + 160_000L, stored.finishedAt?.toEpochMilli())
        assertEquals(10_000L, stored.pausedTotalMillis)
        assertEquals(1, stored.pauseCount)
        // 已用时长 = 总跨度 160s - 暂停 10s = 150s
        assertEquals(
            150_000L,
            TimerCalculations.elapsedOf(
                success.session,
                env.repository.loadPauses(session.id),
                env.clock.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun `暂停中直接完成会先收尾未结束的暂停明细`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(20_000L)
        env.pause(session.id)
        env.advance(15_000L)

        val result = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.Success)
        val success = result as TimerCompleteResult.Success
        assertEquals(15_000L, success.session.pausedTotalMillis)
        assertEquals(1, success.session.pauseCount)
        val row = env.pauseRecordDao.all().single()
        assertEquals(TimerTestEnv.FIXED_MILLIS + 35_000L, row.pauseEndAt?.toEpochMilli())
        assertEquals(
            20_000L,
            TimerCalculations.elapsedOf(
                success.session,
                env.repository.loadPauses(session.id),
                env.clock.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun `已完成的会话不能重复完成`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.repository.completeSession(session.id, Role.STUDENT)

        val result = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.IllegalPhase)
        assertEquals(TimerPhase.FINISHED, (result as TimerCompleteResult.IllegalPhase).phase)
    }

    @Test
    fun `会话不存在时完成返回会话不存在`() = runTest {
        val env = TimerTestEnv()

        val result = env.repository.completeSession(404L, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.SessionNotFound)
    }

    @Test
    fun `作业状态同步失败时会话不收尾`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        val fakeHomework = FakeHomeworkRepository().apply {
            put(timerTestHomework(id = homework.id, status = HomeworkStatus.IN_PROGRESS))
            completeOverride = HomeworkStatusResult.IllegalTransition(
                HomeworkStatus.IN_PROGRESS,
                HomeworkStatus.COMPLETED,
            )
        }
        val repository = TimerRepositoryImpl(
            timerSessionDao = env.timerSessionDao,
            pauseRecordDao = env.pauseRecordDao,
            homeworkRepository = fakeHomework,
            authRepository = env.authRepository,
            clock = env.clock,
            transactionRunner = env.transactionRunner,
            dailyRecordRepository = env.dailyRecordRepository,
        )
        env.advance(30_000L)

        val result = repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.HomeworkSyncFailed)
        assertEquals(1, fakeHomework.completeCalls)
        val stored = env.timerSessionDao.findById(session.id)!!
        assertEquals(TimerPhase.RUNNING.name, stored.status)
        assertNull(stored.finishedAt)
    }

    // ---- 查询（供计时页恢复现场与 stats 统计） ----

    @Test
    fun `未开始计时的作业没有任何会话与暂停明细`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()

        assertNull(env.repository.findActiveSessionByHomework(homework.id))
        assertNull(env.repository.findLatestSession(homework.id))
        assertNull(env.repository.findActiveSession(TimerTestEnv.STUDENT_ID))
        assertNull(env.repository.loadSessionDetail(1L))
        assertNull(env.repository.summarize(1L))
        assertTrue(env.repository.loadSessionsByHomework(homework.id).isEmpty())
        assertTrue(env.repository.loadPausesByHomework(homework.id).isEmpty())
    }

    @Test
    fun `按作业与按学生均可取到未结束会话`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.pause(session.id)

        assertEquals(session.id, env.repository.findActiveSessionByHomework(homework.id)?.id)
        assertEquals(session.id, env.repository.findActiveSession(TimerTestEnv.STUDENT_ID)?.id)
        assertEquals(session.id, env.repository.findLatestSession(homework.id)?.id)
        assertEquals(TimerPhase.PAUSED, env.repository.loadSession(session.id)?.phase)
        assertNull(env.repository.findActiveSession(99L))
    }

    @Test
    fun `按学生取未结束会话返回最近开始的一条`() = runTest {
        val env = TimerTestEnv()
        val firstHomework = env.seedHomework(content = "第一项")
        val first = env.start(firstHomework.id)
        env.advance(1_000L)
        val secondHomework = env.seedHomework(content = "第二项")
        val second = env.start(secondHomework.id)

        val active = env.repository.findActiveSession(TimerTestEnv.STUDENT_ID)

        assertEquals(second.id, active?.id)
        assertTrue(first.id != second.id)
    }

    @Test
    fun `会话详情包含暂停明细供恢复现场使用`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.pause(session.id)

        val detail = env.repository.loadSessionDetail(session.id)

        assertEquals(session.id, detail?.session?.id)
        assertEquals(TimerPhase.PAUSED, detail?.session?.phase)
        assertEquals(1, detail?.pauses?.size)
        assertTrue(detail!!.pauses.single().isOngoing)
    }

    @Test
    fun `会话小结给出已用时长与暂停数据`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(60_000L)

        val summary = env.repository.summarize(session.id)

        assertEquals(session.id, summary?.sessionId)
        assertEquals(TimerPhase.RUNNING, summary?.phase)
        assertEquals(60_000L, summary?.elapsedMillis)
        assertEquals(0L, summary?.pausedTotalMillis)
        assertEquals(0, summary?.pauseCount)
    }

    @Test
    fun `完成后会话进入历史查询且不再属于未结束会话`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(45_000L)
        env.repository.completeSession(session.id, Role.STUDENT)

        assertNull(env.repository.findActiveSessionByHomework(homework.id))
        assertEquals(
            listOf(session.id),
            env.repository.loadSessionsByHomework(homework.id).map { it.id },
        )
        assertEquals(
            listOf(session.id),
            env.repository.loadSessionsByStudent(TimerTestEnv.STUDENT_ID).map { it.id },
        )
        assertEquals(session.id, env.repository.findLatestSession(homework.id)?.id)
    }

    // ---- 事务化与不一致数据清理（评审修复项） ----

    @Test
    fun `暂停的明细与状态写入收敛在同一个事务`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        val before = env.transactionRunner.invocations

        env.repository.pauseSession(session.id)

        assertEquals("暂停应恰好使用一次事务边界", before + 1, env.transactionRunner.invocations)
        assertEquals(TimerPhase.PAUSED.name, env.timerSessionDao.findById(session.id)!!.status)
        assertEquals(1, env.pauseRecordDao.all().size)
    }

    @Test
    fun `完成收尾写入收敛在同一个事务`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(30_000L)
        val before = env.transactionRunner.invocations

        env.repository.completeSession(session.id, Role.STUDENT)

        assertEquals("完成收尾应恰好使用一次事务边界", before + 1, env.transactionRunner.invocations)
    }

    @Test
    fun `恢复时清理重复未结束暂停只保留最早一条`() = runTest {
        val env = TimerTestEnv()
        val base = TimerTestEnv.FIXED_MILLIS
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(10_000L)
        env.pause(session.id)
        // 模拟历史中断留下的两条重复未结束明细（起点更晚，属脏数据）
        env.pauseRecordDao.insert(orphanPause(session.id, homework.id, base + 20_000L))
        env.pauseRecordDao.insert(orphanPause(session.id, homework.id, base + 30_000L))
        env.clock.set(base + 40_000L)

        val result = env.repository.resumeSession(session.id)

        assertTrue(result is TimerResumeResult.Success)
        val resumed = (result as TimerResumeResult.Success).session
        assertEquals("只保留最早一条真实暂停", 1, resumed.pauseCount)
        assertEquals("暂停时长按最早起点到恢复时刻计算", 30_000L, resumed.pausedTotalMillis)
        assertEquals("重复明细应被删除", 1, env.pauseRecordDao.all().size)
        assertEquals(
            base + 10_000L,
            env.repository.loadPauses(session.id).single().pauseStartAt.toEpochMilli(),
        )
    }

    @Test
    fun `完成收尾同样清理重复未结束暂停不虚增次数`() = runTest {
        val env = TimerTestEnv()
        val base = TimerTestEnv.FIXED_MILLIS
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(10_000L)
        env.pause(session.id)
        env.pauseRecordDao.insert(orphanPause(session.id, homework.id, base + 20_000L))
        env.clock.set(base + 40_000L)

        val result = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.Success)
        val success = result as TimerCompleteResult.Success
        assertEquals(1, success.session.pauseCount)
        assertEquals(30_000L, success.session.pausedTotalMillis)
        assertEquals(1, env.pauseRecordDao.all().size)
    }

    @Test
    fun `仅有一条未结束暂停时不做任何清理`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(10_000L)
        env.pause(session.id)
        env.advance(5_000L)

        val result = env.repository.resumeSession(session.id)

        assertTrue(result is TimerResumeResult.Success)
        assertEquals(1, env.pauseRecordDao.all().size)
        assertEquals(5_000L, (result as TimerResumeResult.Success).session.pausedTotalMillis)
    }

    // ---- 测试辅助 ----

    /** 构造「未结束暂停」脏数据行（模拟历史上中断写入留下的重复记录） */
    private fun orphanPause(sessionId: Long, homeworkId: Long, startMillis: Long): PauseRecordEntity =
        PauseRecordEntity(
            sessionId = sessionId,
            homeworkId = homeworkId,
            // 实体已移除 epochDay 的 Kotlin 默认值：脏行按「未指定」哨兵 0 显式构造
            epochDay = 0L,
            pauseStartAt = Instant.ofEpochMilli(startMillis),
        )

    private suspend fun TimerTestEnv.start(
        homeworkId: Long,
        role: Role = Role.STUDENT,
    ): TimerSession = (repository.startSession(homeworkId, role) as TimerStartResult.Success).session

    private suspend fun TimerTestEnv.pause(sessionId: Long): TimerSession =
        (repository.pauseSession(sessionId) as TimerPauseResult.Success).session

    private suspend fun TimerTestEnv.resume(sessionId: Long): TimerSession =
        (repository.resumeSession(sessionId) as TimerResumeResult.Success).session
}
