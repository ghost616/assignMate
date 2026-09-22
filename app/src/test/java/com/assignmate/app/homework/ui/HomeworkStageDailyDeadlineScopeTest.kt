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
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkTestEnv
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.HomeworkValidationError
import com.assignmate.app.homework.domain.StageRange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * 「阶段作业每日截止时刻是否必填」的 UI / 仓库口径一致性单测（需求 #6 一.5）。
 *
 * 仓库规则（[com.assignmate.app.homework.domain.HomeworkValidators.validateTemplate] /
 * `validateTypeChange`）：**家长录入**的阶段作业必须设每日截止时刻，**学生录入**的可留空。
 * 修复前两个录入页对**所有角色**都强制必填——学生侧因此被 UI 先拦下（录入页更因
 * `deadlineEditable = role == PARENT` 根本不渲染该输入框，导致学生永远提交不了阶段作业），
 * 而仓库本会放行。本类把两侧口径锁在一起：
 *
 * 1. 学生新建阶段作业（留空每日时刻）→ 提交成功且落库无每日时刻约束；
 * 2. 家长新建阶段作业（留空）→ 本地拦下且不落库（文案与仓库 MISSING_STAGE_DEADLINE 同源）；
 * 3. 家长编辑**学生录入**的阶段作业（留空）→ 允许（必填口径以**作业录入者**为准）；
 * 4. 家长编辑**家长录入**的阶段作业（清空）→ 拦下且原每日时刻不变；
 * 5. 提示文案随录入者角色区分「必填 / 可留空」；
 * 6. 录入页（四种方式共用入口）同样口径：学生可留空保存、家长留空被拦。
 *
 * 注：家长阶段作业表单现已**预填**「一周 + 21:00」默认值（需求：降低必填项操作成本），
 * 因此家长侧的「留空」用例需先经 `onDeadlineTimeChange("")` 清空预填值，再验证必填口径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkStageDailyDeadlineScopeTest {

    private lateinit var tempDir: File

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempDir = Files.createTempDirectory("homework-stage-deadline-scope").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    // ---- 1~4. 模板页（新建 / 编辑） ----

    @Test
    fun `学生新建阶段作业留空每日时刻可保存且落库无每日时刻`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        env.loginAsStudent(parentId, studentId)
        val viewModel = templateViewModel(env)

        viewModel.start(studentId)
        advanceUntilIdle()
        viewModel.onContentChange("每天读课文")
        viewModel.onTypeChange(HomeworkType.STAGE)
        viewModel.onStageRangeChange(StageRange.ONE_WEEK)
        viewModel.onSubmit()
        advanceUntilIdle()

        assertNull("学生录入阶段作业不得再被 UI 先拦下", viewModel.uiState.value.deadlineError)
        assertNull(viewModel.uiState.value.formError)
        val item = env.repository.listHomework(studentId).single()
        assertEquals(HomeworkType.STAGE, item.type)
        assertEquals(CreatorRole.STUDENT, item.createdByRole)
        assertNull("留空 → 无每日截止时刻约束", item.dailyDeadlineTime)
        assertNull("无每日时刻时 deadline 列不承载编码值", item.stageStartEpochDay)
        assertEquals(
            "阶段起始日回退创建日（口径与仓库一致）",
            env.todayEpochDay(),
            item.stageStartEpochDayOr(zone),
        )
    }

    @Test
    fun `家长新建阶段作业留空每日时刻被本地拦下且不落库`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val viewModel = templateViewModel(env)

        viewModel.start(studentId)
        advanceUntilIdle()
        viewModel.onContentChange("每天读课文")
        viewModel.onTypeChange(HomeworkType.STAGE)
        viewModel.onStageRangeChange(StageRange.ONE_WEEK)
        // 家长阶段作业表单预填了「21:00」，本用例验证的是**留空**时的必填口径，故先显式清空
        viewModel.onDeadlineTimeChange("")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(
            "家长录入的阶段作业必填每日时刻（文案与仓库 MISSING_STAGE_DEADLINE 同源）",
            HomeworkValidationError.MISSING_STAGE_DEADLINE.toUserMessage(),
            viewModel.uiState.value.deadlineError,
        )
        assertTrue("被拦下不得落库", env.repository.listHomework(studentId).isEmpty())
    }

    @Test
    fun `家长编辑学生录入的阶段作业留空每日时刻仍可保存`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        // 学生先录入一条「无每日时刻」的阶段作业
        env.loginAsStudent(parentId, studentId)
        val item = (
            env.repository.addHomework(
                HomeworkTemplate(
                    content = "每天读课文",
                    type = HomeworkType.STAGE,
                    stageRange = StageRange.ONE_WEEK,
                    deadline = null,
                    creatorRole = CreatorRole.STUDENT,
                    startEpochDay = env.todayEpochDay(),
                    zoneId = zone,
                ),
                studentId,
            ) as AddHomeworkResult.Success
            ).items.single()
        // 切回家长会话编辑该作业
        env.authRepository.loginParent(HomeworkTestEnv.ACCOUNT, HomeworkTestEnv.PASSWORD)
        val viewModel = templateViewModel(env)

        viewModel.start(studentId, item.id)
        advanceUntilIdle()
        assertEquals("必填口径以作业录入者为准", CreatorRole.STUDENT, viewModel.uiState.value.ownerRole)
        assertFalse(viewModel.uiState.value.requiresDailyDeadline)
        viewModel.onSubmit()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.deadlineError)
        assertNull(viewModel.uiState.value.formError)
        val stored = env.repository.getHomework(item.id)!!
        assertEquals(HomeworkType.STAGE, stored.type)
        assertNull("仍无每日时刻约束", stored.dailyDeadlineTime)
    }

    @Test
    fun `家长编辑家长录入的阶段作业清空每日时刻被拦下且原值不变`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val item = (
            env.repository.addHomework(
                HomeworkTemplate(
                    content = "每天读课文",
                    type = HomeworkType.STAGE,
                    stageRange = StageRange.ONE_WEEK,
                    deadline = HomeworkDailyDeadlineCodec.timeOfDayCarrier(LocalTime.of(20, 30)),
                    creatorRole = CreatorRole.PARENT,
                    startEpochDay = env.todayEpochDay(),
                    zoneId = zone,
                ),
                studentId,
            ) as AddHomeworkResult.Success
            ).items.single()
        val viewModel = templateViewModel(env)

        viewModel.start(studentId, item.id)
        advanceUntilIdle()
        assertEquals(CreatorRole.PARENT, viewModel.uiState.value.ownerRole)
        assertTrue(viewModel.uiState.value.requiresDailyDeadline)

        viewModel.onDeadlineTimeChange("")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(
            HomeworkValidationError.MISSING_STAGE_DEADLINE.toUserMessage(),
            viewModel.uiState.value.deadlineError,
        )
        assertEquals(
            "被拦下 → 原每日时刻保持不变",
            LocalTime.of(20, 30),
            env.repository.getHomework(item.id)!!.dailyDeadlineTime,
        )
    }

    // ---- 5. 文案随录入者角色区分 ----

    @Test
    fun `阶段每日时刻提示文案随录入者角色区分必填与可留空`() {
        assertEquals("阶段作业在阶段范围内每天到这个时刻截止", stageDailyDeadlineHint(CreatorRole.PARENT))
        assertEquals(
            "阶段作业在阶段范围内每天到这个时刻截止（学生录入可留空，留空则不限时刻）",
            stageDailyDeadlineHint(CreatorRole.STUDENT),
        )
        assertEquals(
            "录入者未知时按可留空提示（不误报必填）",
            stageDailyDeadlineHint(CreatorRole.STUDENT),
            stageDailyDeadlineHint(null),
        )
    }

    // ---- 6. 录入页（OCR/语音/手动共用入口）同口径 ----

    @Test
    fun `录入页学生阶段作业留空可保存而家长留空被拦`() = runTest {
        // 学生：录入页不渲染每日时刻输入（deadlineEditable 仅家长）→ 留空提交必须成功
        val studentRepository = mockk<HomeworkRepository>(relaxed = true)
        val saved = mutableListOf<HomeworkTemplate>()
        coEvery { studentRepository.addHomework(capture(saved), any()) } returns
            AddHomeworkResult.Success(emptyList())
        val studentViewModel = entryViewModel(Role.STUDENT, studentRepository)
        studentViewModel.start(STUDENT_ID)
        advanceUntilIdle()
        studentViewModel.onContentChange("每天读课文")
        studentViewModel.onTypeChange(HomeworkType.STAGE)
        studentViewModel.onStageRangeChange(StageRange.ONE_WEEK)
        studentViewModel.onSubmit()
        advanceUntilIdle()

        assertNull("修复前：学生录入阶段作业永远提交不了", studentViewModel.uiState.value.deadlineError)
        assertEquals(1, saved.size)
        assertEquals(HomeworkType.STAGE, saved.single().type)
        assertNull("学生录入可留空每日截止时刻", saved.single().deadline)
        assertEquals(CreatorRole.STUDENT, saved.single().creatorRole)

        // 家长：预填的「21:00」先清空——本用例验证的是**留空**必须被本地拦下且不触达仓库
        val parentRepository = mockk<HomeworkRepository>(relaxed = true)
        val parentViewModel = entryViewModel(Role.PARENT, parentRepository)
        parentViewModel.start(STUDENT_ID)
        advanceUntilIdle()
        parentViewModel.onContentChange("每天读课文")
        parentViewModel.onTypeChange(HomeworkType.STAGE)
        parentViewModel.onStageRangeChange(StageRange.ONE_WEEK)
        parentViewModel.onDeadlineTimeChange("")
        parentViewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(
            HomeworkValidationError.MISSING_STAGE_DEADLINE.toUserMessage(),
            parentViewModel.uiState.value.deadlineError,
        )
        coVerify(exactly = 0) { parentRepository.addHomework(any(), any()) }
    }

    // ---- 测试工具 ----

    private fun templateViewModel(env: HomeworkTestEnv): HomeworkTemplateViewModel =
        HomeworkTemplateViewModel(env.repository, env.authRepository, env.clock, zone)

    private fun entryViewModel(role: Role, repository: HomeworkRepository): HomeworkEntryViewModel {
        val fileStore = FakeHomeworkFileStore(tempDir)
        return HomeworkEntryViewModel(
            homeworkRepository = repository,
            authRepository = mockk<AuthRepository>(relaxed = true).apply {
                coEvery { currentSession() } returns sessionOf(role)
            },
            ocrHandler = HomeworkOcrHandler(
                ocrRecognizer = mockk<OcrRecognizer>(relaxed = true),
                ocrConfigStore = FakeOcrConfigStore(OcrConfig()),
                pendingOcrRepository = FakePendingOcrRepository(),
                fileStore = fileStore,
                clock = FixedClock(),
            ),
            speechToText = mockk<SpeechToText>(relaxed = true),
            fileStore = fileStore,
            clock = FixedClock(),
            zoneId = zone,
        )
    }

    private fun sessionOf(role: Role): SessionState = when (role) {
        Role.PARENT -> SessionState(role = Role.PARENT, parentId = PARENT_ID, studentId = null)
        Role.STUDENT -> SessionState(role = Role.STUDENT, parentId = PARENT_ID, studentId = STUDENT_ID)
    }

    private fun HomeworkTestEnv.todayEpochDay(): Long =
        com.assignmate.app.homework.domain.HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zone)

    /** 固定时钟（业务时区 2023-11-15 附近，与 HomeworkTestEnv 默认值一致） */
    private class FixedClock : Clock {
        override fun currentTimeMillis(): Long = HomeworkTestEnv.FIXED_MILLIS
    }

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 1L
    }
}
