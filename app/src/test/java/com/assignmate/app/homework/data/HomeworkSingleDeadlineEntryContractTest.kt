package com.assignmate.app.homework.data

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidation
import com.assignmate.app.homework.domain.HomeworkValidationError
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageRange
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按类型分流选 deadline 校验」必须收敛到**单一入口**的契约 + 仓库/UI 同口径（复审 warning A 收口）。
 *
 * 修复前仓库 `updateSchedule` 用行内 `if (domain.isStage)` 自写分流、`updateTemplate` 又经私有
 * `scheduleDeadlineCheckFor` 自写一套「按类型分流」，两侧虽然逻辑等价，但**判据分散在两处**——
 * 这正是原 error（UI 先拦、仓库本会放行）的复发形态。现两处一律走
 * [HomeworkValidators.validateScheduleWithinItemDeadline]（类型分流只发生在 HomeworkValidators 内部）。
 *
 * 本类用两层锁定：
 * 1. **结构层（读码断言）**：仓库源码不得再直接调用 `validateDeadline` / `validateScheduleWithinDailyDeadline`，
 *    两个子入口在整个 homework 生产代码中只有 HomeworkValidators 自己可以调用；
 * 2. **行为层（真实仓库 + 内存 DAO）**：同一候选（作业 + 开始时刻 + 时长）在「UI 预校验入口」与
 *    「仓库」两侧的结论必须逐例一致（含阶段作业可提交、跨日时长的钟面比较盲区）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkSingleDeadlineEntryContractTest {

    // ---- 1. 结构层：单一入口 ----

    @Test
    fun `仓库内部不再自写按类型分流的 deadline 校验`() {
        val repoSource = readMainSource("data/HomeworkRepositoryImpl.kt")

        assertEquals(
            "updateSchedule 与 updateTemplate 两处都必须走同一入口",
            2,
            Regex("validateScheduleWithinItemDeadline\\(").findAll(repoSource).count(),
        )
        assertFalse(
            "不得直接调用「当天作业」分支校验（类型判据必须只在 HomeworkValidators 内）",
            repoSource.contains("validateDeadline("),
        )
        assertFalse(
            "不得直接调用「阶段作业」分支校验",
            repoSource.contains("validateScheduleWithinDailyDeadline("),
        )
        assertFalse(
            "原第二套判据 scheduleDeadlineCheckFor 必须已删除",
            repoSource.contains("scheduleDeadlineCheckFor"),
        )
    }

    @Test
    fun `全模块只有 HomeworkValidators 可调用按类型分出的两个子入口`() {
        val offenders = homeworkMainSources()
            .filter { it.name != "HomeworkValidators.kt" }
            .filter { file ->
                val code = file.readText()
                code.contains("HomeworkValidators.validateDeadline(") ||
                    code.contains("HomeworkValidators.validateScheduleWithinDailyDeadline(")
            }
            .map { it.name }

        assertTrue(
            "除 HomeworkValidators 自身外，任何生产文件都不得直接调用子入口（越界文件：$offenders）",
            offenders.isEmpty(),
        )
    }

    // ---- 2. 行为层：仓库与 UI 预校验同口径 ----

    @Test
    fun `阶段作业截止判定：仓库结果与单一入口逐例一致`() = runTest {
        val daily = LocalTime.of(21, 0)
        // 放行：20:00 开始 60 分钟，正好等于每日截止时刻 21:00（等于视为通过）
        assertStageConsistent(daily, startAt(20, 0), minutes = 60, expectValid = true)
        // 放行：20:59 开始 1 分钟，同样正好到点
        assertStageConsistent(daily, startAt(20, 59), minutes = 1, expectValid = true)
        // 拦下：21:00 开始 1 分钟即越限
        assertStageConsistent(daily, startAt(21, 0), minutes = 1, expectValid = false)
        // 拦下：23:00 开始 120 分钟（跨日；只比钟面会误判「结束 01:00 早于 21:00」而放过）
        assertStageConsistent(daily, startAt(23, 0), minutes = 120, expectValid = false)
    }

    @Test
    fun `当天作业截止判定：仓库结果与单一入口逐例一致`() = runTest {
        val deadline = LocalTime.of(18, 0)
        // 放行：17:00 开始 60 分钟 = 绝对 deadline 18:00（正好等于）
        assertTodayConsistent(deadline, startAt(17, 0), minutes = 60, expectValid = true)
        // 拦下：17:01 开始 60 分钟
        assertTodayConsistent(deadline, startAt(17, 1), minutes = 60, expectValid = false)
        // 拦下：次日开始（当天作业的绝对 deadline 不随日界重置）
        assertTodayConsistent(deadline, nextDayAt(9, 0), minutes = 30, expectValid = false)
    }

    @Test
    fun `修改截止时间时仓库判定与单一入口同口径（当天作业）`() = runTest {
        val env = HomeworkTestEnv()
        val (item, scheduled) = env.todayItemScheduledAt(
            startMillis = startAt(19, 0),
            minutes = 60,
            deadlineTime = LocalTime.of(21, 0),
        )
        assertEquals("既有排定 19:00-20:00 已落库", startAt(19, 0), scheduled.startTime!!.toEpochMilli())

        // 收紧到 19:30：容不下既有排定
        val tight = instantAt(19, 30)
        assertEquals(
            "单一入口对「候选 deadline + 既有排定」的判定",
            HomeworkValidation.Invalid(HomeworkValidationError.DEADLINE_EXCEEDED),
            HomeworkValidators.validateScheduleWithinItemDeadline(
                scheduled.copy(deadline = tight),
                scheduled.startTime!!.toEpochMilli(),
                scheduled.estimatedMinutes!!,
                HomeworkTestEnv.ZONE,
            ),
        )
        assertEquals(
            "仓库 updateTemplate 必须同口径拦下",
            HomeworkOperationResult.TemplateInvalid(HomeworkValidationError.DEADLINE_EXCEEDED),
            env.repository.updateTemplate(item.id, HomeworkType.TODAY, null, tight, Role.PARENT),
        )

        // 放宽到 20:00（正好容纳）→ 两侧都通过
        val exact = instantAt(20, 0)
        assertEquals(
            HomeworkValidation.Valid,
            HomeworkValidators.validateScheduleWithinItemDeadline(
                scheduled.copy(deadline = exact),
                scheduled.startTime!!.toEpochMilli(),
                scheduled.estimatedMinutes!!,
                HomeworkTestEnv.ZONE,
            ),
        )
        assertTrue(
            "正好容纳时应放行",
            env.repository.updateTemplate(item.id, HomeworkType.TODAY, null, exact, Role.PARENT)
                is HomeworkOperationResult.Success,
        )
    }

    @Test
    fun `改为阶段作业时仓库判定与单一入口同口径（每日时刻分流）`() = runTest {
        val env = HomeworkTestEnv()
        // 既有排定 16:00-18:00（当天作业）
        val (item, scheduled) = env.todayItemScheduledAt(
            startMillis = startAt(16, 0),
            minutes = 120,
            deadlineTime = LocalTime.of(21, 0),
        )

        // 改为阶段作业且每日时刻 17:00：既有排定跨过该时刻 → 两侧都拦下
        val tooEarly = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(17, 0))
        assertEquals(
            "阶段分支：按每日时刻判定既有排定",
            HomeworkValidation.Invalid(HomeworkValidationError.DEADLINE_EXCEEDED),
            HomeworkValidators.validateScheduleWithinItemDeadline(
                scheduled.copy(type = HomeworkType.STAGE, stageRange = StageRange.ONE_WEEK, deadline = tooEarly),
                scheduled.startTime!!.toEpochMilli(),
                scheduled.estimatedMinutes!!,
                HomeworkTestEnv.ZONE,
            ),
        )
        assertEquals(
            "仓库必须同口径拦下（说明类型分流已收敛到同一入口）",
            HomeworkOperationResult.TemplateInvalid(HomeworkValidationError.DEADLINE_EXCEEDED),
            env.repository.updateTemplate(item.id, HomeworkType.STAGE, StageRange.ONE_WEEK, tooEarly, Role.PARENT),
        )

        // 放宽到 18:00（正好容纳 16:00-18:00）→ 两侧都通过
        val exact = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(18, 0))
        assertEquals(
            HomeworkValidation.Valid,
            HomeworkValidators.validateScheduleWithinItemDeadline(
                scheduled.copy(type = HomeworkType.STAGE, stageRange = StageRange.ONE_WEEK, deadline = exact),
                scheduled.startTime!!.toEpochMilli(),
                scheduled.estimatedMinutes!!,
                HomeworkTestEnv.ZONE,
            ),
        )
        assertTrue(
            "改为阶段作业且每日时刻正好容纳既有排定时应放行",
            env.repository.updateTemplate(item.id, HomeworkType.STAGE, StageRange.ONE_WEEK, exact, Role.PARENT)
                is HomeworkOperationResult.Success,
        )
    }

    // ---- 测试工具 ----

    /**
     * 同一候选在两侧的结论必须一致：先建「家长录入 + 每日时刻」的阶段作业，再比对
     * [HomeworkValidators.validateScheduleWithinItemDeadline]（设时间页本地预校验同一入口）
     * 与仓库 updateSchedule 的结果。
     */
    private suspend fun assertStageConsistent(
        dailyTime: LocalTime,
        startMillis: Long,
        minutes: Int,
        expectValid: Boolean,
    ) {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.setEpochDay(DAY, hour = 9, minute = 0)
        val item = (
            env.repository.addHomework(
                HomeworkTemplate(
                    content = "每天读课文",
                    type = HomeworkType.STAGE,
                    stageRange = StageRange.ONE_WEEK,
                    deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(dailyTime),
                    creatorRole = CreatorRole.PARENT,
                    startEpochDay = env.todayEpochDay(),
                    zoneId = HomeworkTestEnv.ZONE,
                ),
                studentId,
            ) as AddHomeworkResult.Success
            ).items.single()
        // 读回落库事实：每日时刻经编码/解码参与判定，不用构造入参自证
        val stored = env.repository.getHomework(item.id)!!
        assertEquals("阶段作业每日时刻应可从 deadline 列还原", dailyTime, stored.dailyDeadlineTime)

        assertSameVerdict(env, item.id, stored, startMillis, minutes, expectValid)
    }

    /** 当天作业（绝对 deadline）两侧结论一致性 */
    private suspend fun assertTodayConsistent(
        deadlineTime: LocalTime,
        startMillis: Long,
        minutes: Int,
        expectValid: Boolean,
    ) {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.setEpochDay(DAY, hour = 9, minute = 0)
        val item = (
            env.repository.addHomework(
                HomeworkTemplate(
                    content = "数学练习册",
                    type = HomeworkType.TODAY,
                    deadline = HomeworkDailyDeadlineCodec.instantAt(DAY, deadlineTime, HomeworkTestEnv.ZONE),
                    creatorRole = CreatorRole.PARENT,
                    startEpochDay = env.todayEpochDay(),
                    zoneId = HomeworkTestEnv.ZONE,
                ),
                studentId,
            ) as AddHomeworkResult.Success
            ).items.single()
        val stored = env.repository.getHomework(item.id)!!
        assertEquals(deadlineTime, stored.deadline!!.atZone(HomeworkTestEnv.ZONE).toLocalTime())

        assertSameVerdict(env, item.id, stored, startMillis, minutes, expectValid)
    }

    private suspend fun assertSameVerdict(
        env: HomeworkTestEnv,
        homeworkId: Long,
        stored: HomeworkItem,
        startMillis: Long,
        minutes: Int,
        expectValid: Boolean,
    ) {
        assertEquals(
            "UI 预校验（设时间页同一入口）",
            if (expectValid) {
                HomeworkValidation.Valid
            } else {
                HomeworkValidation.Invalid(HomeworkValidationError.DEADLINE_EXCEEDED)
            },
            HomeworkValidators.validateScheduleWithinItemDeadline(stored, startMillis, minutes, HomeworkTestEnv.ZONE),
        )
        val result = env.repository.updateSchedule(
            homeworkId = homeworkId,
            startTime = Instant.ofEpochMilli(startMillis),
            estimatedMinutes = minutes,
            sessionRole = Role.PARENT,
        )
        if (expectValid) {
            assertTrue("仓库必须与 UI 同口径放行，实际：$result", result is ScheduleUpdateResult.Success)
        } else {
            assertEquals("仓库必须与 UI 同口径拦下，实际：$result", ScheduleUpdateResult.DeadlineExceeded, result)
        }
    }

    /** 建一条「当天作业 + 已排定时间段」并读回落库事实（排定为 19:00-20:00 之类的既有时间段） */
    private suspend fun HomeworkTestEnv.todayItemScheduledAt(
        startMillis: Long,
        minutes: Int,
        deadlineTime: LocalTime,
    ): Pair<HomeworkItem, HomeworkItem> {
        val parentId = loginAsParent()
        val studentId = addStudent(parentId)
        setEpochDay(DAY, hour = 9, minute = 0)
        val item = (
            repository.addHomework(
                HomeworkTemplate(
                    content = "数学练习册",
                    type = HomeworkType.TODAY,
                    deadline = HomeworkDailyDeadlineCodec.instantAt(DAY, deadlineTime, HomeworkTestEnv.ZONE),
                    creatorRole = CreatorRole.PARENT,
                    startEpochDay = todayEpochDay(),
                    zoneId = HomeworkTestEnv.ZONE,
                ),
                studentId,
            ) as AddHomeworkResult.Success
            ).items.single()
        val result = repository.updateSchedule(item.id, Instant.ofEpochMilli(startMillis), minutes, Role.PARENT)
        check(result is ScheduleUpdateResult.Success) { "前置排定应成功，实际：$result" }
        return item to repository.getHomework(item.id)!!
    }

    private fun HomeworkTestEnv.todayEpochDay(): Long =
        HomeworkValidators.epochDayOf(clock.currentTimeMillis(), HomeworkTestEnv.ZONE)

    private fun startAt(hour: Int, minute: Int): Long =
        HomeworkDailyDeadlineCodec.instantAt(DAY, LocalTime.of(hour, minute), HomeworkTestEnv.ZONE).toEpochMilli()

    /** 次日的同一钟面时刻（用于「当天作业的绝对 deadline 不随日界重置」用例） */
    private fun nextDayAt(hour: Int, minute: Int): Long =
        HomeworkDailyDeadlineCodec.instantAt(DAY + 1, LocalTime.of(hour, minute), HomeworkTestEnv.ZONE)
            .toEpochMilli()

    private fun instantAt(hour: Int, minute: Int): Instant =
        HomeworkDailyDeadlineCodec.instantAt(DAY, LocalTime.of(hour, minute), HomeworkTestEnv.ZONE)

    private fun readMainSource(relativePath: String): String =
        File(homeworkMainRoot(), relativePath).readText()

    private fun homeworkMainSources(): List<File> =
        homeworkMainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun homeworkMainRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, HOMEWORK_MAIN_ROOT)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("在工作目录 ${File("").absolutePath} 向上未找到 $HOMEWORK_MAIN_ROOT")
    }

    private companion object Constants {

        const val HOMEWORK_MAIN_ROOT = "app/src/main/java/com/assignmate/app/homework"

        /** 业务自然日基准：2023-11-15 */
        val DAY: Long = LocalDate.of(2023, 11, 15).toEpochDay()
    }
}