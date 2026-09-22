package com.assignmate.app.homework.ui

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrRecognizer
import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.AddHomeworkResult
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
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
 * 「家长作业类型收敛为阶段作业 + 阶段表单预填 + 阶段作业保存不再闪退」单测。
 *
 * 覆盖三块：
 * 1. **角色口径（纯函数）**：类型选项按会话角色过滤（家长只剩阶段作业）、家长默认表单值为
 *    「阶段作业 + 一周 + 21:00」、学生默认保持「当天作业 + 空」、收敛幂等且不覆盖用户已改的选择；
 * 2. **两个录入页的行为**（添加页 [HomeworkEntryViewModel]、编辑页 [HomeworkTemplateViewModel]）：
 *    家长默认阶段作业且预填可直接保存、越界类型选择被忽略、学生端两项与默认当天作业不变、
 *    编辑既有作业以作业取值回填（不套用默认预填）、存量当天作业不做数据兼容；
 * 3. **时序**：会话角色在 `start()` 中异步解析——角色到位后收敛默认值，但**不得覆盖**用户在
 *    角色到位前已经改过的类型 / 阶段范围 / 每日时刻（用挂起的会话替身精确构造该时序）；
 * 4. **闪退修复的等价性**：阶段作业落库编码与每日时刻还原与修复前**逐字等价**（含纳秒精度），
 *    当天作业自然日折算仍走业务时区口径，且生产源码不得再出现 Java 9+ 的 java.time API。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkTypeScopeByRoleTest {

    private lateinit var tempDir: File

    private val zone: ZoneId = SHANGHAI

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempDir = Files.createTempDirectory("homework-type-scope").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    // ---- 1. 角色口径（纯函数） ----

    @Test
    fun `家长会话的作业类型选项只剩阶段作业`() {
        assertEquals(listOf(HomeworkType.STAGE), homeworkTypeOptions(Role.PARENT))
    }

    @Test
    fun `学生会话保持当天作业与阶段作业两项`() {
        assertEquals(listOf(HomeworkType.TODAY, HomeworkType.STAGE), homeworkTypeOptions(Role.STUDENT))
    }

    @Test
    fun `会话未建立时按学生口径给两项不抢先收敛`() {
        assertEquals(
            "role 为 null 时表单尚未渲染，不得抢先砍掉选项",
            listOf(HomeworkType.TODAY, HomeworkType.STAGE),
            homeworkTypeOptions(null),
        )
    }

    @Test
    fun `家长默认表单值为阶段作业加一周加21点`() {
        val defaults = homeworkFormDefaults(Role.PARENT)
        assertEquals(HomeworkType.STAGE, defaults.type)
        assertEquals(StageRange.ONE_WEEK, defaults.stageRange)
        assertEquals(DEFAULT_DAILY_DEADLINE_TEXT, defaults.deadlineTime)
        assertEquals("21:00", defaults.deadlineTime)
    }

    @Test
    fun `学生默认表单值保持既有当天作业口径`() {
        val defaults = homeworkFormDefaults(Role.STUDENT)
        assertEquals(HomeworkType.TODAY, defaults.type)
        assertNull(defaults.stageRange)
        assertEquals("学生不预填每日截止时刻（阶段作业可留空）", "", defaults.deadlineTime)
        assertEquals("会话未建立时同学生口径", defaults, homeworkFormDefaults(null))
    }

    @Test
    fun `默认值收敛不得覆盖用户已改过的字段`() {
        val converged = homeworkFormDefaults(Role.PARENT).appliedTo(
            type = HomeworkType.TODAY,
            stageRange = StageRange.TWO_WEEKS,
            deadlineTime = "20:30",
            touched = HomeworkFormTouched(type = true, stageRange = true, deadlineTime = true),
        )

        assertEquals("用户改过的类型原样保留", HomeworkType.TODAY, converged.type)
        assertEquals("用户改过的阶段范围原样保留", StageRange.TWO_WEEKS, converged.stageRange)
        assertEquals("用户改过的每日时刻原样保留", "20:30", converged.deadlineTime)

        val untouched = homeworkFormDefaults(Role.PARENT).appliedTo(
            type = HomeworkType.TODAY,
            stageRange = null,
            deadlineTime = "",
            touched = HomeworkFormTouched(),
        )
        assertEquals("未改过的字段按家长默认值收敛", homeworkFormDefaults(Role.PARENT), untouched)
    }

    @Test
    fun `默认值收敛是幂等的`() {
        val defaults = homeworkFormDefaults(Role.PARENT)
        val once = defaults.appliedTo(HomeworkType.TODAY, null, "", HomeworkFormTouched())
        val twice = defaults.appliedTo(once.type, once.stageRange, once.deadlineTime, HomeworkFormTouched())

        assertEquals(once, twice)
    }

    // ---- 2. 添加页（录入页） ----

    @Test
    fun `家长添加页默认阶段作业且预填一周与21点可直接保存`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val viewModel = entryViewModel(env, FakeHomeworkFileStore(tempDir))

        viewModel.start(studentId)
        advanceUntilIdle()

        assertEquals(listOf(HomeworkType.STAGE), viewModel.uiState.value.availableTypes)
        assertEquals(HomeworkType.STAGE, viewModel.uiState.value.type)
        assertEquals(StageRange.ONE_WEEK, viewModel.uiState.value.stageRange)
        assertEquals(DEFAULT_DAILY_DEADLINE_TEXT, viewModel.uiState.value.deadlineTime)

        viewModel.onContentChange("每天读课文")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertNull("预填值必须可直接通过校验", viewModel.uiState.value.deadlineError)
        assertNull(viewModel.uiState.value.formError)
        val item = env.repository.listHomework(studentId).single()
        assertEquals(HomeworkType.STAGE, item.type)
        assertEquals(StageRange.ONE_WEEK, item.stageRange)
        assertEquals(LocalTime.of(21, 0), item.dailyDeadlineTime)
        assertEquals(CreatorRole.PARENT, item.createdByRole)
    }

    @Test
    fun `家长添加页不得选择当天作业`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val viewModel = entryViewModel(env, FakeHomeworkFileStore(tempDir))
        viewModel.start(studentId)
        advanceUntilIdle()

        viewModel.onTypeChange(HomeworkType.TODAY)

        assertEquals(
            "家长会话只允许选择阶段作业（越界选择被忽略）",
            HomeworkType.STAGE,
            viewModel.uiState.value.type,
        )
    }

    @Test
    fun `学生添加页保持两项且默认当天作业`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.loginAsStudent(parentId, studentId)
        val viewModel = entryViewModel(env, FakeHomeworkFileStore(tempDir))

        viewModel.start(studentId)
        advanceUntilIdle()

        assertEquals(listOf(HomeworkType.TODAY, HomeworkType.STAGE), viewModel.uiState.value.availableTypes)
        assertEquals(HomeworkType.TODAY, viewModel.uiState.value.type)
        assertNull(viewModel.uiState.value.stageRange)
        assertEquals("", viewModel.uiState.value.deadlineTime)

        viewModel.onTypeChange(HomeworkType.STAGE)
        assertEquals("学生仍可录入阶段作业", HomeworkType.STAGE, viewModel.uiState.value.type)
    }

    // ---- 3. 时序：角色异步到位后收敛默认值，但不得覆盖用户已选 ----

    @Test
    fun `角色异步到位后收敛家长默认值`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val viewModel = gatedEntryViewModel(Role.PARENT, gate)

        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        assertNull("会话尚未返回：角色仍未知", viewModel.uiState.value.role)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(Role.PARENT, viewModel.uiState.value.role)
        assertEquals(HomeworkType.STAGE, viewModel.uiState.value.type)
        assertEquals(StageRange.ONE_WEEK, viewModel.uiState.value.stageRange)
        assertEquals(DEFAULT_DAILY_DEADLINE_TEXT, viewModel.uiState.value.deadlineTime)

        // 幂等：重复 start 不再收敛、也不改变已收敛的结果
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        assertEquals(HomeworkType.STAGE, viewModel.uiState.value.type)
        assertEquals(StageRange.ONE_WEEK, viewModel.uiState.value.stageRange)
        assertEquals(DEFAULT_DAILY_DEADLINE_TEXT, viewModel.uiState.value.deadlineTime)
    }

    @Test
    fun `角色异步到位后不覆盖用户已改过的选择`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val viewModel = gatedEntryViewModel(Role.PARENT, gate)

        viewModel.start(STUDENT_ID)
        advanceUntilIdle()

        // 角色未到位时用户已经动过表单（类型 / 阶段范围 / 每日时刻）
        viewModel.onTypeChange(HomeworkType.TODAY)
        viewModel.onStageRangeChange(StageRange.TWO_WEEKS)
        viewModel.onDeadlineTimeChange("20:30")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(Role.PARENT, viewModel.uiState.value.role)
        assertEquals("已改过的类型不得被默认值覆盖", HomeworkType.TODAY, viewModel.uiState.value.type)
        assertEquals("已改过的阶段范围不得被默认值覆盖", StageRange.TWO_WEEKS, viewModel.uiState.value.stageRange)
        assertEquals("已改过的每日时刻不得被默认值覆盖", "20:30", viewModel.uiState.value.deadlineTime)
        assertEquals("但选项仍按家长会话口径收敛", listOf(HomeworkType.STAGE), viewModel.uiState.value.availableTypes)
    }

    // ---- 4. 编辑页（模板页） ----

    @Test
    fun `编辑页家长新建默认阶段作业并预填一周与21点`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val viewModel = templateViewModel(env)

        viewModel.start(studentId)
        advanceUntilIdle()

        assertEquals(listOf(HomeworkType.STAGE), viewModel.uiState.value.availableTypes)
        assertEquals(HomeworkType.STAGE, viewModel.uiState.value.type)
        assertEquals(StageRange.ONE_WEEK, viewModel.uiState.value.stageRange)
        assertEquals("21:00", viewModel.uiState.value.deadlineTime)

        viewModel.onTypeChange(HomeworkType.TODAY)
        assertEquals("编辑页与添加页同口径：越界选择被忽略", HomeworkType.STAGE, viewModel.uiState.value.type)

        viewModel.onContentChange("每天读课文")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.formError)
        val item = env.repository.listHomework(studentId).single()
        assertEquals(HomeworkType.STAGE, item.type)
        assertEquals(StageRange.ONE_WEEK, item.stageRange)
        assertEquals(LocalTime.of(21, 0), item.dailyDeadlineTime)
        assertEquals(env.todayEpochDay(), item.stageStartEpochDay)
    }

    @Test
    fun `编辑页学生新建默认当天作业`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.loginAsStudent(parentId, studentId)
        val viewModel = templateViewModel(env)

        viewModel.start(studentId)
        advanceUntilIdle()

        assertEquals(listOf(HomeworkType.TODAY, HomeworkType.STAGE), viewModel.uiState.value.availableTypes)
        assertEquals(HomeworkType.TODAY, viewModel.uiState.value.type)
        assertNull(viewModel.uiState.value.stageRange)
        assertEquals("", viewModel.uiState.value.deadlineTime)
    }

    @Test
    fun `编辑页家长会话不出现当天作业选项且存量类型原样保留`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val legacy = (
            env.repository.addHomework(
                HomeworkTemplate(
                    content = "今天写生字",
                    type = HomeworkType.TODAY,
                    stageRange = null,
                    deadline = HomeworkDailyDeadlineCodec.instantAt(
                        env.todayEpochDay(),
                        LocalTime.of(18, 0),
                        zone,
                    ),
                    creatorRole = CreatorRole.PARENT,
                    startEpochDay = env.todayEpochDay(),
                    zoneId = zone,
                ),
                studentId,
            ) as AddHomeworkResult.Success
            ).items.single()

        val viewModel = templateViewModel(env)
        viewModel.start(studentId, legacy.id)
        advanceUntilIdle()

        assertEquals(
            "编辑页与添加页同口径：家长会话没有「当天作业」选项",
            listOf(HomeworkType.STAGE),
            viewModel.uiState.value.availableTypes,
        )
        assertEquals(
            "存量当天作业不做数据兼容：既有类型不被擅自改写",
            HomeworkType.TODAY,
            viewModel.uiState.value.type,
        )
    }

    @Test
    fun `编辑页家长编辑既有阶段作业按作业取值回填不套用默认值`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val existing = (
            env.repository.addHomework(
                HomeworkTemplate(
                    content = "每天练字",
                    type = HomeworkType.STAGE,
                    stageRange = StageRange.THREE_WEEKS,
                    deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(20, 30)),
                    creatorRole = CreatorRole.PARENT,
                    startEpochDay = env.todayEpochDay(),
                    zoneId = zone,
                ),
                studentId,
            ) as AddHomeworkResult.Success
            ).items.single()

        val viewModel = templateViewModel(env)
        viewModel.start(studentId, existing.id)
        advanceUntilIdle()

        assertEquals(HomeworkType.STAGE, viewModel.uiState.value.type)
        assertEquals(
            "编辑既有作业不得被家长默认预填覆盖",
            StageRange.THREE_WEEKS,
            viewModel.uiState.value.stageRange,
        )
        assertEquals("编辑既有作业不得被家长默认预填覆盖", "20:30", viewModel.uiState.value.deadlineTime)
        assertEquals(LocalTime.of(20, 30), existing.dailyDeadlineTime)
    }

    // ---- 5. 闪退修复的等价性（Java 9 API → API 26 等价写法） ----

    @Test
    fun `阶段作业落库编码与每日时刻与修复前逐字等价`() {
        val item = stageTemplate(HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(21, 0)))
            .toItems(
                parentAccountId = PARENT_ID,
                studentId = STUDENT_ID,
                createdAt = Instant.ofEpochMilli(HomeworkTestEnv.FIXED_MILLIS),
                firstPriority = 0,
            )
            .single()

        assertEquals(
            "编码结果 = (锚点 1 + 起始日 19700) * 86400000 + 21:00 毫秒，逐字锁定（替换 API 不改落库值）",
            1_702_242_000_000L,
            requireNotNull(item.deadline).toEpochMilli(),
        )
        assertEquals(
            HomeworkDailyDeadlineCodec.encodeStageDaily(START_EPOCH_DAY, LocalTime.of(21, 0)),
            item.deadline,
        )
        assertEquals(LocalTime.of(21, 0), item.dailyDeadlineTime)
        assertEquals(START_EPOCH_DAY, item.stageStartEpochDay)
    }

    @Test
    fun `阶段每日时刻含秒与毫秒时编码精度不丢`() {
        val precise = LocalTime.of(20, 30, 15, 123_000_000)
        val item = stageTemplate(HomeworkDailyDeadlineCodec.timeOfDayCarrier(precise))
            .toItems(
                parentAccountId = PARENT_ID,
                studentId = STUDENT_ID,
                createdAt = Instant.ofEpochMilli(HomeworkTestEnv.FIXED_MILLIS),
                firstPriority = 0,
            )
            .single()

        assertEquals(HomeworkDailyDeadlineCodec.encodeStageDaily(START_EPOCH_DAY, precise), item.deadline)
        assertEquals("钟面值逐位还原（含秒与毫秒）", precise, item.dailyDeadlineTime)
    }

    @Test
    fun `当天作业的自然日折算按业务时区口径`() {
        val millis = LocalDate.of(2023, 11, 15).atTime(0, 30).atZone(SHANGHAI).toInstant().toEpochMilli()

        assertEquals(
            LocalDate.of(2023, 11, 15).toEpochDay(),
            HomeworkDailyDeadlineCodec.absoluteEpochDay(millis, SHANGHAI),
        )
        assertEquals(
            "同一瞬时在 UTC 口径下仍是前一天（证明按传入时区折算，而非 UTC 毫秒除法）",
            LocalDate.of(2023, 11, 14).toEpochDay(),
            HomeworkDailyDeadlineCodec.absoluteEpochDay(millis, UTC),
        )
        assertEquals(
            "与本模块唯一 epochDay 口径一致",
            HomeworkValidators.epochDayOf(millis, SHANGHAI),
            HomeworkDailyDeadlineCodec.absoluteEpochDay(millis, SHANGHAI),
        )
    }

    @Test
    fun `阶段保存路径不得再调用 Java 9 的 java_time API`() {
        val templateCode = codeText(mainFile("domain/HomeworkTemplate.kt"))
        assertFalse(
            "阶段每日时刻还原不得再用 LocalTime.ofInstant（Java 9 / Android API 31，minSdk 29 上抛 NoSuchMethodError）",
            JAVA9_LOCAL_TIME_OF_INSTANT.containsMatchIn(templateCode),
        )
        assertTrue(
            "应改为 API 26 即可用的等价写法（UTC 钟面值）",
            templateCode.contains("daily.atZone(ZoneOffset.UTC).toLocalTime()"),
        )

        val codecCode = codeText(mainFile("domain/HomeworkDailyDeadlineCodec.kt"))
        assertFalse(
            "当天作业归属日折算不得再用 LocalDate.ofInstant（同为 Java 9 / API 31 API）",
            JAVA9_LOCAL_DATE_OF_INSTANT.containsMatchIn(codecCode),
        )
        assertTrue(
            "应改为 API 26 即可用的等价写法（业务时区自然日）",
            codecCode.contains("atZone(zoneId).toLocalDate().toEpochDay()"),
        )

        val offenders = homeworkMainSources()
            .filter { file -> JAVA9_TIME_API.containsMatchIn(codeText(file)) }
            .map { it.name }
            .sorted()
        assertEquals(
            "homework 生产代码整体不得再引入 Java 9+ 的 java.time API（minSdk 29 且未启用 desugaring）",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `两个录入页的类型选项都取自 availableTypes`() {
        listOf("ui/HomeworkEntryScreen.kt", "ui/HomeworkTemplateScreen.kt").forEach { path ->
            val code = codeText(mainFile(path))
            assertTrue("$path 的类型选项必须取 availableTypes", code.contains("uiState.availableTypes.forEach"))
            assertFalse(
                "$path 不得再遍历全部枚举（家长会话会漏出「当天作业」）",
                code.contains("HomeworkType.entries.forEach"),
            )
        }
    }

    // ---- 测试工具 ----

    private fun entryViewModel(
        env: HomeworkTestEnv,
        fileStore: FakeHomeworkFileStore,
    ): HomeworkEntryViewModel = HomeworkEntryViewModel(
        homeworkRepository = env.repository,
        authRepository = env.authRepository,
        ocrHandler = ocrHandler(fileStore, env.clock),
        speechToText = mockk(relaxed = true),
        fileStore = fileStore,
        clock = env.clock,
        zoneId = zone,
    )

    /** 会话返回被 [gate] 挂起的录入页 ViewModel：用于精确构造「角色异步到位」的时序 */
    private fun gatedEntryViewModel(role: Role, gate: CompletableDeferred<Unit>): HomeworkEntryViewModel {
        val fileStore = FakeHomeworkFileStore(tempDir)
        val authRepository = mockk<AuthRepository>(relaxed = true)
        coEvery { authRepository.currentSession() } coAnswers {
            gate.await()
            sessionOf(role)
        }
        return HomeworkEntryViewModel(
            homeworkRepository = mockk(relaxed = true),
            authRepository = authRepository,
            ocrHandler = ocrHandler(fileStore, FixedClock()),
            speechToText = mockk(relaxed = true),
            fileStore = fileStore,
            clock = FixedClock(),
            zoneId = zone,
        )
    }

    private fun ocrHandler(fileStore: FakeHomeworkFileStore, clock: Clock): HomeworkOcrHandler =
        HomeworkOcrHandler(
            ocrRecognizer = mockk<OcrRecognizer>(relaxed = true),
            ocrConfigStore = FakeOcrConfigStore(OcrConfig()),
            pendingOcrRepository = FakePendingOcrRepository(),
            fileStore = fileStore,
            clock = clock,
        )

    private fun templateViewModel(env: HomeworkTestEnv): HomeworkTemplateViewModel =
        HomeworkTemplateViewModel(env.repository, env.authRepository, env.clock, zone)

    private fun stageTemplate(deadline: Instant?): HomeworkTemplate = HomeworkTemplate(
        content = "每天读课文",
        type = HomeworkType.STAGE,
        stageRange = StageRange.ONE_WEEK,
        deadline = deadline,
        creatorRole = CreatorRole.PARENT,
        startEpochDay = START_EPOCH_DAY,
        zoneId = zone,
    )

    private fun sessionOf(role: Role): SessionState = when (role) {
        Role.PARENT -> SessionState(role = Role.PARENT, parentId = PARENT_ID, studentId = null)
        Role.STUDENT -> SessionState(role = Role.STUDENT, parentId = PARENT_ID, studentId = STUDENT_ID)
    }

    private fun HomeworkTestEnv.todayEpochDay(): Long =
        HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zone)

    private fun homeworkMainSources(): List<File> {
        val dir = File(repoRoot(), HOMEWORK_MAIN_ROOT)
        assertTrue("homework 主源码目录不存在: ${dir.absolutePath}", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun mainFile(relativePath: String): File {
        val file = File(repoRoot(), "$HOMEWORK_MAIN_ROOT/$relativePath")
        check(file.isFile) { IOException("源文件不存在: ${file.absolutePath}") }
        return file
    }

    /** 去掉块注释与行注释后的代码文本：KDoc 里「不得用 xxx」的说明字样不得参与结构校验 */
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

    /** 固定时钟（业务时区 2023-11-15 附近，与 [HomeworkTestEnv] 默认值一致） */
    private class FixedClock : Clock {
        override fun currentTimeMillis(): Long = HomeworkTestEnv.FIXED_MILLIS
    }

    private companion object Constants {

        const val HOMEWORK_MAIN_ROOT = "app/src/main/java/com/assignmate/app/homework"

        const val PARENT_ID = 1L
        const val STUDENT_ID = 1L

        /** 阶段编码用例的起始日（远离真实数据，便于锁定编码算式） */
        const val START_EPOCH_DAY = 19_700L

        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
        val UTC: ZoneId = ZoneId.of("UTC")

        /** Java 9 / Android API 31 的 java.time API（本工程 minSdk 29 上会抛 NoSuchMethodError） */
        val JAVA9_LOCAL_TIME_OF_INSTANT = Regex("\\bLocalTime\\.ofInstant\\(")
        val JAVA9_LOCAL_DATE_OF_INSTANT = Regex("\\bLocalDate\\.ofInstant\\(")
        val JAVA9_TIME_API = Regex("\\bLocalTime\\.ofInstant\\(|\\bLocalDate\\.ofInstant\\(")
    }
}