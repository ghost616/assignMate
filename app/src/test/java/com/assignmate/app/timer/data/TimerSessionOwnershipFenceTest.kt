package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.domain.TimerPhase
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计时会话的**权限围栏**单测（本次修复重点：pauseSession / resumeSession 原先完全无鉴权）。
 *
 * 覆盖口径（与 auth 统一归属能力 `AuthRepository.isStudentOwnedBy` 一致）：
 * - [TimerRepository.startSession]：家长仅可对**名下学生**的作业开始计时（既有执行权 `canOperate`
 *   的家长分支不做归属判定，故此前存在越权面），学生仅限本人名下；
 * - [TimerRepository.pauseSession] / [TimerRepository.resumeSession] / [TimerRepository.completeSession]：
 *   按 sessionId 查到会话后，依其**库内所属 studentId** 判定（学生会话仅限本人、家长仅限名下学生）；
 * - 越权（含无有效会话）一律返回各自结果的 `PermissionDenied`，并断言**零写入**
 *   （会话表与暂停明细表 writeCount 不变、事务边界未被开启、作业状态未被同步）；
 * - 判定顺序：归属围栏先于阶段判定（越权者无法从「阶段不合法」推断他人会话状态）；
 * - 合法操作的回归用例：家长可操作名下学生会话，学生可操作本人会话。
 *
 * 依赖：[TimerTestEnv]（内存计时 DAO + 真实 homework 仓库实现 + 可注入会话），
 * 跨家长场景直接经 homework 测试替身 `FakeAuthRepository.setSession` 切换会话。
 */
class TimerSessionOwnershipFenceTest {

    // ---- 开始计时：家长归属围栏 ----

    @Test
    fun `家长不能为名下之外的学生作业开始计时且不写入`() = runTest {
        val env = TimerTestEnv()
        val othersHomework = env.seedHomework(studentId = OTHER_STUDENT_ID)
        env.useParentSession()
        val writesBefore = env.writeCounts()

        val result = env.repository.startSession(othersHomework.id, Role.PARENT)

        assertTrue("家长不能对非名下学生的作业计时", result is TimerStartResult.PermissionDenied)
        assertEquals("越权不得产生任何写入", writesBefore, env.writeCounts())
        assertTrue("越权不得创建会话", env.timerSessionDao.all().isEmpty())
        assertEquals(
            "越权不得改变作业状态",
            HomeworkStatus.PENDING,
            env.homeworkRepository.getHomework(othersHomework.id)?.status,
        )
    }

    @Test
    fun `家长不能为他人家长名下学生的作业开始计时且不写入`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.useParentSessionOf(OTHER_PARENT_ID)
        val writesBefore = env.writeCounts()

        val result = env.repository.startSession(homework.id, Role.PARENT)

        assertTrue(result is TimerStartResult.PermissionDenied)
        assertEquals(writesBefore, env.writeCounts())
        assertTrue(env.timerSessionDao.all().isEmpty())
        assertEquals(HomeworkStatus.PENDING, env.homeworkRepository.getHomework(homework.id)?.status)
    }

    @Test
    fun `越权判定先于作业状态判定`() = runTest {
        val env = TimerTestEnv()
        // 他人名下且已完成的作业：若先判状态会返回 NotStartable，从而泄露他人作业状态
        val othersHomework = env.seedHomework(studentId = OTHER_STUDENT_ID, status = HomeworkStatus.COMPLETED)

        val result = env.repository.startSession(othersHomework.id, Role.STUDENT)

        assertTrue("越权应优先于状态判定", result is TimerStartResult.PermissionDenied)
        assertTrue(env.timerSessionDao.all().isEmpty())
    }

    @Test
    fun `家长可为自己名下学生的作业开始计时`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.useParentSession()

        val result = env.repository.startSession(homework.id, Role.PARENT)

        assertTrue(result is TimerStartResult.Success)
        assertEquals(TimerTestEnv.STUDENT_ID, (result as TimerStartResult.Success).session.studentId)
        assertEquals(HomeworkStatus.IN_PROGRESS, env.homeworkRepository.getHomework(homework.id)?.status)
    }

    // ---- 暂停：会话归属围栏 ----

    @Test
    fun `学生不能暂停他人名下学生的计时会话且不写入`() = runTest {
        val env = TimerTestEnv()
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID)
        val writesBefore = env.writeCounts()
        val transactionsBefore = env.transactionRunner.invocations

        val result = env.repository.pauseSession(foreignSessionId)

        assertTrue("学生会话只能操作本人会话", result is TimerPauseResult.PermissionDenied)
        assertEquals("越权不得写入会话或明细", writesBefore, env.writeCounts())
        assertEquals("越权不得开启事务边界", transactionsBefore, env.transactionRunner.invocations)
        assertTrue("越权不得落暂停明细", env.pauseRecordDao.all().isEmpty())
        assertEquals(TimerPhase.RUNNING.name, env.timerSessionDao.findById(foreignSessionId)!!.status)
    }

    @Test
    fun `家长不能暂停他人名下学生的计时会话且不写入`() = runTest {
        val env = TimerTestEnv()
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID)
        env.useParentSession()
        val writesBefore = env.writeCounts()

        val result = env.repository.pauseSession(foreignSessionId)

        assertTrue("家长只能操作名下学生的会话", result is TimerPauseResult.PermissionDenied)
        assertEquals(writesBefore, env.writeCounts())
        assertTrue(env.pauseRecordDao.all().isEmpty())
        assertEquals(TimerPhase.RUNNING.name, env.timerSessionDao.findById(foreignSessionId)!!.status)
    }

    @Test
    fun `无有效会话时暂停被拒绝且不写入`() = runTest {
        val env = TimerTestEnv()
        val ownSessionId = env.seedSession(studentId = TimerTestEnv.STUDENT_ID)
        env.logout()
        val writesBefore = env.writeCounts()

        val result = env.repository.pauseSession(ownSessionId)

        assertTrue("未登录不得操作任何会话", result is TimerPauseResult.PermissionDenied)
        assertEquals(writesBefore, env.writeCounts())
        assertTrue(env.pauseRecordDao.all().isEmpty())
    }

    @Test
    fun `越权的脏状态会话暂停返回权限失败而非阶段不合法`() = runTest {
        val env = TimerTestEnv()
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID, statusName = "BROKEN")

        val result = env.repository.pauseSession(foreignSessionId)

        assertTrue("不得借阶段判定泄露他人会话状态", result is TimerPauseResult.PermissionDenied)
        assertEquals("BROKEN", env.timerSessionDao.findById(foreignSessionId)!!.status)
    }

    @Test
    fun `家长可暂停自己名下学生的计时会话`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.useParentSession()
        val started = (env.repository.startSession(homework.id, Role.PARENT) as TimerStartResult.Success).session

        val result = env.repository.pauseSession(started.id)

        assertTrue(result is TimerPauseResult.Success)
        assertEquals(TimerPhase.PAUSED, (result as TimerPauseResult.Success).session.phase)
        assertEquals(1, env.pauseRecordDao.all().size)
    }

    // ---- 恢复：会话归属围栏 ----

    @Test
    fun `学生不能恢复他人名下学生的计时会话且不写入`() = runTest {
        val env = TimerTestEnv()
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID, status = TimerPhase.PAUSED)
        env.seedOngoingPause(foreignSessionId)
        val writesBefore = env.writeCounts()
        val transactionsBefore = env.transactionRunner.invocations

        val result = env.repository.resumeSession(foreignSessionId)

        assertTrue(result is TimerResumeResult.PermissionDenied)
        assertEquals("越权不得写入会话或明细", writesBefore, env.writeCounts())
        assertEquals("越权不得开启事务边界", transactionsBefore, env.transactionRunner.invocations)
        assertNull("越权不得收尾他人暂停明细", env.pauseRecordDao.all().single().pauseEndAt)
        val stored = env.timerSessionDao.findById(foreignSessionId)!!
        assertEquals(TimerPhase.PAUSED.name, stored.status)
        assertEquals(0L, stored.pausedTotalMillis)
        assertEquals(0, stored.pauseCount)
    }

    @Test
    fun `家长不能恢复他人名下学生的计时会话且不写入`() = runTest {
        val env = TimerTestEnv()
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID, status = TimerPhase.PAUSED)
        env.seedOngoingPause(foreignSessionId)
        env.useParentSession()
        val writesBefore = env.writeCounts()

        val result = env.repository.resumeSession(foreignSessionId)

        assertTrue(result is TimerResumeResult.PermissionDenied)
        assertEquals(writesBefore, env.writeCounts())
        assertNull(env.pauseRecordDao.all().single().pauseEndAt)
        assertEquals(TimerPhase.PAUSED.name, env.timerSessionDao.findById(foreignSessionId)!!.status)
    }

    @Test
    fun `无有效会话时恢复被拒绝且不写入`() = runTest {
        val env = TimerTestEnv()
        val ownSessionId = env.seedSession(studentId = TimerTestEnv.STUDENT_ID, status = TimerPhase.PAUSED)
        env.seedOngoingPause(ownSessionId)
        env.logout()
        val writesBefore = env.writeCounts()

        val result = env.repository.resumeSession(ownSessionId)

        assertTrue(result is TimerResumeResult.PermissionDenied)
        assertEquals(writesBefore, env.writeCounts())
        assertNull(env.pauseRecordDao.all().single().pauseEndAt)
    }

    @Test
    fun `家长可恢复自己名下学生的暂停会话`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.useParentSession()
        val started = (env.repository.startSession(homework.id, Role.PARENT) as TimerStartResult.Success).session
        env.repository.pauseSession(started.id)
        env.advance(20_000L)

        val result = env.repository.resumeSession(started.id)

        assertTrue(result is TimerResumeResult.Success)
        val resumed = (result as TimerResumeResult.Success).session
        assertEquals(TimerPhase.RUNNING, resumed.phase)
        assertEquals(20_000L, resumed.pausedTotalMillis)
        assertEquals(1, resumed.pauseCount)
    }

    // ---- 完成：会话归属围栏（入口即拦，不依赖下游 homework） ----

    @Test
    fun `学生不能完成他人名下学生的计时会话且不同步作业`() = runTest {
        val env = TimerTestEnv()
        val homework = timerTestHomework(id = 7L, studentId = OTHER_STUDENT_ID, status = HomeworkStatus.IN_PROGRESS)
        val fakeHomework = FakeHomeworkRepository().apply { put(homework) }
        val repository = env.repositoryWith(fakeHomework)
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID, homeworkId = homework.id)
        val writesBefore = env.writeCounts()
        val transactionsBefore = env.transactionRunner.invocations

        val result = repository.completeSession(foreignSessionId, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.PermissionDenied)
        assertEquals("越权不得触达作业状态同步", 0, fakeHomework.completeCalls)
        assertEquals(HomeworkStatus.IN_PROGRESS, fakeHomework.getHomework(homework.id)?.status)
        assertEquals("越权不得写入会话或明细", writesBefore, env.writeCounts())
        assertEquals("越权不得开启收尾事务", transactionsBefore, env.transactionRunner.invocations)
        val stored = env.timerSessionDao.findById(foreignSessionId)!!
        assertEquals(TimerPhase.RUNNING.name, stored.status)
        assertNull("越权不得写入结束时刻", stored.finishedAt)
    }

    @Test
    fun `家长不能完成他人名下学生的计时会话且不同步作业`() = runTest {
        val env = TimerTestEnv()
        val homework = timerTestHomework(id = 8L, studentId = OTHER_STUDENT_ID, status = HomeworkStatus.IN_PROGRESS)
        val fakeHomework = FakeHomeworkRepository().apply { put(homework) }
        val repository = env.repositoryWith(fakeHomework)
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID, homeworkId = homework.id)
        env.useParentSession()
        val writesBefore = env.writeCounts()

        val result = repository.completeSession(foreignSessionId, Role.PARENT)

        assertTrue(result is TimerCompleteResult.PermissionDenied)
        assertEquals("越权不得触达作业状态同步", 0, fakeHomework.completeCalls)
        assertEquals(HomeworkStatus.IN_PROGRESS, fakeHomework.getHomework(homework.id)?.status)
        assertEquals(writesBefore, env.writeCounts())
        assertEquals(TimerPhase.RUNNING.name, env.timerSessionDao.findById(foreignSessionId)!!.status)
    }

    @Test
    fun `无有效会话时完成被拒绝且不同步作业`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val sessionId = env.seedSession(studentId = TimerTestEnv.STUDENT_ID, homeworkId = homework.id)
        env.logout()
        val writesBefore = env.writeCounts()

        val result = env.repository.completeSession(sessionId, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.PermissionDenied)
        assertEquals(writesBefore, env.writeCounts())
        assertEquals(
            "未登录不得同步作业状态",
            HomeworkStatus.PENDING,
            env.homeworkRepository.getHomework(homework.id)?.status,
        )
        val stored = env.timerSessionDao.findById(sessionId)!!
        assertEquals(TimerPhase.RUNNING.name, stored.status)
        assertNull(stored.finishedAt)
    }

    @Test
    fun `家长可完成自己名下学生的计时会话`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.useParentSession()
        val started = (env.repository.startSession(homework.id, Role.PARENT) as TimerStartResult.Success).session
        env.advance(30_000L)

        val result = env.repository.completeSession(started.id, Role.PARENT)

        assertTrue(result is TimerCompleteResult.Success)
        assertEquals(HomeworkStatus.COMPLETED, (result as TimerCompleteResult.Success).homework.status)
        assertEquals(HomeworkStatus.COMPLETED, env.homeworkRepository.getHomework(homework.id)?.status)
        assertEquals(TimerPhase.FINISHED.name, env.timerSessionDao.findById(started.id)!!.status)
    }

    // ---- 测试辅助 ----

    /** 切换为「指定家长」的会话（用于构造跨家长越权场景） */
    private fun TimerTestEnv.useParentSessionOf(parentId: Long) {
        authRepository.setSession(SessionState(role = Role.PARENT, parentId = parentId, studentId = null))
    }

    /** 直接落一条会话行（绕过 startSession，用于构造「他人名下」的会话数据） */
    private suspend fun TimerTestEnv.seedSession(
        studentId: Long,
        homeworkId: Long = 1L,
        status: TimerPhase = TimerPhase.RUNNING,
        statusName: String = status.persistedName,
    ): Long = timerSessionDao.insert(
        TimerSessionEntity(
            homeworkId = homeworkId,
            studentId = studentId,
            parentAccountId = TimerTestEnv.PARENT_ID,
            // 本用例只验证归属围栏（不涉及逐日归属），按「未指定」哨兵 0 显式构造
            epochDay = UNSPECIFIED_EPOCH_DAY,
            startedAt = Instant.ofEpochMilli(clock.currentTimeMillis()),
            finishedAt = null,
            pausedTotalMillis = 0L,
            pauseCount = 0,
            status = statusName,
        ),
    )

    /** 直接落一条「未结束暂停」明细（模拟暂停中的会话现场） */
    private suspend fun TimerTestEnv.seedOngoingPause(sessionId: Long, homeworkId: Long = 1L) {
        pauseRecordDao.insert(
            PauseRecordEntity(
                sessionId = sessionId,
                homeworkId = homeworkId,
                // 未结束暂停明细同样按「未指定」哨兵显式构造（实体已无 Kotlin 默认值）
                epochDay = UNSPECIFIED_EPOCH_DAY,
                pauseStartAt = Instant.ofEpochMilli(clock.currentTimeMillis()),
                pauseEndAt = null,
            ),
        )
    }

    /** 全部落库写入次数快照（会话表 + 暂停明细表），用于「越权零写入」断言 */
    private fun TimerTestEnv.writeCounts(): Pair<Int, Int> =
        timerSessionDao.writeCount to pauseRecordDao.writeCount

    /** 以指定作业仓库构造被测仓库（便于断言「越权时未触达作业状态同步」） */
    private fun TimerTestEnv.repositoryWith(homeworkRepository: HomeworkRepository): TimerRepositoryImpl =
        TimerRepositoryImpl(
            timerSessionDao = timerSessionDao,
            pauseRecordDao = pauseRecordDao,
            homeworkRepository = homeworkRepository,
            authRepository = authRepository,
            clock = clock,
            transactionRunner = transactionRunner,
            dailyRecordRepository = dailyRecordRepository,
        )

    private companion object {

        /** 另一个家长（与 [TimerTestEnv.PARENT_ID] 分属不同账号） */
        const val OTHER_PARENT_ID = 9L

        /** 另一位学生（不属于 [TimerTestEnv.PARENT_ID] 名下） */
        const val OTHER_STUDENT_ID = 99L

        /** `epoch_day` 的「未指定」哨兵（列默认值 0）：仅用于构造不涉及逐日归属的测试数据 */
        const val UNSPECIFIED_EPOCH_DAY = 0L
    }
}
