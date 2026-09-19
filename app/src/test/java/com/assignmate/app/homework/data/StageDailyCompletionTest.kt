package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageDayRecords
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.homework.ui.HomeworkListRoleScope
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段作业「当天完成 ≠ 整条完成」修正的回归单测（需求 #6 error）：
 *
 * 1. **必修回归**：同一条阶段作业连续两天——第 1 天完成后**第 2 天可直接开始计时，无需撤销完成**；
 *    第 2 天完成后同样保持可继续（阶段范围内每一天都可开始）；
 * 2. 阶段范围内**全部天完成** → 整条 [HomeworkStatus.COMPLETED]；
 *    仍有未完成天且阶段已结束 → 不置已完成、归家长端「已结束」分组并标注未完成天数；
 * 3. **TODAY 不回归**：完成即整条完成、撤销完成回退为进行中；
 * 4. 「撤销完成」对阶段作业撤销的是**今天**的完成记录，今天可重新开始；
 * 5. 阶段窗口外（尚未开始/已结束）拒绝「完成今天」且不写入每天详情；
 * 6. 「已记录 → 待完成」不得凭空写入「已完成」的每天详情（否则会污染阶段进度与全部天完成判定）；
 * 7. 时区唯一来源：覆写注入的业务时区后，阶段起始日与归属日**同步**切换。
 *
 * 与 timer 的契约：完成后整条状态必须落在 timer 的可开始集合
 * （`STARTABLE_STATUSES = {PENDING, IN_PROGRESS}`）内——本类以「status ∈ {PENDING, IN_PROGRESS}」
 * 显式断言，避免测试与 timer 的常量形成编译期耦合（timer 侧适配由 timer 计划承接）。
 */
class StageDailyCompletionTest {

    // ---- 1. 连续两天：当天完成 ≠ 整条完成 ----

    @Test
    fun `阶段作业第一天完成后第二天可直接开始计时无需撤销完成`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val start = env.todayEpochDay()
        val id = env.addStage(studentId)
        env.scheduleOn(id, start)

        // 第 1 天：开始 → 完成今天的作业
        assertTrue(env.repository.startProgress(id, Role.PARENT) is HomeworkStatusResult.Success)
        val day1 = env.repository.complete(id, Role.PARENT) as HomeworkStatusResult.Success
        assertEquals("当天完成后整条不得置为已完成", HomeworkStatus.PENDING, day1.item.status)
        assertTrue(
            "整条状态必须落在 timer 的可开始集合内（PENDING / IN_PROGRESS）",
            day1.item.status == HomeworkStatus.PENDING || day1.item.status == HomeworkStatus.IN_PROGRESS,
        )
        assertEquals("今天的完成落到作业每天详情", HomeworkDayStatus.COMPLETED, env.dayStatus(id, start))
        assertEquals("阶段进度：已打卡 1/7 天", env.progressText(id, start))

        // 第 2 天：直接开始计时（修复前整条已 COMPLETED，必须先在清单页「撤销完成」才能开始）
        env.setEpochDay(start + 1L)
        assertEquals(HomeworkStatus.PENDING, env.status(id))
        val day2Start = env.repository.startProgress(id, Role.PARENT)
        assertEquals(
            "第二天必须可直接开始计时",
            HomeworkStatus.IN_PROGRESS,
            (day2Start as HomeworkStatusResult.Success).item.status,
        )

        // 第 2 天完成 → 仍保持可继续（第 3 天照常可开始）
        val day2 = env.repository.complete(id, Role.PARENT) as HomeworkStatusResult.Success
        assertEquals(HomeworkStatus.PENDING, day2.item.status)
        assertEquals(HomeworkDayStatus.COMPLETED, env.dayStatus(id, start + 1L))
        assertEquals("阶段进度：已打卡 2/7 天", env.progressText(id, start + 1L))

        env.setEpochDay(start + 2L)
        assertTrue(
            "阶段范围内每一天都可开始",
            env.repository.startProgress(id, Role.PARENT) is HomeworkStatusResult.Success,
        )
    }

    // ---- 2. 全部天完成才置整条已完成 ----

    @Test
    fun `阶段范围内全部天完成后整条置为已完成`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        // 阶段自 6 天前开始：覆盖区间 [start, start+6]，最后一天即「今天」
        val start = env.todayEpochDay() - 6L
        env.setEpochDay(start)
        val id = env.addStage(studentId)

        val statuses = (0L until 7L).map { offset ->
            env.scheduleOn(id, start + offset)
            env.repository.startProgress(id, Role.PARENT)
            (env.repository.complete(id, Role.PARENT) as HomeworkStatusResult.Success).item.status
        }

        assertEquals("前 6 天完成后整条仍未完成", List(6) { HomeworkStatus.PENDING }, statuses.take(6))
        assertEquals("最后一天完成后整条置为已完成", HomeworkStatus.COMPLETED, statuses.last())
        assertEquals("阶段进度：已打卡 7/7 天", env.progressText(id, start + 6L))

        // 重复完成幂等：整条已完成时不改写任何数据
        assertTrue(env.repository.complete(id, Role.PARENT) is HomeworkStatusResult.Success)
        assertEquals(HomeworkStatus.COMPLETED, env.status(id))

        // 家长视角：归入「已完成历史」（与阶段进度同一口径）
        val item = env.repository.getHomework(id)!!
        val records = env.repository.dailyRecords(id)
        val groups = HomeworkListRoleScope.group(listOf(item), { records }, start + 6L, env.zone)
        assertEquals("全部天完成 → 已完成历史", listOf(id), groups.completedHistory.map { it.id })
        assertTrue(groups.main.isEmpty())
        assertTrue(groups.ended.isEmpty())
    }

    @Test
    fun `阶段已结束仍有未完成天时不置已完成并归入已结束分组`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val start = env.todayEpochDay() - 8L
        env.setEpochDay(start)
        val id = env.addStage(studentId)

        // 首日完成，其余 6 天未完成
        env.scheduleOn(id, start)
        env.repository.startProgress(id, Role.PARENT)
        assertEquals(
            HomeworkStatus.PENDING,
            (env.repository.complete(id, Role.PARENT) as HomeworkStatusResult.Success).item.status,
        )

        // 今天已越过阶段末日（覆盖 [start, start+6]）
        env.setEpochDay(start + 8L)
        assertEquals(
            "阶段窗口外不得再「完成今天」",
            HomeworkStatusResult.IllegalTransition(HomeworkStatus.PENDING, HomeworkStatus.COMPLETED),
            env.repository.complete(id, Role.PARENT),
        )
        val item = env.repository.getHomework(id)!!
        assertEquals("仍有未完成天 → 整条不得置为已完成", HomeworkStatus.PENDING, item.status)

        val records = env.repository.dailyRecords(id)
        val groups = HomeworkListRoleScope.group(listOf(item), { records }, start + 8L, env.zone)
        assertTrue("不入已完成历史", groups.completedHistory.isEmpty())
        assertEquals("归入已结束分组", listOf(id), groups.ended.map { it.id })
        assertEquals("标注未完成天数（6 天）", 6, groups.endedMissedDayCounts[id])
        assertEquals(
            "仅首日已完成",
            1,
            StageDayRecords.progressOf(item, records, start + 8L, env.zone)!!.completedDays,
        )
    }

    // ---- 3. TODAY 不回归 ----

    @Test
    fun `当天作业完成即整条完成且撤销完成回退为进行中`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val today = env.todayEpochDay()
        val id = env.addToday(studentId)
        env.scheduleOn(id, today)

        val completed = env.repository.complete(id, Role.PARENT) as HomeworkStatusResult.Success
        assertEquals("当天作业完成即整条完成（语义不变）", HomeworkStatus.COMPLETED, completed.item.status)
        assertEquals(HomeworkDayStatus.COMPLETED, env.dayStatus(id, today))

        val reopened = env.repository.reopen(id, Role.PARENT) as HomeworkStatusResult.Success
        assertEquals(HomeworkStatus.IN_PROGRESS, reopened.item.status)
        assertEquals(HomeworkDayStatus.NOT_STARTED, env.dayStatus(id, today))
        assertNull(env.dailyRecordRepository.find(id, today)?.finishedAtMillis)
    }

    // ---- 4. 阶段作业的「撤销完成」= 撤销今天的完成 ----

    @Test
    fun `阶段作业撤销完成回退今天的记录并保持可继续`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val start = env.todayEpochDay()
        val id = env.addStage(studentId)
        env.scheduleOn(id, start)
        env.repository.startProgress(id, Role.PARENT)
        env.repository.complete(id, Role.PARENT)

        val reopened = env.repository.reopen(id, Role.PARENT) as HomeworkStatusResult.Success
        assertEquals("撤销今天的完成后整条回到待完成", HomeworkStatus.PENDING, reopened.item.status)
        assertEquals("今天回到未开始", HomeworkDayStatus.NOT_STARTED, env.dayStatus(id, start))
        assertEquals("阶段进度：已打卡 0/7 天", env.progressText(id, start))

        // 今天已无完成记录：重复撤销完成不是合法流转（不产生边界不明的数据）
        assertEquals(
            HomeworkStatusResult.IllegalTransition(HomeworkStatus.PENDING, HomeworkStatus.IN_PROGRESS),
            env.repository.reopen(id, Role.PARENT),
        )

        // 今天可重新开始并完成
        assertTrue(env.repository.startProgress(id, Role.PARENT) is HomeworkStatusResult.Success)
        assertEquals(
            HomeworkStatus.PENDING,
            (env.repository.complete(id, Role.PARENT) as HomeworkStatusResult.Success).item.status,
        )
        assertEquals(HomeworkDayStatus.COMPLETED, env.dayStatus(id, start))
    }

    // ---- 5. 阶段窗口外拒绝「完成今天」 ----

    @Test
    fun `阶段尚未开始时完成今天被拒且不写入每天详情`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val start = env.todayEpochDay() + 1L
        env.setEpochDay(start)
        val id = env.addStage(studentId)
        env.scheduleOn(id, start)

        // 回到阶段开始前一天：今天不属于该阶段
        env.setEpochDay(start - 1L)
        assertEquals(
            HomeworkStatusResult.IllegalTransition(HomeworkStatus.PENDING, HomeworkStatus.COMPLETED),
            env.repository.complete(id, Role.PARENT),
        )
        assertTrue("窗口外不得写入每天详情", env.repository.dailyRecords(id).isEmpty())
        assertEquals(HomeworkStatus.PENDING, env.status(id))
    }

    @Test
    fun `已记录阶段作业不可直接完成`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val id = env.addStage(studentId)

        assertEquals(
            HomeworkStatusResult.IllegalTransition(HomeworkStatus.RECORDED, HomeworkStatus.COMPLETED),
            env.repository.complete(id, Role.PARENT),
        )
        assertTrue(env.repository.dailyRecords(id).isEmpty())
    }

    // ---- 6. 「已记录 → 待完成」不写每天详情 ----

    @Test
    fun `已记录转待完成不写每天详情避免凭空完成`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val start = env.todayEpochDay()
        val id = env.addStage(studentId)

        assertTrue(env.repository.markPending(id, Role.PARENT) is HomeworkStatusResult.Success)

        assertEquals("排定确认只是状态推进，不得写入任何每天详情", 0, env.repository.dailyRecords(id).size)
        val progress = StageDayRecords.progressOf(
            env.repository.getHomework(id)!!,
            emptyList(),
            start,
            env.zone,
        )!!
        assertEquals("不得凭空记成已打卡", 0, progress.completedDays)
        assertEquals("阶段进度：已打卡 0/7 天", progress.progressText)

        // 当天作业同理：状态列表达「待完成」，不写每天详情
        val todayId = env.addToday(studentId)
        env.repository.markPending(todayId, Role.PARENT)
        assertTrue(env.repository.dailyRecords(todayId).isEmpty())
    }

    // ---- 7. 时区唯一来源：覆写后阶段起始日与归属日同步切换 ----

    @Test
    fun `覆写注入的业务时区后阶段起始日与归属日同步切换`() = runTest {
        val shanghai = HomeworkTestEnv(SHANGHAI)
        val utc = HomeworkTestEnv(UTC)
        val shanghaiToday = HomeworkValidators.epochDayOf(HomeworkTestEnv.FIXED_MILLIS, SHANGHAI)
        val utcToday = HomeworkValidators.epochDayOf(HomeworkTestEnv.FIXED_MILLIS, UTC)
        assertEquals("固定瞬时在 UTC+8 已跨日，UTC 口径仍是前一天", 1L, shanghaiToday - utcToday)

        for (env in listOf(shanghai, utc)) {
            val today = HomeworkValidators.epochDayOf(env.clock.currentTimeMillis(), env.zone)
            val parentId = env.loginAsParent()
            val studentId = env.addStudent(parentId)
            val id = env.addStage(studentId)
            val item = env.repository.getHomework(id)!!

            assertEquals("阶段起始日 = 注入时区口径的创建日", today, item.stageStartEpochDay)
            assertEquals(
                "每天详情折算与阶段起始日同源（同一注入时区）",
                today,
                env.dailyRecordRepository.epochDayOf(env.clock.currentTimeMillis()),
            )

            env.scheduleOn(id, today)
            env.repository.startProgress(id, Role.PARENT)
            assertTrue(env.repository.complete(id, Role.PARENT) is HomeworkStatusResult.Success)
            assertEquals(
                "当天完成落在注入时区的自然日上",
                HomeworkDayStatus.COMPLETED,
                env.dayStatus(id, today),
            )
            assertNull(
                "另一个自然日不得出现记录（归属日必须随唯一来源整体切换）",
                env.dailyRecordRepository.find(id, today - 1L),
            )
        }
    }

    // ---- 测试工具 ----

    private suspend fun HomeworkTestEnv.addStage(
        studentId: Long,
        range: StageRange = StageRange.ONE_WEEK,
    ): Long = (
        repository.addHomework(
            HomeworkTemplate(
                content = "每天读课文",
                type = HomeworkType.STAGE,
                stageRange = range,
                deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(DAILY_DEADLINE),
                creatorRole = CreatorRole.PARENT,
                startEpochDay = todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        ) as AddHomeworkResult.Success
        ).items.single().id

    private suspend fun HomeworkTestEnv.addToday(studentId: Long): Long = (
        repository.addHomework(
            HomeworkTemplate(
                content = "数学练习册",
                type = HomeworkType.TODAY,
                creatorRole = CreatorRole.PARENT,
                startEpochDay = todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        ) as AddHomeworkResult.Success
        ).items.single().id

    /** 把时钟拨到 [epochDay] 的 09:00 并为作业排定该天的时间段（阶段作业按每日截止时刻校验） */
    private suspend fun HomeworkTestEnv.scheduleOn(id: Long, epochDay: Long) {
        setEpochDay(epochDay, hour = 9, minute = 0)
        val result = repository.updateSchedule(
            id,
            Instant.ofEpochMilli(clock.currentTimeMillis()),
            SLOT_MINUTES,
            Role.PARENT,
        )
        assertTrue("排定时间应成功，实际：$result", result is ScheduleUpdateResult.Success)
    }

    private suspend fun HomeworkTestEnv.progressText(id: Long, todayEpochDay: Long): String? =
        StageDayRecords
            .progressOf(repository.getHomework(id)!!, repository.dailyRecords(id), todayEpochDay, zone)
            ?.progressText

    private suspend fun HomeworkTestEnv.status(id: Long): HomeworkStatus =
        repository.getHomework(id)!!.status

    private suspend fun HomeworkTestEnv.dayStatus(id: Long, epochDay: Long): HomeworkDayStatus? =
        dailyRecordRepository.find(id, epochDay)?.status

    private fun HomeworkTestEnv.todayEpochDay(): Long =
        HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zone)

    private companion object Constants {

        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        val UTC: ZoneId = ZoneId.of("UTC")

        /** 阶段作业的每日截止时刻（覆盖「排定 09:00 + 30 分钟」不越界） */
        val DAILY_DEADLINE: LocalTime = LocalTime.of(21, 0)

        const val SLOT_MINUTES = 30
    }
}
