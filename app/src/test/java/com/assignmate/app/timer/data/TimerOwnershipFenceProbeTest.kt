package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.timer.domain.TimerPhase
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * timer 权限围栏的**对抗性补充用例**（本轮修复的独立验证，不复用既有用例的断言结构）。
 *
 * 出发点：既有 TimerSessionOwnershipFenceTest 已覆盖「跨学生 / 跨家长 / 未登录」三条主路径，
 * 本类专攻其未覆盖的边角与「判定顺序」细节，用以证伪下列可能的漏洞：
 * 1. 家长会话缺少 parentId（脏会话）时是否仍被误放行；
 * 2. 学生会话缺少 studentId（脏会话）时是否会被 0L 相等判定误放行；
 * 3. 家长账号与学生档案完全没有归属关系（无档案）时，start 入口是否被 homework 的
 *    canOperate 家长分支恒真放行；
 * 4. 会话操作是否真的以「库内 studentId」为权威（而非调用方入参口径）；
 * 5. 多写路径（resume 的「清理重复暂停 + 收尾 + 刷汇总」）越权时是否逐项零写入。
 */
class TimerOwnershipFenceProbeTest {

    private companion object {
        const val NO_STUDENT_PARENT_ID = 7L
        const val OTHER_STUDENT_ID = 98L
    }

    // ---- 脏会话（缺角色维度）一律拒绝 ----

    @Test
    fun `家长会话缺 parentId 时入口全部拒绝且零写入`() = runTest {
        val env = TimerTestEnv()
        // 预置一条他人会话用于 pause/complete 入口（homeworkId 与下方 homework.id 不同，避免与断言混淆）
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID, homeworkId = 77L)
        val homework = env.seedHomework()
        env.authRepository.setSession(SessionState(role = Role.PARENT, parentId = null, studentId = null))
        val writesBefore = env.writeCounts()
        val transactionsBefore = env.transactionRunner.invocations

        val start = env.repository.startSession(homework.id, Role.PARENT)
        val pause = env.repository.pauseSession(foreignSessionId)
        val complete = env.repository.completeSession(foreignSessionId, Role.PARENT)

        assertTrue("缺 parentId 的家长会话不得开始计时", start is TimerStartResult.PermissionDenied)
        assertTrue("缺 parentId 的家长会话不得暂停", pause is TimerPauseResult.PermissionDenied)
        assertTrue("缺 parentId 的家长会话不得完成", complete is TimerCompleteResult.PermissionDenied)
        assertEquals("入口均不得写入", writesBefore, env.writeCounts())
        assertEquals("越权不得开启事务", transactionsBefore, env.transactionRunner.invocations)
        assertTrue(env.timerSessionDao.all().none { it.homeworkId == homework.id })
        assertEquals(HomeworkStatus.PENDING, env.homeworkRepository.getHomework(homework.id)?.status)
    }

    @Test
    fun `学生会话缺 studentId 时不得操作 studentId 为 0 的会话且零写入`() = runTest {
        val env = TimerTestEnv()
        // 脏数据：会话 studentId 为 0（默认值），若用 session.studentId == entity.studentId 直接比较会被 0L 绕过
        val zeroStudentSessionId = env.seedSession(studentId = 0L, status = TimerPhase.PAUSED)
        env.authRepository.setSession(SessionState(role = Role.STUDENT, parentId = 1L, studentId = null))
        val writesBefore = env.writeCounts()

        val pause = env.repository.pauseSession(zeroStudentSessionId)
        val resume = env.repository.resumeSession(zeroStudentSessionId)

        assertTrue("缺 studentId 的学生会话不得暂停", pause is TimerPauseResult.PermissionDenied)
        assertTrue("缺 studentId 的学生会话不得恢复", resume is TimerResumeResult.PermissionDenied)
        assertEquals(writesBefore, env.writeCounts())
        assertEquals(TimerPhase.PAUSED.name, env.timerSessionDao.findById(zeroStudentSessionId)!!.status)
    }

    // ---- 家长归属：无档案 / 跨账号 ----

    @Test
    fun `名下无任何学生档案的家长不能开始计时且零写入`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.authRepository.setSession(
            SessionState(role = Role.PARENT, parentId = NO_STUDENT_PARENT_ID, studentId = null),
        )
        val writesBefore = env.writeCounts()

        val result = env.repository.startSession(homework.id, Role.PARENT)

        assertTrue("家长账号与作业学生无归属关系时必须拒绝", result is TimerStartResult.PermissionDenied)
        assertEquals(writesBefore, env.writeCounts())
        assertTrue(env.timerSessionDao.all().isEmpty())
        assertEquals(HomeworkStatus.PENDING, env.homeworkRepository.getHomework(homework.id)?.status)
    }

    @Test
    fun `家长不能暂停归档在他人账号下学生的会话`() = runTest {
        val env = TimerTestEnv()
        // 另一学生档案存在但归属另一个家长账号：判定必须经 isStudentOwnedBy 而非「有档案即放行」
        env.authRepository.addStudentProfile(id = OTHER_STUDENT_ID, parentAccountId = 555L)
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID)
        env.useParentSession()
        val writesBefore = env.writeCounts()

        val result = env.repository.pauseSession(foreignSessionId)

        assertTrue(result is TimerPauseResult.PermissionDenied)
        assertEquals(writesBefore, env.writeCounts())
        assertTrue(env.pauseRecordDao.all().isEmpty())
    }

    // ---- 判定顺序：归属先于阶段 / 先于下游作业同步 ----

    @Test
    fun `越权的已完成会话完成操作返回权限失败而非阶段不合法`() = runTest {
        val env = TimerTestEnv()
        val finishedSessionId = env.seedSession(
            studentId = OTHER_STUDENT_ID,
            status = TimerPhase.FINISHED,
        )

        val result = env.repository.completeSession(finishedSessionId, Role.STUDENT)

        assertTrue("不得借阶段判定泄露他人会话状态", result is TimerCompleteResult.PermissionDenied)
        assertEquals(TimerPhase.FINISHED.name, env.timerSessionDao.findById(finishedSessionId)!!.status)
    }

    @Test
    fun `越权的已完成会话恢复操作返回权限失败而非阶段不合法`() = runTest {
        val env = TimerTestEnv()
        val finishedSessionId = env.seedSession(
            studentId = OTHER_STUDENT_ID,
            status = TimerPhase.FINISHED,
        )

        val result = env.repository.resumeSession(finishedSessionId)

        assertTrue(result is TimerResumeResult.PermissionDenied)
        assertEquals(TimerPhase.FINISHED.name, env.timerSessionDao.findById(finishedSessionId)!!.status)
    }

    @Test
    fun `越权完成不触达下游作业仓库`() = runTest {
        val env = TimerTestEnv()
        val fakeHomework = FakeHomeworkRepository().apply {
            put(
                timerTestHomework(
                    id = 21L,
                    studentId = OTHER_STUDENT_ID,
                    status = HomeworkStatus.IN_PROGRESS,
                ),
            )
        }
        val repository = env.repositoryWith(fakeHomework)
        val sessionId = env.seedSession(studentId = OTHER_STUDENT_ID, homeworkId = 21L)
        env.useParentSession()

        val result = repository.completeSession(sessionId, Role.PARENT)

        assertTrue(result is TimerCompleteResult.PermissionDenied)
        assertEquals("越权不得触达下游作业仓库", 0, fakeHomework.completeCalls)
        assertEquals(0, fakeHomework.startProgressCalls)
    }

    // ---- 权威归属：以库内 studentId 为准，不信任调用方 ----

    @Test
    fun `入参角色不能改变归属判定口径`() = runTest {
        val env = TimerTestEnv()
        val othersHomework = env.seedHomework(studentId = OTHER_STUDENT_ID)
        env.useParentSession()

        val result = env.repository.startSession(othersHomework.id, Role.STUDENT)

        assertTrue(result is TimerStartResult.PermissionDenied)
        assertTrue(env.timerSessionDao.all().isEmpty())
    }

    @Test
    fun `归属判定只认库内 studentId 而不看会话 parentAccountId`() = runTest {
        val env = TimerTestEnv()
        // 会话 parentAccountId 为无关值，但 studentId 属于当前家长名下：归属判定应放行
        val sessionId = env.seedSession(studentId = TimerTestEnv.STUDENT_ID)
        env.useParentSession()

        val result = env.repository.pauseSession(sessionId)

        assertTrue("归属口径只看学生的家长归属", result is TimerPauseResult.Success)
        assertEquals(TimerPhase.PAUSED.name, env.timerSessionDao.findById(sessionId)!!.status)
    }

    // ---- 多写路径的逐项零写入 ----

    @Test
    fun `越权恢复不清理重复暂停明细也不写汇总`() = runTest {
        val env = TimerTestEnv()
        val foreignSessionId = env.seedSession(studentId = OTHER_STUDENT_ID, status = TimerPhase.PAUSED)
        // 制造两条未结束暂停（历史中断脏数据）：若越权分支漏判，cleanupUnfinishedPauses 会删除其一
        env.seedOngoingPause(foreignSessionId)
        env.advance(1_000L)
        env.seedOngoingPause(foreignSessionId)
        env.advance(1_000L)
        val writesBefore = env.writeCounts()
        val transactionsBefore = env.transactionRunner.invocations

        val result = env.repository.resumeSession(foreignSessionId)

        assertTrue(result is TimerResumeResult.PermissionDenied)
        assertEquals("越权不得清理他人脏明细", 2, env.pauseRecordDao.all().size)
        assertTrue("越权不得收尾任何暂停明细", env.pauseRecordDao.all().all { it.pauseEndAt == null })
        assertEquals(writesBefore, env.writeCounts())
        assertEquals(transactionsBefore, env.transactionRunner.invocations)
        val stored = env.timerSessionDao.findById(foreignSessionId)!!
        assertEquals(TimerPhase.PAUSED.name, stored.status)
        assertEquals(0, stored.pauseCount)
    }

    // ---- 不存在的目标：不得误报权限失败 ----

    @Test
    fun `不存在的会话返回不存在而非权限失败且零写入`() = runTest {
        val env = TimerTestEnv()
        val writesBefore = env.writeCounts()

        val pause = env.repository.pauseSession(999L)
        val resume = env.repository.resumeSession(999L)
        val complete = env.repository.completeSession(999L, Role.STUDENT)

        assertTrue(pause is TimerPauseResult.SessionNotFound)
        assertTrue(resume is TimerResumeResult.SessionNotFound)
        assertTrue(complete is TimerCompleteResult.SessionNotFound)
        assertEquals(writesBefore, env.writeCounts())
    }

    @Test
    fun `不存在的作业开始计时返回作业不存在且零写入`() = runTest {
        val env = TimerTestEnv()
        val writesBefore = env.writeCounts()

        val result = env.repository.startSession(999L, Role.STUDENT)

        assertTrue(result is TimerStartResult.HomeworkNotFound)
        assertEquals(writesBefore, env.writeCounts())
    }

    // ---- 合法路径不被误伤（回归） ----

    @Test
    fun `学生本人会话全流程正常`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()

        val started = env.repository.startSession(homework.id, Role.STUDENT)
        assertTrue(started is TimerStartResult.Success)
        val sessionId = (started as TimerStartResult.Success).session.id

        env.advance(10_000L)
        val paused = env.repository.pauseSession(sessionId)
        assertTrue(paused is TimerPauseResult.Success)

        env.advance(5_000L)
        val resumed = env.repository.resumeSession(sessionId)
        assertTrue(resumed is TimerResumeResult.Success)
        assertEquals(5_000L, (resumed as TimerResumeResult.Success).session.pausedTotalMillis)

        env.advance(20_000L)
        val completed = env.repository.completeSession(sessionId, Role.STUDENT)
        assertTrue(completed is TimerCompleteResult.Success)
        assertEquals(HomeworkStatus.COMPLETED, env.homeworkRepository.getHomework(homework.id)?.status)
        assertEquals(TimerPhase.FINISHED.name, env.timerSessionDao.findById(sessionId)!!.status)
    }

    @Test
    fun `家长会话可完成名下学生暂停中的会话并收尾暂停明细`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        env.useParentSession()
        val started = (env.repository.startSession(homework.id, Role.PARENT) as TimerStartResult.Success).session
        env.repository.pauseSession(started.id)
        env.advance(15_000L)

        val completed = env.repository.completeSession(started.id, Role.PARENT)

        assertTrue(completed is TimerCompleteResult.Success)
        assertEquals(
            "暂停中直接完成应收尾未结束暂停",
            15_000L,
            (completed as TimerCompleteResult.Success).session.pausedTotalMillis,
        )
        assertNull("完成后不得残留未结束暂停", env.pauseRecordDao.all().firstOrNull { it.pauseEndAt == null })
    }

    // ---- 测试辅助 ----

    private suspend fun TimerTestEnv.seedSession(
        studentId: Long,
        homeworkId: Long = 1L,
        status: TimerPhase = TimerPhase.RUNNING,
    ): Long = timerSessionDao.insert(
        TimerSessionEntity(
            homeworkId = homeworkId,
            studentId = studentId,
            parentAccountId = TimerTestEnv.PARENT_ID,
            startedAt = Instant.ofEpochMilli(clock.currentTimeMillis()),
            finishedAt = null,
            pausedTotalMillis = 0L,
            pauseCount = 0,
            status = status.persistedName,
        ),
    )

    private suspend fun TimerTestEnv.seedOngoingPause(sessionId: Long) {
        pauseRecordDao.insert(
            PauseRecordEntity(
                sessionId = sessionId,
                homeworkId = 1L,
                pauseStartAt = Instant.ofEpochMilli(clock.currentTimeMillis()),
                pauseEndAt = null,
            ),
        )
    }

    private fun TimerTestEnv.writeCounts(): Pair<Int, Int> =
        timerSessionDao.writeCount to pauseRecordDao.writeCount

    private fun TimerTestEnv.repositoryWith(
        homeworkRepository: com.assignmate.app.homework.data.HomeworkRepository,
    ): TimerRepositoryImpl = TimerRepositoryImpl(
        timerSessionDao = timerSessionDao,
        pauseRecordDao = pauseRecordDao,
        homeworkRepository = homeworkRepository,
        authRepository = authRepository,
        clock = clock,
        transactionRunner = transactionRunner,
    )
}