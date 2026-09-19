package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.data.db.dao.TimerSessionDao
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「作业每天详情回写」的健壮性收口（皐陶审查 #8-1）。
 *
 * 背景：`syncDayRecord` 原先用 `daySessions.minOf { ... }` 取当天最早开始时刻——
 * 正常路径下当天会话列表必然非空（自身会话会兜底补入），但 DAO 异常/数据被并发清理导致
 * **列表为空**时，`minOf` 会抛 `NoSuchElementException`，把一次详情回写变成崩溃。
 * 本类用「插入不落行」的会话 DAO 替身稳定复现该空列表场景，锁定「不抛异常 + 写入安全兜底值」。
 */
class TimerDayRecordSyncRobustnessTest {

    @Test
    fun `当天会话列表为空时不抛异常并以当前会话开始时刻兜底`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        // 只覆盖写入路径：insert 返回 id 但不落行 → loadByHomeworkAndDay 与 findById 都查不到该会话
        val repository = TimerRepositoryImpl(
            timerSessionDao = DroppingSessionDao(),
            pauseRecordDao = env.pauseRecordDao,
            homeworkRepository = env.homeworkRepository,
            authRepository = env.authRepository,
            clock = env.clock,
            transactionRunner = env.transactionRunner,
            dailyRecordRepository = env.dailyRecordRepository,
        )

        val result = repository.startSession(homework.id, Role.STUDENT)

        assertTrue("开始计时不得因详情回写而失败，实际：$result", result is TimerStartResult.Success)
        val epochDay = env.dailyRecordRepository.epochDayOf(TimerTestEnv.FIXED_MILLIS)
        val record = env.dailyRecordDao.snapshot()
            .single { it.homeworkId == homework.id && it.epochDay == epochDay }
        assertEquals(
            "空列表兜底为「当前会话的开始时刻」：既不抛异常，也不写 null",
            TimerTestEnv.FIXED_MILLIS,
            record.startedAt?.toEpochMilli(),
        )
    }

    @Test
    fun `当天有多个会话时开始时刻仍取最早的一次`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val epochDay = env.todayEpochDay()
        // 前置：当天已有一条更早的会话（归属同一天），随后再开始一次
        env.timerSessionDao.insert(
            TimerSessionEntity(
                homeworkId = homework.id,
                studentId = TimerTestEnv.STUDENT_ID,
                parentAccountId = TimerTestEnv.PARENT_ID,
                epochDay = epochDay,
                startedAt = Instant.ofEpochMilli(TimerTestEnv.FIXED_MILLIS - 30 * MINUTE),
                status = "RUNNING",
            ),
        )

        val result = env.repository.startSession(homework.id, Role.STUDENT)

        assertTrue("用例前提：开始计时应成功", result is TimerStartResult.Success)
        val session = (result as TimerStartResult.Success).session
        assertEquals(
            "用例前提：复用当天更早的那条会话（幂等语义）",
            TimerTestEnv.FIXED_MILLIS - 30 * MINUTE,
            session.startedAt.toEpochMilli(),
        )

        // 触发一次当天详情的聚合回写（幂等复用不会写详情，故经暂停动作驱动刷新）
        val paused = env.repository.pauseSession(session.id)
        assertTrue("用例前提：暂停应成功", paused is TimerPauseResult.Success)

        val record = env.dailyRecordDao.snapshot()
            .single { it.homeworkId == homework.id && it.epochDay == epochDay }
        assertEquals(
            "同一天多次开始时，详情的开始时刻取当天最早一次（回归口径不变）",
            TimerTestEnv.FIXED_MILLIS - 30 * MINUTE,
            record.startedAt?.toEpochMilli(),
        )
    }

    /**
     * 只返回 id、不落行的会话 DAO 替身：模拟「详情回写时查不到任何当天会话」，
     * 使「当天最早开始时刻」走到空列表分支（修复前该分支会抛 `NoSuchElementException`）。
     */
    private class DroppingSessionDao : TimerSessionDao {

        override suspend fun insert(item: TimerSessionEntity): Long = 1L

        override suspend fun insertAll(items: List<TimerSessionEntity>): List<Long> =
            items.map { insert(it) }

        override suspend fun update(item: TimerSessionEntity) = Unit

        override suspend fun delete(item: TimerSessionEntity) = Unit

        override fun observeByHomework(homeworkId: Long): Flow<List<TimerSessionEntity>> =
            flowOf(emptyList())

        override suspend fun loadByHomework(homeworkId: Long): List<TimerSessionEntity> = emptyList()

        override suspend fun loadByStudent(studentId: Long): List<TimerSessionEntity> = emptyList()

        override suspend fun loadByStudentAndStatus(
            studentId: Long,
            status: String,
        ): List<TimerSessionEntity> = emptyList()

        override suspend fun loadByHomeworkAndDay(
            homeworkId: Long,
            epochDay: Long,
        ): List<TimerSessionEntity> = emptyList()

        override suspend fun findById(id: Long): TimerSessionEntity? = null

        override suspend fun updateFinish(sessionId: Long, finishedAtMillis: Long, status: String) = Unit

        override suspend fun updatePauseSummary(
            sessionId: Long,
            pausedTotalMillis: Long,
            pauseCount: Int,
            status: String,
        ) = Unit

        override suspend fun updateStatus(sessionId: Long, status: String) = Unit
    }

    private companion object {

        const val MINUTE = 60_000L
    }
}
