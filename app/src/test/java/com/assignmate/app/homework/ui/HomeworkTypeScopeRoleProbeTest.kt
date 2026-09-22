package com.assignmate.app.homework.ui

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrRecognizer
import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.FakeHomeworkFileStore
import com.assignmate.app.homework.data.FakeOcrConfigStore
import com.assignmate.app.homework.data.FakePendingOcrRepository
import com.assignmate.app.homework.data.HomeworkTestEnv
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.homework.domain.StageRange
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「家长作业类型收敛为阶段作业 + 阶段表单预填 + 阶段作业保存闪退修复」的独立探针单测。
 *
 * 与随功能提交的 HomeworkTypeScopeByRoleTest 互补，补足以下未覆盖面：
 *
 * A. 收敛矩阵的全 8 种 touched 组合（逐字段独立、互不牵连）——原用例只覆盖「全改」与「全未改」两端；
 * B. Role 枚举全量的类型选项口径（防止将来新增角色时静默落入「两项」分支）；
 * C. 闪退修复的等价性对照：在 JVM 上直接调用被移除的 Java 9 API
 *    （LocalTime.ofInstant / LocalDate.ofInstant，JDK 17 可用）与修复后写法逐一比对，
 *    含纳秒精度与跨日边界——这是「逐字等价」最强的独立证据（生产源码侧只做静态守卫）；
 * D. 阶段编码不随业务时区漂移（同一钟面值在两个不同 ZoneId 下必须落到同一 deadline 列）；
 * E. 编辑页（HomeworkTemplateViewModel）的角色异步到位时序（原用例的时序探针只覆盖添加页）；
 * F. 部分字段被改时的部分收敛时序（类型被改、范围/时刻未被改）；
 * G. 添加页保存后阶段起始日落到「今日」（原用例的添加页断言缺此项）；
 * H. 全应用主源码（不限 homework 包）不得再出现 Java 9 的 ofInstant 家族 API。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkTypeScopeRoleProbeTest {

    private lateinit var tempDir: File

    private val zone: ZoneId = SHANGHAI

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempDir = Files.createTempDirectory("homework-type-scope-probe").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    // ---- A. 收敛矩阵：逐字段独立 ----

    @Test
    fun `家长默认值收敛对全部 touched 组合逐字段独立`() {
        // 表驱动、期望值逐行硬编码（不复用被测的 if-else 逻辑，避免自证）
        val rows = listOf(
            Triple(HomeworkFormTouched(), HomeworkFormDefaults(HomeworkType.STAGE, StageRange.ONE_WEEK, "21:00"), "全未改 -> 全部收敛为家长默认"),
            Triple(HomeworkFormTouched(type = true), HomeworkFormDefaults(HomeworkType.TODAY, StageRange.ONE_WEEK, "21:00"), "只改类型"),
            Triple(HomeworkFormTouched(stageRange = true), HomeworkFormDefaults(HomeworkType.STAGE, StageRange.TWO_WEEKS, "21:00"), "只改范围"),
            Triple(HomeworkFormTouched(deadlineTime = true), HomeworkFormDefaults(HomeworkType.STAGE, StageRange.ONE_WEEK, "20:30"), "只改时刻"),
            Triple(HomeworkFormTouched(type = true, stageRange = true), HomeworkFormDefaults(HomeworkType.TODAY, StageRange.TWO_WEEKS, "21:00"), "改类型+范围"),
            Triple(HomeworkFormTouched(type = true, deadlineTime = true), HomeworkFormDefaults(HomeworkType.TODAY, StageRange.ONE_WEEK, "20:30"), "改类型+时刻"),
            Triple(HomeworkFormTouched(stageRange = true, deadlineTime = true), HomeworkFormDefaults(HomeworkType.STAGE, StageRange.TWO_WEEKS, "20:30"), "改范围+时刻"),
            Triple(HomeworkFormTouched(type = true, stageRange = true, deadlineTime = true), HomeworkFormDefaults(HomeworkType.TODAY, StageRange.TWO_WEEKS, "20:30"), "全改 -> 原样保留"),
        )

        rows.forEach { (touched, expected, label) ->
            val actual = homeworkFormDefaults(Role.PARENT).appliedTo(
                type = HomeworkType.TODAY,
                stageRange = StageRange.TWO_WEEKS,
                deadlineTime = "20:30",
                touched = touched,
            )
            assertEquals(label, expected, actual)
        }
    }

    @Test
    fun `学生默认值收敛对已改动字段同样不覆盖`() {
        // 学生口径（当天作业 / 空）对未改字段是空操作；对已改字段同样必须原样保留
        val untouched = homeworkFormDefaults(Role.STUDENT).appliedTo(
            type = HomeworkType.TODAY,
            stageRange = null,
            deadlineTime = "",
            touched = HomeworkFormTouched(),
        )
        assertEquals(homeworkFormDefaults(Role.STUDENT), untouched)

        val touchedStudent = homeworkFormDefaults(Role.STUDENT).appliedTo(
            type = HomeworkType.STAGE,
            stageRange = StageRange.ONE_WEEK,
            deadlineTime = "19:00",
            touched = HomeworkFormTouched(type = true, stageRange = true, deadlineTime = true),
        )
        assertEquals(HomeworkType.STAGE, touchedStudent.type)
        assertEquals(StageRange.ONE_WEEK, touchedStudent.stageRange)
        assertEquals("19:00", touchedStudent.deadlineTime)
    }

    // ---- B. 角色枚举全量口径 ----

    @Test
    fun `Role 枚举每个取值的类型选项口径都被显式定义`() {
        assertTrue("本探针依赖 Role 至少含家长与学生两种取值", Role.entries.size >= 2)
        Role.entries.forEach { role ->
            val options = homeworkTypeOptions(role)
            assertTrue("$role 必须至少有一个可选类型（否则表单无类型可渲染）", options.isNotEmpty())
            assertEquals("$role 的选项不得重复", options.size, options.toSet().size)
            if (role == Role.PARENT) {
                assertEquals("家长只能选阶段作业", listOf(HomeworkType.STAGE), options)
                assertFalse("家长选项不得含当天作业", options.contains(HomeworkType.TODAY))
            } else {
                assertEquals("非家长（学生）保持两项", HomeworkType.entries.toList(), options)
                assertEquals("学生默认仍是当天作业", HomeworkType.TODAY, homeworkFormDefaults(role).type)
            }
        }
    }

    // ---- C. 闪退修复：与已移除的 Java 9 API 逐字对照 ----

    @Test
    fun `阶段每日时刻还原与 Java 9 的 LocalTime ofInstant 逐字等价`() {
        val samples = listOf(
            LocalTime.of(21, 0),
            LocalTime.of(0, 0),
            LocalTime.of(23, 59, 59, 999_000_000),
            LocalTime.of(20, 30, 15, 123_000_000),
        )

        samples.forEach { timeOfDay ->
            val carrier = HomeworkDailyDeadlineCodec.timeOfDayCarrier(timeOfDay)
            val item = stageTemplate(
                deadline = carrier,
                zoneId = zone,
            ).toItems(
                parentAccountId = PARENT_ID,
                studentId = STUDENT_ID,
                createdAt = Instant.ofEpochMilli(HomeworkTestEnv.FIXED_MILLIS),
                firstPriority = 0,
            ).single()

            assertEquals(
                "修复前写法 LocalTime.ofInstant(instant, UTC) 的结果必须与落库还原值一致（$timeOfDay）",
                LocalTime.ofInstant(carrier, ZoneOffset.UTC),
                item.dailyDeadlineTime,
            )
            assertEquals("钟面值逐位保留（含秒与毫秒）", timeOfDay, item.dailyDeadlineTime)
        }
    }

    @Test
    fun `当天作业自然日折算与 Java 9 的 LocalDate ofInstant 逐字等价`() {
        // 覆盖跨日边界：上海 23:59:59 / 次日 00:00:00，以及 UTC 侧的同一瞬时分界
        val millisList = listOf(
            LocalDateTime.of(2023, 11, 14, 23, 59, 59).atZone(SHANGHAI).toInstant().toEpochMilli(),
            LocalDateTime.of(2023, 11, 15, 0, 0, 0).atZone(SHANGHAI).toInstant().toEpochMilli(),
            LocalDateTime.of(2023, 11, 15, 0, 30, 0).atZone(SHANGHAI).toInstant().toEpochMilli(),
            LocalDateTime.of(2024, 2, 29, 12, 0, 0).atZone(SHANGHAI).toInstant().toEpochMilli(),
            HomeworkTestEnv.FIXED_MILLIS,
        )

        listOf(SHANGHAI, UTC).forEach { sampleZone ->
            millisList.forEach { millis ->
                assertEquals(
                    "absoluteEpochDay 必须与已移除的 LocalDate.ofInstant 等价（zone=$sampleZone, millis=$millis）",
                    LocalDate.ofInstant(Instant.ofEpochMilli(millis), sampleZone).toEpochDay(),
                    HomeworkDailyDeadlineCodec.absoluteEpochDay(millis, sampleZone),
                )
            }
        }
    }

    // ---- D. 阶段编码不随时区漂移 ----

    @Test
    fun `阶段每日时刻编码不随业务时区漂移`() {
        val shanghaiItem = stageTemplate(
            deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
            zoneId = SHANGHAI,
        ).toItems(PARENT_ID, STUDENT_ID, Instant.ofEpochMilli(HomeworkTestEnv.FIXED_MILLIS), 0).single()

        val utcItem = stageTemplate(
            deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)),
            zoneId = UTC,
        ).toItems(PARENT_ID, STUDENT_ID, Instant.ofEpochMilli(HomeworkTestEnv.FIXED_MILLIS), 0).single()

        assertEquals(
            "阶段每日时刻是钟面值：换业务时区不得改变落库 deadline（否则产生正负 8 小时偏移）",
            shanghaiItem.deadline,
            utcItem.deadline,
        )
        assertEquals(LocalTime.of(21, 0), utcItem.dailyDeadlineTime)
        assertEquals(START_EPOCH_DAY, utcItem.stageStartEpochDay)
    }

    // ---- E / F. 时序：编辑页与「部分字段已改」 ----

    @Test
    fun `编辑页角色异步到位后收敛家长默认值且不覆盖用户已改`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val viewModel = gatedTemplateViewModel(Role.PARENT, gate)

        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        assertNull("会话尚未返回：角色仍未知", viewModel.uiState.value.role)

        // 角色未到位时用户已改「阶段范围 + 每日时刻」（类型未改）
        viewModel.onStageRangeChange(StageRange.TWO_WEEKS)
        viewModel.onDeadlineTimeChange("20:30")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(Role.PARENT, viewModel.uiState.value.role)
        assertEquals("类型未改 -> 收敛为家长默认的阶段作业", HomeworkType.STAGE, viewModel.uiState.value.type)
        assertEquals("已改过的范围不得被覆盖", StageRange.TWO_WEEKS, viewModel.uiState.value.stageRange)
        assertEquals("已改过的时刻不得被覆盖", "20:30", viewModel.uiState.value.deadlineTime)
        assertEquals(listOf(HomeworkType.STAGE), viewModel.uiState.value.availableTypes)
    }

    @Test
    fun `添加页仅类型被改时其余字段仍按时序收敛`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val viewModel = gatedEntryViewModel(Role.PARENT, gate)

        viewModel.start(STUDENT_ID)
        advanceUntilIdle()

        viewModel.onTypeChange(HomeworkType.TODAY)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(Role.PARENT, viewModel.uiState.value.role)
        assertEquals("已改过的类型不得被覆盖", HomeworkType.TODAY, viewModel.uiState.value.type)
        assertEquals("未改过的范围按家长默认收敛", StageRange.ONE_WEEK, viewModel.uiState.value.stageRange)
        assertEquals("未改过的时刻按家长默认收敛", "21:00", viewModel.uiState.value.deadlineTime)
        assertEquals(listOf(HomeworkType.STAGE), viewModel.uiState.value.availableTypes)
    }

    // ---- G. 添加页保存：阶段起始日落今日 ----

    @Test
    fun `家长添加页预填值直接保存后阶段起始日为今日`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val viewModel = entryViewModel(env)

        viewModel.start(studentId)
        advanceUntilIdle()
        viewModel.onContentChange("每天读课文")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.deadlineError)
        assertNull(viewModel.uiState.value.formError)
        val item = env.repository.listHomework(studentId).single()
        assertEquals(HomeworkType.STAGE, item.type)
        assertEquals(StageRange.ONE_WEEK, item.stageRange)
        assertEquals(LocalTime.of(21, 0), item.dailyDeadlineTime)
        assertEquals(
            "阶段起始日 = 作业创建日（业务时区口径）",
            HomeworkValidators.epochDayOf(env.clock.currentTimeMillis(), zone),
            item.stageStartEpochDay,
        )
        assertEquals("恰好 1 条作业项", 1, env.repository.listHomework(studentId).size)
    }

    // ---- H. 全应用主源码守卫 ----

    @Test
    fun `全应用主源码不得再出现 Java 9 的 ofInstant 家族 API`() {
        val dir = File(repoRoot(), APP_MAIN_ROOT)
        assertTrue("主源码目录不存在: ${dir.absolutePath}", dir.isDirectory)
        val offenders = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { JAVA9_OF_INSTANT.containsMatchIn(codeText(it)) }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') }
            .sorted()
            .toList()

        assertEquals(
            "minSdk 29 且未启用 desugaring：Java 9（Android API 31）的 ofInstant 家族在 API<31 设备上抛 NoSuchMethodError",
            emptyList<String>(),
            offenders,
        )
    }

    // ---- 测试工具 ----

    private fun entryViewModel(env: HomeworkTestEnv): HomeworkEntryViewModel {
        val fileStore = FakeHomeworkFileStore(tempDir)
        return HomeworkEntryViewModel(
            homeworkRepository = env.repository,
            authRepository = env.authRepository,
            ocrHandler = ocrHandler(fileStore, env.clock),
            speechToText = mockk(relaxed = true),
            fileStore = fileStore,
            clock = env.clock,
            zoneId = zone,
        )
    }

    /** 会话返回被 gate 挂起的录入页 ViewModel：用于构造「角色异步到位」的时序 */
    private fun gatedEntryViewModel(role: Role, gate: CompletableDeferred<Unit>): HomeworkEntryViewModel {
        val fileStore = FakeHomeworkFileStore(tempDir)
        return HomeworkEntryViewModel(
            homeworkRepository = mockk(relaxed = true),
            authRepository = gatedAuthRepository(role, gate),
            ocrHandler = ocrHandler(fileStore, FixedClock()),
            speechToText = mockk(relaxed = true),
            fileStore = fileStore,
            clock = FixedClock(),
            zoneId = zone,
        )
    }

    /** 会话返回被 gate 挂起的编辑页 ViewModel：用于构造「角色异步到位」的时序 */
    private fun gatedTemplateViewModel(role: Role, gate: CompletableDeferred<Unit>): HomeworkTemplateViewModel =
        HomeworkTemplateViewModel(
            homeworkRepository = mockk(relaxed = true),
            authRepository = gatedAuthRepository(role, gate),
            clock = FixedClock(),
            zoneId = zone,
        )

    private fun gatedAuthRepository(
        role: Role,
        gate: CompletableDeferred<Unit>,
    ): AuthRepository = mockk<AuthRepository>(relaxed = true).apply {
        coEvery { currentSession() } coAnswers {
            gate.await()
            sessionOf(role)
        }
    }

    private fun ocrHandler(fileStore: FakeHomeworkFileStore, clock: Clock): HomeworkOcrHandler =
        HomeworkOcrHandler(
            ocrRecognizer = mockk<OcrRecognizer>(relaxed = true),
            ocrConfigStore = FakeOcrConfigStore(OcrConfig()),
            pendingOcrRepository = FakePendingOcrRepository(),
            fileStore = fileStore,
            clock = clock,
        )

    private fun stageTemplate(deadline: Instant?, zoneId: ZoneId): HomeworkTemplate = HomeworkTemplate(
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = deadline,
        creatorRole = CreatorRole.PARENT,
        startEpochDay = START_EPOCH_DAY,
        zoneId = zoneId,
    )

    private fun sessionOf(role: Role): SessionState = when (role) {
        Role.PARENT -> SessionState(role = Role.PARENT, parentId = PARENT_ID, studentId = null)
        Role.STUDENT -> SessionState(role = Role.STUDENT, parentId = PARENT_ID, studentId = STUDENT_ID)
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, APP_MAIN_ROOT).isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("在工作目录 ${File("").absolutePath} 向上未找到 $APP_MAIN_ROOT")
    }

    /** 去掉块注释与行注释后的代码文本：KDoc 里「不得用 xxx」的说明字样不得参与结构校验 */
    private fun codeText(file: File): String = file.readText()
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "\n")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    /** 固定时钟（业务时区 2023-11-15 附近，与 HomeworkTestEnv 默认值一致） */
    private class FixedClock : Clock {
        override fun currentTimeMillis(): Long = HomeworkTestEnv.FIXED_MILLIS
    }

    private companion object Constants {

        const val APP_MAIN_ROOT = "app/src/main/java"

        const val PARENT_ID = 1L
        const val STUDENT_ID = 1L

        /** 阶段编码用例的起始日（远离真实数据，便于锁定编码算式） */
        const val START_EPOCH_DAY = 19_700L

        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        val UTC: ZoneId = ZoneId.of("UTC")

        /** Java 9 / Android API 31 的 java.time 静态工厂（minSdk 29 上会抛 NoSuchMethodError） */
        val JAVA9_OF_INSTANT = Regex("\\bLocal(Time|Date|DateTime)\\.ofInstant\\(")
    }
}