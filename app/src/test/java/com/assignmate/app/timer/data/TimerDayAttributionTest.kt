package com.assignmate.app.timer.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.domain.TimerPhase
import com.assignmate.app.timer.domain.TimerSession
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计时/暂停按「作业 + 自然日」归属的仓库层单测（timer 模块新增口径）。
 *
 * 覆盖：
 * 1. 开始/暂停/恢复/完成四个动作都把当日执行数据写入 core 的「作业每天详情」
 *    （开始时刻、预估/实际时长、暂停次数/暂停总时长、完成时刻与状态）；
 * 2. 同一作业不同自然日各自独立计时与暂停归属——第二天开始计时**不覆盖**第一天数据；
 * 3. 跨天归属口径（**风后定死，须测试覆盖**）：开始于 23:50、结束于次日 00:10 的会话
 *    全部计入**开始那一天**，且只写一条计入执行数据的归属详情（第二天不得被记成完成）；
 * 4. v4 旧库遗留的 epoch_day = 0 会话按开始时刻补折算（自愈，不写垃圾详情）。
 *
 * 数据链路刻意使用**真实** homework 仓库 + 真实 core「每天详情」契约实现（内存 DAO），
 * 因此「状态流转写当天详情」与「计时写归属日详情」两条链路的相互作用也被覆盖。
 */
class TimerDayAttributionTest {

    @Test
    fun `开始计时把归属日与开始时刻写入当天详情`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(estimatedMinutes = 25, content = "数学练习册")

        val session = env.start(homework.id)

        assertEquals("会话归属日 = 开始时刻所在业务自然日", env.todayEpochDay(), session.epochDay)
        val record = env.dailyRecordDao.snapshot().single()
        assertEquals(homework.id, record.homeworkId)
        assertEquals(env.todayEpochDay(), record.epochDay)
        assertEquals(HomeworkDayStatus.IN_PROGRESS.name, record.status)
        assertEquals(TimerTestEnv.FIXED_MILLIS, record.startedAt?.toEpochMilli())
        assertEquals(25, record.estimatedMinutes)
        assertNull("当天尚未收尾时不写实际时长", record.actualMinutes)
        assertEquals(0, record.pauseCount)
        assertEquals(0, record.pausedTotalMinutes)
        assertNull(record.finishedAt)
    }

    @Test
    fun `暂停与恢复把当天详情的暂停次数与暂停总时长同步刷新`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(3 * MINUTE)
        env.pause(session.id)
        env.advance(4 * MINUTE)

        env.resume(session.id)

        val record = env.dailyRecordDao.snapshot().single()
        assertEquals(HomeworkDayStatus.IN_PROGRESS.name, record.status)
        assertEquals("暂停次数按归属日聚合", 1, record.pauseCount)
        assertEquals("暂停总时长按分钟向上取整", 4, record.pausedTotalMinutes)
        assertEquals(TimerTestEnv.FIXED_MILLIS, record.startedAt?.toEpochMilli())
        assertNull("未收尾前仍不写实际时长", record.actualMinutes)
    }

    @Test
    fun `完成时把当天详情置已完成并回写实际时长与完成时刻`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(estimatedMinutes = 30)
        val session = env.start(homework.id)
        env.advance(2 * MINUTE)
        env.pause(session.id)
        env.advance(1 * MINUTE)
        env.resume(session.id)
        env.advance(19 * MINUTE)

        val result = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.Success)
        val record = env.dailyRecordDao.snapshot().single()
        assertEquals(HomeworkDayStatus.COMPLETED.name, record.status)
        // 总跨度 22 分钟 - 暂停 1 分钟 = 21 分钟
        assertEquals(21, record.actualMinutes)
        assertEquals(1, record.pauseCount)
        assertEquals(1, record.pausedTotalMinutes)
        assertEquals(TimerTestEnv.FIXED_MILLIS + 22 * MINUTE, record.finishedAt?.toEpochMilli())
    }

    @Test
    fun `同一作业第二天开始计时不覆盖第一天的数据`() = runTest {
        val env = TimerTestEnv()
        val firstDay = env.todayEpochDay()
        // 阶段作业：一条作业项跨多天，是「同一作业不同自然日各自独立计时」的典型场景
        val homework = env.stageHomework(startEpochDay = firstDay, stageRange = StageRange.ONE_WEEK)

        // 第一天：完整走一轮（含一次暂停），最后完成
        val first = env.start(homework.id)
        env.advance(5 * MINUTE)
        env.pause(first.id)
        env.advance(2 * MINUTE)
        env.resume(first.id)
        env.advance(3 * MINUTE)
        env.repository.completeSession(first.id, Role.STUDENT)
        val firstDayRecord = env.dailyRecordDao.snapshot().single()
        assertEquals(HomeworkDayStatus.COMPLETED.name, firstDayRecord.status)
        assertEquals(8, firstDayRecord.actualMinutes)

        // 第二天：同一作业再次开始计时
        // （作业自身状态由 homework 负责：当天完成会置「已完成」，这里按应用的「撤销完成」路径回到可进行）
        env.advance(DAY)
        env.homeworkRepository.reopen(homework.id, Role.STUDENT)
        val secondDay = env.todayEpochDay()
        val second = env.start(homework.id)
        env.advance(4 * MINUTE)
        env.pause(second.id)
        env.advance(1 * MINUTE)
        env.resume(second.id)

        assertEquals("第二次会话归属到第二天", secondDay, second.epochDay)
        assertTrue("两天必须是不同的业务自然日", secondDay != firstDay)
        val records = env.dailyRecordDao.snapshot()
        assertEquals("同一作业两天各一条详情，互不覆盖", 2, records.size)
        val day1 = records.first { it.epochDay == firstDay }
        assertEquals("第一天仍是已完成且数据不变", HomeworkDayStatus.COMPLETED.name, day1.status)
        assertEquals(8, day1.actualMinutes)
        assertEquals(1, day1.pauseCount)
        assertEquals(2, day1.pausedTotalMinutes)
        val day2 = records.first { it.epochDay == secondDay }
        assertEquals(HomeworkDayStatus.IN_PROGRESS.name, day2.status)
        assertEquals(1, day2.pauseCount)
        assertEquals(1, day2.pausedTotalMinutes)
        assertNull("第二天尚未收尾", day2.actualMinutes)
        // 会话表与暂停明细表同样按天归属
        assertEquals(listOf(firstDay, secondDay), env.timerSessionDao.all().map { it.epochDay })
        assertEquals(listOf(firstDay, secondDay), env.pauseRecordDao.all().map { it.epochDay })
    }

    @Test
    fun `跨天会话全部计入开始那天且只写一条计入执行数据的详情`() = runTest {
        val env = TimerTestEnv()
        val startDay = env.todayEpochDay()
        val homework: HomeworkItem = env.seedHomework(estimatedMinutes = 30, content = "背古诗")
        // 开始于 23:50，暂停 23:55，恢复 00:00，完成于次日 00:10
        env.clock.set(millisAt(startDay, LocalTime.of(23, 50)))
        val session = env.start(homework.id)
        env.advance(5 * MINUTE)
        env.pause(session.id)
        env.advance(5 * MINUTE)
        env.resume(session.id)
        env.advance(10 * MINUTE)
        val nextDay = env.todayEpochDay()

        val result = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.Success)
        assertTrue("完成时刻已跨入第二天", nextDay != startDay)
        assertEquals("会话归属日 = 开始那天", startDay, (result as TimerCompleteResult.Success).session.epochDay)
        // 暂停明细同样归属开始那天（跨过午夜也不分裂到两天）
        val pause = env.pauseRecordDao.all().single()
        assertEquals(startDay, pause.epochDay)
        // 归属详情：开始那天为已完成，实际时长 = 20 分钟跨度 - 5 分钟暂停 = 15 分钟
        val startDayRecord = env.dailyRecordDao.snapshot().first { it.epochDay == startDay }
        assertEquals(HomeworkDayStatus.COMPLETED.name, startDayRecord.status)
        assertEquals(millisAt(startDay, LocalTime.of(23, 50)), startDayRecord.startedAt?.toEpochMilli())
        assertEquals(millisAt(nextDay, LocalTime.of(0, 10)), startDayRecord.finishedAt?.toEpochMilli())
        assertEquals(15, startDayRecord.actualMinutes)
        assertEquals(1, startDayRecord.pauseCount)
        assertEquals(5, startDayRecord.pausedTotalMinutes)
        // 只有开始那一天带执行数据（第二天由作业状态流转写下的「已完成」已被回退为未开始）
        val withExecution = env.dailyRecordDao.snapshot().filter { it.startedAt != null || it.finishedAt != null }
        assertEquals("跨天会话只在开始那天留下执行数据", listOf(startDay), withExecution.map { it.epochDay })
        val nextDayRecord = env.dailyRecordDao.snapshot().first { it.epochDay == nextDay }
        assertEquals("第二天不得被这条跨天会话记成完成", HomeworkDayStatus.NOT_STARTED.name, nextDayRecord.status)
        assertNull(nextDayRecord.finishedAt)
        assertNull(nextDayRecord.actualMinutes)
    }

    @Test
    fun `旧库未落归属日的会话按开始时刻补折算并写入当天详情`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val startedAt = TimerTestEnv.FIXED_MILLIS
        // 模拟 v4 旧库迁移行：epoch_day 为列默认值 0
        val id = env.timerSessionDao.insert(
            TimerSessionEntity(
                homeworkId = homework.id,
                studentId = TimerTestEnv.STUDENT_ID,
                parentAccountId = TimerTestEnv.PARENT_ID,
                // 实体已移除 epochDay 的 Kotlin 默认值：旧库迁移行显式写「未折算」哨兵 0
                epochDay = UNSPECIFIED_EPOCH_DAY,
                startedAt = Instant.ofEpochMilli(startedAt),
                status = TimerPhase.RUNNING.persistedName,
            ),
        )
        env.advance(2 * MINUTE)

        val paused = env.repository.pauseSession(id)

        assertTrue(paused is TimerPauseResult.Success)
        val attributedDay = env.dailyRecordRepository.epochDayOf(startedAt)
        assertEquals("暂停明细按会话开始时刻补折算的归属日写入", attributedDay, env.pauseRecordDao.all().single().epochDay)
        assertEquals(attributedDay, (paused as TimerPauseResult.Success).session.epochDay)
        val record = env.dailyRecordDao.snapshot().single()
        assertEquals(attributedDay, record.epochDay)
        assertTrue("不得写出 epoch_day = 0 的垃圾详情", record.epochDay != 0L)
    }

    @Test
    fun `同一天多次开始计时按归属日累加而非互相覆盖`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework(estimatedMinutes = 20)
        val first = env.start(homework.id)
        env.advance(10 * MINUTE)
        env.pause(first.id)
        env.advance(2 * MINUTE)
        env.repository.completeSession(first.id, Role.STUDENT)

        // 同一天再次开始（例如再练一遍）：当天详情应累加两段
        // （作业状态由 homework 负责：当天完成会置「已完成」，这里按应用的「撤销完成」路径回到可进行）
        env.homeworkRepository.reopen(homework.id, Role.STUDENT)
        val second = env.start(homework.id)
        env.advance(6 * MINUTE)
        env.repository.completeSession(second.id, Role.STUDENT)

        val record = env.dailyRecordDao.snapshot().single()
        assertEquals(HomeworkDayStatus.COMPLETED.name, record.status)
        assertEquals("两段实际时长累加（第一段 10 分钟 + 第二段 6 分钟）", 16, record.actualMinutes)
        assertEquals("暂停次数累加", 1, record.pauseCount)
        assertEquals(2, record.pausedTotalMinutes)
        assertEquals("开始时刻取当天最早一次", TimerTestEnv.FIXED_MILLIS, record.startedAt?.toEpochMilli())
    }

    @Test
    fun `暂停中的会话完成时当天详情直接收口为已完成`() = runTest {
        val env = TimerTestEnv()
        val homework = env.seedHomework()
        val session = env.start(homework.id)
        env.advance(4 * MINUTE)
        env.pause(session.id)
        env.advance(3 * MINUTE)

        val result = env.repository.completeSession(session.id, Role.STUDENT)

        assertTrue(result is TimerCompleteResult.Success)
        val record = env.dailyRecordDao.snapshot().single()
        assertEquals(HomeworkDayStatus.COMPLETED.name, record.status)
        assertEquals(4, record.actualMinutes)
        assertEquals(1, record.pauseCount)
        assertEquals(3, record.pausedTotalMinutes)
        assertNotNull(record.finishedAt)
    }

    // ---- 测试辅助 ----

    private suspend fun TimerTestEnv.start(homeworkId: Long): TimerSession =
        (repository.startSession(homeworkId, Role.STUDENT) as TimerStartResult.Success).session

    private suspend fun TimerTestEnv.pause(sessionId: Long): TimerSession =
        (repository.pauseSession(sessionId) as TimerPauseResult.Success).session

    private suspend fun TimerTestEnv.resume(sessionId: Long): TimerSession =
        (repository.resumeSession(sessionId) as TimerResumeResult.Success).session

    /** 某业务自然日某钟面的绝对毫秒（业务时区口径，复用 project 内已有的唯一折算入口） */
    private fun millisAt(epochDay: Long, time: LocalTime): Long =
        HomeworkDailyDeadlineCodec.instantAt(epochDay, time, ZONE).toEpochMilli()

    private companion object {

        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE

        /** `epoch_day` 的「未折算」哨兵（列默认值 0）：仅用于构造 v4 旧库迁移行 */
        const val UNSPECIFIED_EPOCH_DAY = 0L

        val ZONE: ZoneId = TimerTestEnv.ZONE
    }
}
