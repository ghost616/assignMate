package com.assignmate.app.homework.domain

import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「阶段每天详情推导的业务时区必须显式注入」单测（对照风后开发计划第一条）。
 *
 * 背景：`StageDayRecords` 的推导入口此前带 `zoneId: ZoneId = ZoneId.systemDefault()` 默认值，
 * 于是它成了**静默的第二时区入口**——覆写 core 唯一业务时区绑定后，凡漏传的调用点都会悄悄
 * 落回系统时区，阶段起止日 / 归属日 / 可见性 / 每日截止时刻折算随之漂移。
 *
 * 本类锁两件事：
 * 1. **结构**：五个推导入口的 `zoneId` 一律必填（无默认值），且 homework 生产代码中不得出现
 *    `ZoneId.systemDefault()`（本模块不得自造业务时区）；
 * 2. **行为**：同一固定时刻 / 同一份每天详情下，只改注入的业务时区，阶段起止日、归属日、
 *    学生可见性与每日截止时刻的绝对折算**同步变化**（含一个跨零点用例：UTC 仍是当日、
 *    上海已跨入次日）。
 */
class StageDayRecordsZoneRequiredTest {

    // ---- 1. 结构：zoneId 必填 + 本模块不得自造业务时区 ----

    @Test
    fun `阶段推导入口的 zoneId 一律必填不得再给默认值`() {
        val code = codeText(homeworkMainFile("domain/StageDayRecords.kt"))

        val paramsByName = Regex(
            "fun\\s+(outcomeFor|todayOutcome|progressOf|isTodayActionable|isVisibleOnDay)\\s*\\(([^)]*)\\)",
        ).findAll(code).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }

        assertEquals(
            "五个推导入口必须都在（漏一个就说明签名被改散）",
            listOf("outcomeFor", "todayOutcome", "progressOf", "isTodayActionable", "isVisibleOnDay"),
            paramsByName.keys.toList(),
        )
        paramsByName.forEach { (name, params) ->
            assertTrue("$name 必须接收业务时区 zoneId", params.contains("zoneId: ZoneId"))
            assertFalse(
                "$name 的 zoneId 不得带任何默认值（否则又是静默第二时区入口）",
                Regex("zoneId:\\s*ZoneId\\s*=").containsMatchIn(params),
            )
        }
    }

    @Test
    fun `homework 生产代码不得自行以 systemDefault 充当业务时区`() {
        val offenders = homeworkMainSources()
            .filter { file -> codeText(file).contains("ZoneId.systemDefault()") }
            .map { it.name }
            .sorted()

        assertEquals(
            "homework 内业务时区只能来自注入（core 唯一绑定）；默认值或字段级兜底都会形成第二个口径入口",
            emptyList<String>(),
            offenders,
        )
    }

    // ---- 2. 行为：只改注入时区，起止日 / 归属日 / 可见性 / 每日折算同步变化 ----

    @Test
    fun `覆写业务时区后阶段起止日与归属日同步切换（跨零点）`() {
        // 固定时刻：上海 2023-11-15 06:13（已跨入次日），UTC 仍是 2023-11-14 22:13
        assertEquals("测试前提：两套时区相差一个自然日", 1L, SHANGHAI_DAY - UTC_DAY)

        // 学生录入的阶段作业（无每日时刻 → deadline 列不承载编码，起始日只能由创建日回退）
        val item = stageHomework(deadline = null)

        assertEquals("上海口径：阶段自「今天」起", SHANGHAI_DAY, item.stageStartEpochDayOr(SHANGHAI))
        assertEquals("UTC 口径：同一条作业的阶段起始日整体前移一天", UTC_DAY, item.stageStartEpochDayOr(UTC))
        assertNull("无每日时刻时 deadline 列无可还原编码（不臆造起始日）", item.stageStartEpochDay)

        assertEquals("归属日按业务时区折算（上海）", SHANGHAI_DAY, item.createdEpochDay(SHANGHAI))
        assertEquals("归属日按业务时区折算（UTC）", UTC_DAY, item.createdEpochDay(UTC))
    }

    @Test
    fun `覆写业务时区后学生可见性与阶段进度覆盖区间同步平移（跨零点）`() {
        val item = stageHomework(deadline = null)

        // 同一个自然日 UTC_DAY：UTC 口径下阶段正好开始（可见），上海口径下阶段自次日才开始（不可见）
        assertTrue("UTC 口径：阶段自 UTC_DAY 开始 → 可见", StageDayRecords.isVisibleOnDay(item, UTC_DAY, UTC))
        assertFalse(
            "上海口径：阶段自 SHANGHAI_DAY 开始 → 同一天不可见",
            StageDayRecords.isVisibleOnDay(item, UTC_DAY, SHANGHAI),
        )

        // 进度：同一「今天」在两套时区下的覆盖区间整体平移一天
        val today = SHANGHAI_DAY
        val inShanghai = StageDayRecords.progressOf(item, emptyList(), today, SHANGHAI)!!
        val inUtc = StageDayRecords.progressOf(item, emptyList(), today, UTC)!!

        assertEquals(StageRange.ONE_WEEK.days, inShanghai.coveredDays)
        // 上海：阶段自「今天」起 → 覆盖首日即今天（已过去 1 天、无缺卡、尚余 6 天）
        assertEquals("上海口径：阶段自今天起 → 覆盖区间首日即今天", 1, inShanghai.elapsedDays)
        assertEquals(0, inShanghai.missedDays)
        assertEquals(StageRange.ONE_WEEK.days - 1, inShanghai.remainingDays)
        // UTC：起始日整体前移一天（阶段自上海口径的「昨天」起）→ 已过去 2 天，其中首日已缺卡
        assertEquals("UTC 口径：阶段起始日整体前移一天", 2, inUtc.elapsedDays)
        assertEquals("UTC 口径：已过去的首日未完成 → 缺卡 1 天", 1, inUtc.missedDays)
        assertEquals(StageRange.ONE_WEEK.days - 2, inUtc.remainingDays)

        // 今日状态同源：同一个自然日（UTC_DAY）在两套口径下分别落成「阶段首日·待完成」与「阶段尚未开始·未到」
        assertEquals(
            "UTC 口径：该天正是阶段首日 → 待完成",
            HomeworkDayState.PENDING,
            StageDayRecords.todayOutcome(item, emptyList(), UTC_DAY, UTC)?.state,
        )
        assertEquals(
            "上海口径：该天在阶段开始之前 → 未到",
            HomeworkDayState.NOT_ARRIVED,
            StageDayRecords.todayOutcome(item, emptyList(), UTC_DAY, SHANGHAI)?.state,
        )
        assertTrue(StageDayRecords.isTodayActionable(item, emptyList(), UTC_DAY, UTC))
        assertFalse(StageDayRecords.isTodayActionable(item, emptyList(), UTC_DAY, SHANGHAI))
    }

    @Test
    fun `覆写业务时区后每日截止时刻的绝对折算同步切换`() {
        // 带每日截止时刻的阶段作业：钟面值固定 21:00，绝对瞬时随业务时区折算
        val item = stageHomework(deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)))
        assertEquals(LocalTime.of(21, 0), item.dailyDeadlineTime)

        val inShanghai = StageDayRecords.dailyDeadlineOf(item, SHANGHAI_DAY, SHANGHAI)!!.toEpochMilli()
        val inUtc = StageDayRecords.dailyDeadlineOf(item, SHANGHAI_DAY, UTC)!!.toEpochMilli()

        assertEquals(
            "同一钟面值在同一自然日下：UTC 口径的绝对瞬时比上海口径（UTC+8）晚 8 小时",
            8 * 60 * 60 * 1000L,
            inUtc - inShanghai,
        )
        assertEquals(
            "绝对折算与编码器同源（不自行解释钟面值）",
            HomeworkDailyDeadlineCodec.instantAt(SHANGHAI_DAY, LocalTime.of(21, 0), SHANGHAI).toEpochMilli(),
            inShanghai,
        )
    }

    // ---- 测试工具 ----

    /** 阶段作业（createdAt 固定在同一瞬时；[deadline] 为空表示无每日时刻，起始日只能由创建日回退） */
    private fun stageHomework(deadline: Instant?): HomeworkItem = HomeworkItem(
        id = 1L,
        parentAccountId = PARENT_ID,
        studentId = STUDENT_ID,
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = deadline,
        priority = 0,
        startTime = null,
        estimatedMinutes = null,
        status = HomeworkStatus.RECORDED,
        createdByRole = CreatorRole.STUDENT,
        createdAt = Instant.ofEpochMilli(FIXED_MILLIS),
    )

    private fun homeworkMainSources(): List<File> {
        val dir = File(repoRoot(), HOMEWORK_MAIN_ROOT)
        assertTrue("homework 主源码目录不存在: ${dir.absolutePath}", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun homeworkMainFile(relativePath: String): File {
        val file = File(repoRoot(), "$HOMEWORK_MAIN_ROOT/$relativePath")
        assertTrue("源文件不存在: ${file.absolutePath}", file.isFile)
        return file
    }

    /** 去掉块注释与行注释后的代码文本：KDoc 里的说明字样不得参与结构校验 */
    private fun codeText(file: File): String = file.readText()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "\n")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, HOMEWORK_MAIN_ROOT).isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("在工作目录 ${File("").absolutePath} 向上未找到 $HOMEWORK_MAIN_ROOT")
    }

    private companion object Constants {

        const val HOMEWORK_MAIN_ROOT = "app/src/main/java/com/assignmate/app/homework"

        const val PARENT_ID = 1L
        const val STUDENT_ID = 10L

        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        val UTC: ZoneId = ZoneId.of("UTC")

        /** 与 [com.assignmate.app.homework.data.HomeworkTestEnv] 同源的固定时钟（跨零点场景） */
        const val FIXED_MILLIS: Long = 1_700_000_000_000L

        val SHANGHAI_DAY: Long = HomeworkValidators.epochDayOf(FIXED_MILLIS, SHANGHAI)
        val UTC_DAY: Long = HomeworkValidators.epochDayOf(FIXED_MILLIS, UTC)
    }
}
