package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageDayRecords
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.homework.ui.HomeworkListRoleScope
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「覆写 core 唯一业务时区后，homework 全链路同步切换」跨层单测（对照风后开发计划第一条）。
 *
 * 定位：`StageDayRecords` 的 zoneId 去掉默认值后，调用点只能显式传注入时区；本类用**真实仓库落库**的数据
 * （阶段起始日经 deadline 编码承载）证明「同一份数据 + 两套业务时区」下，阶段起止日、归属日、学生可见性、
 * 阶段进度覆盖区间与每日截止时刻的绝对折算**同步平移**，且跨零点（UTC 仍是当日、上海已跨入次日）不出现
 * 「一半口径走系统时区」的静默回退。
 */
class HomeworkStageZoneOverrideIntegrationTest {

    @Test
    fun `跨零点下两套业务时区各自把阶段起始日与归属日落到自己的自然日`() = runTest {
        val (shanghai, shStudent) = zoneEnv(SHANGHAI)
        val (utc, utcStudent) = zoneEnv(UTC)
        // 前提：同一固定瞬时在上海已跨日、UTC 仍是前一天
        assertEquals("测试前提：两套时区相差一个自然日", 1L, shanghai.todayEpochDay() - utc.todayEpochDay())

        val shItem = shanghai.stageHomework(shStudent)
        val utcItem = utc.stageHomework(utcStudent)

        assertEquals("阶段起始日 = 注入时区口径的创建日", shanghai.todayEpochDay(), shItem.stageStartEpochDay)
        assertEquals("阶段起始日 = 注入时区口径的创建日", utc.todayEpochDay(), utcItem.stageStartEpochDay)
        assertEquals("同一条作业在两套时区下落库的起始日整体相差一天", 1L, shItem.stageStartEpochDay!! - utcItem.stageStartEpochDay!!)
        assertEquals("归属日与阶段起始日同源（上海）", shItem.stageStartEpochDay, shItem.createdEpochDay(SHANGHAI))
        assertEquals("归属日与阶段起始日同源（UTC）", utcItem.stageStartEpochDay, utcItem.createdEpochDay(UTC))

        // 钟面值不随时区变化（编码只承载「起始日 + 每日时刻」）
        assertEquals(LocalTime.of(21, 0), shItem.dailyDeadlineTime)
        assertEquals(LocalTime.of(21, 0), utcItem.dailyDeadlineTime)
    }

    @Test
    fun `同一份落库数据在两套业务时区下的可见性进度与每日折算同步平移`() = runTest {
        val (env, studentId) = zoneEnv(SHANGHAI)
        val item = env.stageHomework(studentId)
        val shanghaiToday = env.todayEpochDay()
        val utcToday = HomeworkValidators.epochDayOf(env.clock.currentTimeMillis(), UTC)
        assertEquals(1L, shanghaiToday - utcToday)

        // 今天（上海口径）完成一次打卡：阶段「当天完成 ≠ 整条完成」，整条仍为待完成（次日可直接开始计时）
        env.completeToday(item.id)
        val records = env.repository.dailyRecords(item.id)
        assertEquals("当天详情落在注入时区的自然日上", shanghaiToday, records.single().epochDay)
        assertEquals(HomeworkStatus.PENDING, env.repository.getHomework(item.id)!!.status)

        // 可见性：上海口径今天正是阶段首日（可见）；UTC 口径的「今天」还早一天（阶段尚未开始）
        assertTrue(StageDayRecords.isVisibleOnDay(item, shanghaiToday, SHANGHAI))
        assertFalse("UTC 口径：阶段自次日起 → 同一条数据的自然日尚未进入覆盖区间", StageDayRecords.isVisibleOnDay(item, utcToday, UTC))

        // 阶段进度：覆盖区间固定由编码还原，但「已过去 / 剩余」随注入时区整体平移一天
        val inShanghai = StageDayRecords.progressOf(item, records, shanghaiToday, SHANGHAI)!!
        val inUtc = StageDayRecords.progressOf(item, records, utcToday, UTC)!!
        assertEquals(StageRange.ONE_WEEK.days, inShanghai.coveredDays)
        assertEquals(1, inShanghai.elapsedDays)
        assertEquals(StageRange.ONE_WEEK.days - 1, inShanghai.remainingDays)
        assertEquals(0, inUtc.elapsedDays)
        assertEquals(StageRange.ONE_WEEK.days, inUtc.remainingDays)
        assertEquals("覆盖区间内的完成记录不因时区被丢弃", 1, inUtc.completedDays)
        assertEquals("尚未到来的天不算缺卡", 0, inUtc.missedDays)

        // 每日截止时刻的绝对折算：同一钟面值在上海（UTC+8）比 UTC 口径早 8 小时达到
        val inShanghaiMillis = StageDayRecords.dailyDeadlineOf(item, shanghaiToday, SHANGHAI)!!.toEpochMilli()
        val inUtcMillis = StageDayRecords.dailyDeadlineOf(item, shanghaiToday, UTC)!!.toEpochMilli()
        assertEquals(8 * 60 * 60 * 1000L, inUtcMillis - inShanghaiMillis)
        assertEquals(
            "绝对折算与编码器同源",
            HomeworkDailyDeadlineCodec.instantAt(shanghaiToday, LocalTime.of(21, 0), SHANGHAI).toEpochMilli(),
            inShanghaiMillis,
        )
    }

    @Test
    fun `学生当天清单口径随注入业务时区同步切换`() = runTest {
        val (env, studentId) = zoneEnv(SHANGHAI)
        val item = env.stageHomework(studentId)
        val shanghaiToday = env.todayEpochDay()
        val utcToday = HomeworkValidators.epochDayOf(env.clock.currentTimeMillis(), UTC)

        assertEquals(
            "上海口径：今天落在阶段覆盖范围内 → 学生可见",
            listOf(item.id),
            HomeworkListRoleScope.visibleForDay(listOf(item), Role.STUDENT, shanghaiToday, SHANGHAI).map { it.id },
        )
        assertTrue(
            "UTC 口径：同一条作业的阶段从次日才开始 → 学生当天清单为空",
            HomeworkListRoleScope.visibleForDay(listOf(item), Role.STUDENT, utcToday, UTC).isEmpty(),
        )
    }

    // ---- 测试工具 ----

    /** 建一套按 [zone] 注入业务时区的环境，并返回（环境, 学生 id） */
    private suspend fun zoneEnv(zone: ZoneId): Pair<HomeworkTestEnv, Long> {
        val env = HomeworkTestEnv(zone)
        val parentId = env.loginAsParent()
        return env to env.addStudent(parentId)
    }

    /** 经仓库落库一条阶段作业（带每日 21:00 截止时刻），返回读回的领域模型 */
    private suspend fun HomeworkTestEnv.stageHomework(studentId: Long): HomeworkItem =
        repository.getHomework(addStage(studentId))!!

    private suspend fun HomeworkTestEnv.addStage(studentId: Long): Long = (
        repository.addHomework(
            HomeworkTemplate(
                content = "每天读课文",
                type = HomeworkType.STAGE,
                stageRange = StageRange.ONE_WEEK,
                deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(DAILY_DEADLINE),
                creatorRole = CreatorRole.PARENT,
                startEpochDay = todayEpochDay(),
                zoneId = zone,
            ),
            studentId,
        ) as AddHomeworkResult.Success
        ).items.single().id

    /** 完成「今天」这一天的阶段打卡（已记录 → 待完成 → 进行中 → 已完成） */
    private suspend fun HomeworkTestEnv.completeToday(id: Long) {
        assertTrue(repository.markPending(id, Role.PARENT) is HomeworkStatusResult.Success)
        assertTrue(repository.startProgress(id, Role.PARENT) is HomeworkStatusResult.Success)
        assertTrue(repository.complete(id, Role.PARENT) is HomeworkStatusResult.Success)
    }

    private fun HomeworkTestEnv.todayEpochDay(): Long =
        HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zone)

    private companion object Constants {

        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        val UTC: ZoneId = ZoneId.of("UTC")

        /** 与仓库测试同源的每日截止时刻（保证「排定/完成」链路不被截止约束拦住） */
        val DAILY_DEADLINE: LocalTime = LocalTime.of(21, 0)
    }
}
