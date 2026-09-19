package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkDayState
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageDayRecords
import com.assignmate.app.homework.domain.StageRange
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「开始 / 完成 / 撤销完成 → core 作业每天详情」链路与阶段进度推导单测。
 *
 * 覆盖需求第三节：
 * - 完成、开始、预估、实际时长、暂停汇总均落到每天详情；
 * - 同一（作业 + 自然日）只一条记录（upsert 原地更新且保留原创建时刻）；
 * - 阶段清单行的「今日状态」与「阶段进度（已打卡 N/M 天）」由每天详情推导；
 * - 缺卡不可补做：已过去的未完成天即便再次完成也不会被记为已完成。
 */
class HomeworkDailyProgressTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun HomeworkTestEnv.todayEpochDay(): Long =
        Instant.ofEpochMilli(clock.currentTimeMillis()).atZone(zone).toLocalDate().toEpochDay()

    private suspend fun HomeworkTestEnv.addToday(
        studentId: Long,
        content: String = "数学练习册",
        deadline: Instant? = null,
    ): Long = (
        repository.addHomework(
            HomeworkTemplate(
                content = content,
                type = HomeworkType.TODAY,
                deadline = deadline,
                creatorRole = CreatorRole.PARENT,
                startEpochDay = todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        ) as AddHomeworkResult.Success
        ).items.single().id

    private suspend fun HomeworkTestEnv.addStage(
        studentId: Long,
        range: StageRange = StageRange.ONE_WEEK,
        deadlineTime: LocalTime = LocalTime.of(21, 0),
    ): Long = (
        repository.addHomework(
            HomeworkTemplate(
                content = "每天读课文",
                type = HomeworkType.STAGE,
                stageRange = range,
                deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(deadlineTime),
                creatorRole = CreatorRole.PARENT,
                startEpochDay = todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        ) as AddHomeworkResult.Success
        ).items.single().id

    // ---- 当天作业：开始 / 完成 / 撤销完成 ----

    @Test
    fun `开始作业把当天详情置为进行中并记录开始时刻与预估`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val id = env.addToday(studentId, deadline = Instant.ofEpochMilli(env.clock.currentTimeMillis() + 7_200_000L))
        env.repository.updateSchedule(id, Instant.ofEpochMilli(env.clock.currentTimeMillis()), 30, Role.PARENT)

        assertTrue(env.repository.startProgress(id, Role.PARENT) is HomeworkStatusResult.Success)

        val record = env.dailyRecordRepository.find(id, env.todayEpochDay())!!
        assertEquals(HomeworkDayStatus.IN_PROGRESS, record.status)
        assertEquals(env.clock.currentTimeMillis(), record.startedAtMillis)
        assertEquals(30, record.estimatedMinutes)
        assertNull(record.finishedAtMillis)
    }

    @Test
    fun `完成作业把当天详情置为已完成并记录完成时刻`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val id = env.addToday(studentId, deadline = Instant.ofEpochMilli(env.clock.currentTimeMillis() + 7_200_000L))
        env.repository.updateSchedule(id, Instant.ofEpochMilli(env.clock.currentTimeMillis()), 30, Role.PARENT)
        env.repository.startProgress(id, Role.PARENT)
        env.advance(60_000)

        assertTrue(env.repository.complete(id, Role.PARENT) is HomeworkStatusResult.Success)

        val record = env.dailyRecordRepository.find(id, env.todayEpochDay())!!
        assertEquals(HomeworkDayStatus.COMPLETED, record.status)
        assertEquals(env.clock.currentTimeMillis(), record.finishedAtMillis)
        assertEquals(30, record.estimatedMinutes)
        assertTrue("今日状态由每天详情推导为已完成", record.status == HomeworkDayStatus.COMPLETED)
    }

    @Test
    fun `撤销完成把当天详情回退为未开始并清空完成时刻`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val id = env.addToday(studentId, deadline = Instant.ofEpochMilli(env.clock.currentTimeMillis() + 7_200_000L))
        env.repository.updateSchedule(id, Instant.ofEpochMilli(env.clock.currentTimeMillis()), 30, Role.PARENT)
        env.repository.complete(id, Role.PARENT)

        assertTrue(env.repository.reopen(id, Role.PARENT) is HomeworkStatusResult.Success)

        val record = env.dailyRecordRepository.find(id, env.todayEpochDay())!!
        assertEquals(HomeworkDayStatus.NOT_STARTED, record.status)
        assertNull(record.finishedAtMillis)
    }

    @Test
    fun `撤销排定把已完成的当天详情回退`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val id = env.addToday(studentId, deadline = Instant.ofEpochMilli(env.clock.currentTimeMillis() + 7_200_000L))
        env.repository.updateSchedule(id, Instant.ofEpochMilli(env.clock.currentTimeMillis()), 30, Role.PARENT)
        env.repository.complete(id, Role.PARENT)

        assertTrue(env.repository.clearSchedule(id, Role.PARENT) is HomeworkOperationResult.Success)

        assertEquals(
            HomeworkDayStatus.NOT_STARTED,
            env.dailyRecordRepository.find(id, env.todayEpochDay())?.status,
        )
    }

    @Test
    fun `同一作业同一天只保留一条详情且保留原创建时刻`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val id = env.addToday(studentId, deadline = Instant.ofEpochMilli(env.clock.currentTimeMillis() + 7_200_000L))
        env.repository.updateSchedule(id, Instant.ofEpochMilli(env.clock.currentTimeMillis()), 30, Role.PARENT)

        val created = env.clock.currentTimeMillis()
        env.repository.startProgress(id, Role.PARENT)
        env.advance(30_000)
        env.repository.complete(id, Role.PARENT)
        env.advance(30_000)
        env.repository.reopen(id, Role.PARENT)

        val records = env.dailyRecordRepository.loadByHomework(id)
        assertEquals("同一（作业 + 自然日）至多一条", 1, records.size)
        assertEquals(created, records.single().createdAtMillis)
        assertEquals(HomeworkDayStatus.NOT_STARTED, records.single().status)
    }

    @Test
    fun `未注入每天详情仓库时清单口径不变`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val id = env.addToday(studentId)

        // core 契约的空实现（Noop）为仓库构造参数的默认值：读取为空表、写入空转，且
        // epochDayOf 按构造注入的业务时区折算（不再写死 systemDefault）
        val noop = NoopDailyRecordRepository(zone)
        assertTrue(noop.loadByHomework(id).isEmpty())
        assertNull(noop.find(id, env.todayEpochDay()))
        assertEquals(0L, noop.upsertStatus(id, studentId, env.todayEpochDay(), HomeworkDayStatus.COMPLETED, 0L))
        assertEquals(
            "空实现的自然日折算应与业务时区同口径",
            env.todayEpochDay(),
            noop.epochDayOf(env.clock.currentTimeMillis()),
        )
        assertTrue(env.repository.markPending(id, Role.PARENT) is HomeworkStatusResult.Success)
    }

    // ---- 阶段作业：进度由每天详情推导 ----

    @Test
    fun `阶段进度按每天详情统计已打卡天数`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        // 阶段自 2 天前开始：先拨时钟到那天录入，再回到「今天」——前两天真正成为「已过去」
        val start = env.todayEpochDay() - 2L
        env.setEpochDay(start)
        val id = env.addStage(studentId)
        val item = env.repository.getHomework(id)!!
        assertEquals(start, item.stageStartEpochDay)

        // 前两天已完成（缺记录的日子不会凭空变成已完成）
        env.dailyRecordRepository.upsertStatus(
            homeworkId = id,
            studentId = studentId,
            epochDay = start,
            status = HomeworkDayStatus.COMPLETED,
            nowMillis = env.clock.currentTimeMillis(),
        )
        env.dailyRecordRepository.upsertStatus(
            homeworkId = id,
            studentId = studentId,
            epochDay = start + 1L,
            status = HomeworkDayStatus.COMPLETED,
            nowMillis = env.clock.currentTimeMillis(),
        )

        // 回到今天：前两天已完成、今天尚未完成
        env.setEpochDay(start + 2L)
        val today = env.todayEpochDay()
        val records = env.repository.dailyRecords(id)

        val progress = StageDayRecords.progressOf(item, records, today, env.zone)!!
        assertEquals(StageRange.ONE_WEEK.days, progress.coveredDays)
        assertEquals(2, progress.completedDays)
        assertEquals("前两天均已完成，无缺卡", 0, progress.missedDays)
        assertEquals("阶段进度：已打卡 2/7 天", progress.progressText)
        assertEquals(
            "今日尚无完成记录 → 今日状态为待完成（仍可完成）",
            HomeworkDayState.PENDING,
            StageDayRecords.todayOutcome(item, records, today, env.zone)?.state,
        )
        assertTrue(StageDayRecords.isTodayActionable(item, records, today, env.zone))
    }

    @Test
    fun `阶段全部天完成后归入已完成`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        // 阶段自 6 天前开始：先把时钟拨到那天再录入，覆盖区间即 [start, start+6]（最后一天为「今天」）
        val start = env.todayEpochDay() - 6L
        env.setEpochDay(start)
        val id = env.addStage(studentId)
        val item = env.repository.getHomework(id)!!
        assertEquals(start, item.stageStartEpochDay)

        // 回到「今天」并补齐覆盖区间内每一天的完成记录
        env.setEpochDay(start + 6L)
        val today = env.todayEpochDay()
        (0L until 7L).forEach { offset ->
            env.dailyRecordRepository.upsertStatus(
                homeworkId = id,
                studentId = studentId,
                epochDay = start + offset,
                status = HomeworkDayStatus.COMPLETED,
                nowMillis = env.clock.currentTimeMillis(),
            )
        }

        val progress = StageDayRecords.progressOf(item, env.repository.dailyRecords(id), today, env.zone)!!
        assertTrue(progress.isAllCompleted)
        assertEquals("阶段进度：已打卡 7/7 天", progress.progressText)
        assertFalse(progress.isEndedWithMissedDays)
    }

    @Test
    fun `阶段整体结束后未完成天计入缺卡天数`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val id = env.addStage(studentId)
        val item = env.repository.getHomework(id)!!
        val start = item.stageStartEpochDay!!

        // 阶段首日已完成，其余 6 天未完成
        env.dailyRecordRepository.upsertStatus(
            homeworkId = id,
            studentId = studentId,
            epochDay = start,
            status = HomeworkDayStatus.COMPLETED,
            nowMillis = env.clock.currentTimeMillis(),
        )

        // 把「今天」推到阶段结束之后（覆盖区间 [start, start+6] 已全部过去）
        env.setEpochDay(start + 8L)
        val future = env.todayEpochDay()
        val progress = StageDayRecords.progressOf(item, env.repository.dailyRecords(id), future, env.zone)!!

        assertTrue("阶段已整体结束", progress.isEnded)
        assertEquals(1, progress.completedDays)
        assertEquals(6, progress.missedDays)
        assertTrue(progress.isEndedWithMissedDays)
    }

    @Test
    fun `阶段开始前学生不可见且今天不可完成`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        // 阶段自「明天」开始：创建时刻在明天
        env.setEpochDay(env.todayEpochDay() + 1L)
        val id = env.addStage(studentId)
        val item = env.repository.getHomework(id)!!
        val start = item.stageStartEpochDay!!

        // 回到前一天：阶段尚未开始
        env.setEpochDay(start - 1L)
        val today = env.todayEpochDay()
        val records = env.repository.dailyRecords(id)

        assertFalse("阶段开始前不对学生展示", StageDayRecords.isVisibleOnDay(item, today, env.zone))
        assertFalse(
            "阶段开始前今天不可完成",
            StageDayRecords.isTodayActionable(item, records, today, env.zone),
        )
        assertEquals(
            HomeworkDayState.NOT_ARRIVED,
            StageDayRecords.todayOutcome(item, records, today, env.zone)?.state,
        )
    }

    @Test
    fun `跨日后前一天判为未完成且今天仍可完成`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val id = env.addStage(studentId)
        val item = env.repository.getHomework(id)!!
        val start = item.stageStartEpochDay!!

        // 跨入次日：昨天（阶段首日）缺卡不可补做，今天仍可完成
        env.setEpochDay(start + 1L)
        val today = env.todayEpochDay()
        val records = env.repository.dailyRecords(id)
        val progress = StageDayRecords.progressOf(item, records, today, env.zone)!!

        assertEquals(1, progress.missedDays)
        assertEquals(0, progress.completedDays)
        assertFalse(
            "已过去的首日不可补做",
            StageDayRecords.stateOf(
                epochDay = start,
                startEpochDay = start,
                coveredDays = item.stageCoveredDays,
                todayEpochDay = today,
                isCompleted = false,
            ).isActionable,
        )
        assertTrue("当天仍可完成", StageDayRecords.isTodayActionable(item, records, today, env.zone))
        // 状态列不受「缺卡」影响：阶段作业录入即「已记录」，缺卡只体现在每天详情的推导里
        assertEquals(HomeworkStatus.RECORDED, item.status)
    }
}
