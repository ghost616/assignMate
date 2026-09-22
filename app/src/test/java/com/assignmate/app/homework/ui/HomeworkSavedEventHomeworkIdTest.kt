package com.assignmate.app.homework.ui

import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrRecognizer
import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.FakeHomeworkFileStore
import com.assignmate.app.homework.data.FakeOcrConfigStore
import com.assignmate.app.homework.data.FakePendingOcrRepository
import com.assignmate.app.homework.data.HomeworkTestEnv
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「保存成功事件回抛新 homeworkId」单测（对照风后开发计划第二条）。
 *
 * 目的：framework 对「新建作业 + 学生会话未指定学生」这一分支此前只能靠「按整份清单纠正」兜底，
 * 因为录入/模板页的保存成功事件不携带新生成的作业 id。本类锁定两个入口的**真实 id 回抛**：
 *
 * 1. 录入页新建**阶段作业** → `HomeworkEntryEvent.Saved.homeworkId` = 那一条作业项的真实主键；
 * 2. 录入页（**学生会话**）新建**当天作业** → 同上（非占位 id）；
 *    注：家长会话只能录入阶段作业（默认收敛为阶段作业），故「当天作业」用例由学生端承担。
 * 3. 模板页新建作业 → `HomeworkTemplateEvent.SavedWithMessage.homeworkId` = 新作业项真实主键；
 * 4. 模板页编辑既有作业 → `HomeworkTemplateEvent.Saved.homeworkId` = 被编辑作业 id；
 * 5. 接线：两个 Route 的 `onSaved(Long)` 形参由事件 id 透传（framework 据此精确同步提醒）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkSavedEventHomeworkIdTest {

    private lateinit var tempDir: File

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempDir = Files.createTempDirectory("homework-saved-event-id").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    // ---- 1~2. 录入页 ----

    @Test
    fun `录入页新建阶段作业保存成功事件携带真实作业 id`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val viewModel = entryViewModel(env)
        val events = mutableListOf<HomeworkEntryEvent>()
        val job = launch { viewModel.events.collect { events += it } }

        viewModel.start(studentId)
        advanceUntilIdle()
        viewModel.onContentChange("每天读课文")
        viewModel.onTypeChange(HomeworkType.STAGE)
        viewModel.onStageRangeChange(StageRange.ONE_WEEK)
        // 家长录入的阶段作业必须设每日截止时刻（否则被本地拦下，不会发保存事件）
        viewModel.onDeadlineTimeChange("21:00")
        viewModel.onSubmit()
        advanceUntilIdle()
        job.cancel()

        val saved = events.filterIsInstance<HomeworkEntryEvent.Saved>().single()
        val stored = env.repository.listHomework(studentId).single()
        assertEquals(HomeworkType.STAGE, stored.type)
        assertTrue("事件必须携带真实主键（非占位 id），实际：${saved.homeworkId}", saved.homeworkId > 0L)
        assertEquals("阶段作业即那一条作业项的 id", stored.id, saved.homeworkId)
        assertEquals("已添加 1 项作业", saved.message)
    }

    @Test
    fun `录入页新建当天作业保存成功事件携带真实作业 id`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        // 家长会话已不再能创建「当天作业」（默认收敛为阶段作业），本用例改由学生会话承担：
        // 学生端保持「默认当天作业」的既有行为，事件仍须携带新建作业项的真实 id
        env.loginAsStudent(parentId, studentId)
        val viewModel = entryViewModel(env)
        val events = mutableListOf<HomeworkEntryEvent>()
        val job = launch { viewModel.events.collect { events += it } }

        viewModel.start(studentId)
        advanceUntilIdle()
        viewModel.onContentChange("数学练习册")
        viewModel.onSubmit()
        advanceUntilIdle()
        job.cancel()

        val saved = events.filterIsInstance<HomeworkEntryEvent.Saved>().single()
        val stored = env.repository.listHomework(studentId).single()
        assertEquals(HomeworkType.TODAY, stored.type)
        assertTrue("事件必须携带真实主键（非占位 id），实际：${saved.homeworkId}", saved.homeworkId > 0L)
        assertEquals(stored.id, saved.homeworkId)
    }

    // ---- 3~4. 模板页（新建 / 编辑） ----

    @Test
    fun `模板页新建作业保存成功事件携带真实作业 id`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val viewModel = templateViewModel(env)
        val events = mutableListOf<HomeworkTemplateEvent>()
        val job = launch { viewModel.events.collect { events += it } }

        viewModel.start(studentId)
        advanceUntilIdle()
        viewModel.onContentChange("数学练习册")
        viewModel.onSubmit()
        advanceUntilIdle()
        job.cancel()

        val saved = events.filterIsInstance<HomeworkTemplateEvent.SavedWithMessage>().single()
        val stored = env.repository.listHomework(studentId).single()
        assertTrue("新建事件必须携带真实主键（非占位 id），实际：${saved.homeworkId}", saved.homeworkId > 0L)
        assertEquals(stored.id, saved.homeworkId)
        assertEquals("已添加 1 项作业", saved.message)
    }

    @Test
    fun `模板页编辑既有作业保存成功事件携带被编辑作业 id`() = runTest {
        val env = HomeworkTestEnv()
        val parentId = env.loginAsParent()
        val studentId = env.addStudent(parentId)
        val stored = (
            env.repository.addHomework(
                HomeworkTemplate(
                    content = "数学练习册",
                    type = HomeworkType.TODAY,
                    creatorRole = CreatorRole.PARENT,
                    startEpochDay = env.todayEpochDay(zone),
                    zoneId = zone,
                ),
                studentId,
            ) as AddHomeworkResult.Success
            ).items.single()
        val viewModel = templateViewModel(env)
        val events = mutableListOf<HomeworkTemplateEvent>()
        val job = launch { viewModel.events.collect { events += it } }

        viewModel.start(studentId, stored.id)
        advanceUntilIdle()
        viewModel.onContentChange("数学练习册（修订）")
        viewModel.onSubmit()
        advanceUntilIdle()
        job.cancel()

        val saved = events.filterIsInstance<HomeworkTemplateEvent.Saved>().single()
        assertEquals("编辑路径回抛被编辑作业 id", stored.id, saved.homeworkId)
        assertEquals("内容已按编辑路径落库", "数学练习册（修订）", env.repository.getHomework(stored.id)!!.content)
    }

    // ---- 5. 接线：Route 把事件 id 透传给 onSaved ----

    @Test
    fun `两个录入页 Route 都把事件中的 homeworkId 透传给 onSaved`() {
        val entrySource = readMainSource("ui/HomeworkEntryScreen.kt")
        assertTrue(
            "录入页 Route 的 onSaved 必须接收作业 id（framework 据此精确同步提醒）",
            entrySource.contains("onSaved: (Long) -> Unit"),
        )
        assertTrue(
            "录入页 Route 的 Saved 分支必须把事件 id 透传给 onSaved",
            entrySource.contains("onSaved(event.homeworkId)"),
        )

        val templateSource = readMainSource("ui/HomeworkTemplateScreen.kt")
        assertTrue(
            "模板页 Route 的 onSaved 必须接收作业 id",
            templateSource.contains("onSaved: (Long) -> Unit"),
        )
        assertEquals(
            "模板页 Saved / SavedWithMessage 两个分支都要透传事件 id",
            2,
            Regex("onSaved\\(event\\.homeworkId\\)").findAll(templateSource).count(),
        )

        val entryViewModelSource = readMainSource("ui/HomeworkEntryViewModel.kt")
        assertTrue(
            "录入页保存事件必须携带 homeworkId（无默认值，编译期强制回抛）",
            entryViewModelSource.contains("data class Saved(val message: String, val homeworkId: Long)"),
        )
        val templateViewModelSource = readMainSource("ui/HomeworkTemplateViewModel.kt")
        assertTrue(
            "模板页编辑事件必须携带 homeworkId",
            templateViewModelSource.contains("data class Saved(val homeworkId: Long)"),
        )
        assertTrue(
            "模板页新建事件必须携带 homeworkId",
            templateViewModelSource.contains("data class SavedWithMessage(val message: String, val homeworkId: Long)"),
        )
    }

    // ---- 测试工具 ----

    private fun entryViewModel(env: HomeworkTestEnv): HomeworkEntryViewModel {
        val fileStore = FakeHomeworkFileStore(tempDir)
        return HomeworkEntryViewModel(
            homeworkRepository = env.repository,
            authRepository = env.authRepository,
            ocrHandler = HomeworkOcrHandler(
                ocrRecognizer = mockk<OcrRecognizer>(relaxed = true),
                ocrConfigStore = FakeOcrConfigStore(OcrConfig()),
                pendingOcrRepository = FakePendingOcrRepository(),
                fileStore = fileStore,
                clock = env.clock,
            ),
            speechToText = mockk<SpeechToText>(relaxed = true),
            fileStore = fileStore,
            clock = env.clock,
            zoneId = zone,
        )
    }

    private fun templateViewModel(env: HomeworkTestEnv): HomeworkTemplateViewModel =
        HomeworkTemplateViewModel(env.repository, env.authRepository, env.clock, zone)

    private fun HomeworkTestEnv.todayEpochDay(zoneId: ZoneId): Long =
        com.assignmate.app.homework.domain.HomeworkValidators.epochDayOf(clock.currentTimeMillis(), zoneId)

    private fun readMainSource(relativePath: String): String {
        val file = File(repoRoot(), "$HOMEWORK_MAIN_ROOT/$relativePath")
        check(file.isFile) { IOException("源文件不存在: ${file.absolutePath}") }
        return file.readText()
    }

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
    }
}
