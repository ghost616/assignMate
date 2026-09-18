package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrResult
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.FakeAuthRepository
import com.assignmate.app.homework.data.FakeHomeworkFileStore
import com.assignmate.app.homework.data.FakeOcrConfigStore
import com.assignmate.app.homework.data.FakeOcrRecognizer
import com.assignmate.app.homework.data.FakePendingOcrRepository
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkOrderResult
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.data.ReorderDirection
import com.assignmate.app.homework.data.ScheduleUpdateResult
import com.assignmate.app.homework.domain.HomeworkConstants
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 复测轮回归探针（离朱）：把上一轮两条红探针改写为当前契约下的应然断言，
 * 并补齐测试说明「建议复测重点」要求的反向与边界覆盖。
 *
 * A. 学生 + 未配置：不再重复写 formError（该提示由就地卡片承担），渲染文案（[ocrNoticeFor]）
 *    不得含任何指向设置页的字样、须保留「图片已保存待重试」，且无动作按钮；
 * B. 家长 + 未配置：正文逐字等于既有原文、「去设置」只作 actionLabel（不混入正文、不声称点击）；
 * C. role = null + 未配置：沿用底层原文、无动作、不额外引导，且不写 formError；
 * D. 未配置反复重试的边界：MAX+1 / MAX+5 次后任务与图片仍在、计数恒为 0；
 *    MAX-1 起点任务同样不被清理；配置补齐后可立即重试成功并清理任务与图片；
 * E. 反向：NetworkError 重试不误报 needsConfiguration，且仍按原上限清理。
 *
 * 说明：Compose 渲染分支无法在 JVM 断言（本仓库无设备/未引入 Robolectric），
 * 故以「ViewModel 状态 + 渲染入口 + Handler 副作用」覆盖渲染分支的输入契约。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkEntryOcrRoleNoticeProbeTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock = MutableClock(1_700_000_000_000L)

    private lateinit var tempDir: File
    private lateinit var fileStore: FakeHomeworkFileStore
    private lateinit var recognizer: FakeOcrRecognizer
    private lateinit var pendingRepository: FakePendingOcrRepository
    private lateinit var configStore: FakeOcrConfigStore

    private val config = OcrConfig(
        apiBaseUrl = "https://example.com/v1",
        modelName = "test-model",
        apiKey = "secret",
        enabled = true,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("homework-entry-ocr-role-probe").toFile()
        fileStore = FakeHomeworkFileStore(tempDir)
        recognizer = FakeOcrRecognizer()
        pendingRepository = FakePendingOcrRepository()
        configStore = FakeOcrConfigStore(config)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }
    // ---- A. 学生侧：未配置提示只由就地卡片承担（零设置页字样） ----

    @Test
    fun `学生会话未配置时不再重复写内联错误且渲染文案零设置页引导`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        // 未配置提示由就地卡片承担（正文 = 渲染入口产物），不再重复写 formError（避免同屏两处提示）
        assertNull("未配置场景不写表单内联错误", viewModel.uiState.value.formError)
        val rendered = ocrSetupGuidance(Role.STUDENT, hasSettingsEntry = true)
            .notice(EXPECTED_NOT_CONFIGURED_MESSAGE)
        assertEquals("卡片正文 = 渲染入口产物（无汇总信息即本常量）", OCR_PARENT_SETUP_NOTICE, rendered.message)
        assertTrue(
            "学生侧渲染文案不得含指向设置页的字样，实际：${rendered.message}",
            STUDENT_FORBIDDEN_MARKERS.none { rendered.message.contains(it) },
        )
        assertTrue(
            "学生侧渲染文案仍须保留「图片已保存待重试」事实，实际：${rendered.message}",
            rendered.message.contains("图片已保存待重试"),
        )
        assertNull("学生侧无动作按钮", rendered.actionLabel)
    }

    // ---- B. 家长侧：既有原文不回归（「去设置」只作为 Snackbar 动作） ----

    @Test
    fun `家长会话未配置时渲染正文为既有原文且不夹带按钮说明`() = runTest {
        val viewModel = entryViewModel(Role.PARENT)
        viewModel.start(PARENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        assertNull("未配置提示由带动作的 Snackbar 承担，不再重复写内联错误", viewModel.uiState.value.formError)

        val notice = ocrSetupGuidance(Role.PARENT, hasSettingsEntry = true)
            .notice(EXPECTED_NOT_CONFIGURED_MESSAGE)
        assertEquals("家长侧正文逐字保持既有原文", EXPECTED_PARENT_NOT_CONFIGURED_NOTICE, notice.message)
        assertEquals("家长侧正文 = 底层原文", EXPECTED_NOT_CONFIGURED_MESSAGE, notice.message)
        assertFalse(
            "「去设置」只走 Snackbar actionLabel，不得混入正文，实际：${notice.message}",
            notice.message.contains(OCR_SETTINGS_ACTION_LABEL),
        )
        assertFalse("正文不得声称可点击", notice.message.contains("点击"))
        assertTrue("家长文案须保留待重试事实", notice.message.contains("图片已保存待重试"))
        assertEquals("家长侧动作 = 去设置", OCR_SETTINGS_ACTION_LABEL, notice.actionLabel)
        assertNotEquals(
            "家长侧文案不得被学生侧收敛文案覆盖",
            OCR_PARENT_SETUP_NOTICE,
            notice.message,
        )
    }

    // ---- C. 会话失效（role = null）：沿用原文 ----

    @Test
    fun `会话失效时渲染原文不额外引导且不写内联错误`() = runTest {
        val viewModel = entryViewModel(role = null)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        assertNull("未配置场景不写表单内联错误", viewModel.uiState.value.formError)
        val notice = ocrSetupGuidance(role = null, hasSettingsEntry = true)
            .notice(EXPECTED_NOT_CONFIGURED_MESSAGE)
        assertEquals("role = null 沿用底层识别原文、不追加角色引导", EXPECTED_NOT_CONFIGURED_MESSAGE, notice.message)
        assertNull("会话失效无动作", notice.actionLabel)
    }

    // ---- D. 未配置重试的边界：MAX+1 / MAX+5 ----

    @Test
    fun `未配置重试 MAX 加 1 次后任务图片与计数均不变化`() = runTest {
        val handler = handler()
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        handler.recognizeLocalImage(path)
        val taskId = pendingRepository.all().single().id

        val attempts = HomeworkConstants.MAX_OCR_RETRY_COUNT + 1
        repeat(attempts) { index ->
            recognizer.enqueue(OcrResult.Failure.NotConfigured())
            val summary = handler.retryPendingTasks()
            assertTrue("每次重试都应上抛未配置事实（第 ${index + 1} 次）", summary.needsConfiguration)
        }

        val task = pendingRepository.all().single()
        assertEquals("任务不得被清理", taskId, task.id)
        assertEquals(PendingOcrStatus.PENDING, task.status)
        assertEquals("未配置始终不累加计数（MAX+1 后）", 0, task.retryCount)
        assertTrue("图片不得被清理（MAX+1 后）", File(task.localImagePath).isFile)
    }

    @Test
    fun `未配置重试 MAX 加 5 次后任务图片仍保留且计数为 0`() = runTest {
        val handler = handler()
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        handler.recognizeLocalImage(path)
        val taskId = pendingRepository.all().single().id

        val attempts = HomeworkConstants.MAX_OCR_RETRY_COUNT + 5
        repeat(attempts) { recognizer.enqueue(OcrResult.Failure.NotConfigured()) }
        repeat(attempts) { handler.retryPendingTasks() }

        val remaining = pendingRepository.all()
        assertEquals("任务不得被清理（MAX+5 后，实际=${remaining.size}）", 1, remaining.size)
        assertEquals(taskId, remaining.single().id)
        assertEquals(PendingOcrStatus.PENDING, remaining.single().status)
        assertEquals("未配置始终不累加计数（MAX+5 后）", 0, remaining.single().retryCount)
        assertTrue("图片不得被清理（MAX+5 后）", File(remaining.single().localImagePath).isFile)
        assertEquals("不得残留额外文件", 1, fileStore.fileCount())
    }

    @Test
    fun `计数已达 MAX 减 1 的任务撞上未配置后仍不被清理`() = runTest {
        val handler = handler()
        val path = localImage()
        pendingRepository.add(
            PendingOcrTask(
                localImagePath = path,
                createdAtMillis = clock.currentTimeMillis(),
                status = PendingOcrStatus.PENDING,
                retryCount = HomeworkConstants.MAX_OCR_RETRY_COUNT - 1,
            ),
        )

        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        val summary = handler.retryPendingTasks()

        assertTrue(summary.needsConfiguration)
        val task = pendingRepository.all().single()
        assertEquals(
            "已接近上限的任务撞上未配置时计数不得再增长（否则下一步即触顶清理）",
            HomeworkConstants.MAX_OCR_RETRY_COUNT - 1,
            task.retryCount,
        )
        assertEquals(PendingOcrStatus.PENDING, task.status)
        assertTrue("图片仍在", File(task.localImagePath).isFile)
    }

    @Test
    fun `未配置反复重试后配置补齐可立即重试成功并清理任务与图片`() = runTest {
        val handler = handler()
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        handler.recognizeLocalImage(path)
        val task = pendingRepository.all().single()

        repeat(HomeworkConstants.MAX_OCR_RETRY_COUNT + 2) {
            recognizer.enqueue(OcrResult.Failure.NotConfigured())
            handler.retryPendingTasks()
        }

        configStore.value = config.copy(apiKey = "configured")
        recognizer.enqueue(OcrResult.Success(text = "配置补齐后识别成功"))
        val summary = handler.retryPendingTasks()

        assertEquals(1, summary.attempted)
        assertEquals(1, summary.succeeded)
        assertEquals("配置补齐后识别成功", summary.firstText)
        assertFalse("成功重试后不再报告未配置", summary.needsConfiguration)
        assertTrue("任务应被清理", pendingRepository.all().isEmpty())
        assertFalse("图片应被清理", File(task.localImagePath).exists())
        assertEquals(0, fileStore.fileCount())
    }

    // ---- E. 反向：其他失败类型不误报未配置 ----

    @Test
    fun `网络失败反复重试不误报未配置且仍按上限清理`() = runTest {
        val handler = handler()
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        handler.recognizeLocalImage(path)

        val attempts = HomeworkConstants.MAX_OCR_RETRY_COUNT
        repeat(attempts) { index ->
            recognizer.enqueue(OcrResult.Failure.NetworkError())
            val summary = handler.retryPendingTasks()
            assertFalse("网络失败不得被当成未配置（第 ${index + 1} 次）", summary.needsConfiguration)
        }

        val task = pendingRepository.all().single()
        assertEquals("网络失败按原口径累加计数", attempts, task.retryCount)
        assertEquals(PendingOcrStatus.PENDING, task.status)

        // 计数触顶后仍按原口径清理（未配置的豁免只对未配置生效）
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        val summary = handler.retryPendingTasks()
        assertFalse(summary.needsConfiguration)
        assertTrue("触顶后网络失败任务仍应被清理", pendingRepository.all().isEmpty())
    }
    // ---- 测试工具 ----

    private fun handler(): HomeworkOcrHandler = HomeworkOcrHandler(
        ocrRecognizer = recognizer,
        ocrConfigStore = configStore,
        pendingOcrRepository = pendingRepository,
        fileStore = fileStore,
        clock = clock,
    )

    private fun entryViewModel(role: Role?): HomeworkEntryViewModel = HomeworkEntryViewModel(
        homeworkRepository = NoopHomeworkRepository(),
        authRepository = FakeAuthRepository(sessionOf(role)),
        ocrHandler = handler(),
        speechToText = NoopSpeechToText(),
        fileStore = fileStore,
        clock = clock,
        zoneId = zone,
    )

    private fun sessionOf(role: Role?): SessionState = when (role) {
        Role.PARENT -> SessionState(role = Role.PARENT, parentId = PARENT_ID, studentId = null)
        Role.STUDENT -> SessionState(role = Role.STUDENT, parentId = PARENT_ID, studentId = STUDENT_ID)
        null -> SessionState.NONE
    }

    private fun localImage(name: String = "probe.jpg"): String {
        val file = File(tempDir, name)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        return file.absolutePath
    }

    private class NoopSpeechToText : SpeechToText {
        override fun startListening(): Flow<SpeechToText.SpeechEvent> = flowOf()
        override fun stopListening() = Unit
        override fun release() = Unit
    }

    private class NoopHomeworkRepository : HomeworkRepository {
        override fun observeHomework(studentId: Long): Flow<List<HomeworkItem>> = flowOf(emptyList())
        override suspend fun listHomework(studentId: Long): List<HomeworkItem> = emptyList()
        override suspend fun getHomework(homeworkId: Long): HomeworkItem? = null

        override suspend fun addHomework(template: HomeworkTemplate, studentId: Long): AddHomeworkResult =
            unsupported()

        override suspend fun reorderHomework(
            homeworkId: Long,
            direction: ReorderDirection,
            sessionRole: Role,
        ): HomeworkOrderResult = unsupported()

        override suspend fun moveHomeworkTo(
            homeworkId: Long,
            targetIndex: Int,
            sessionRole: Role,
        ): HomeworkOrderResult = unsupported()

        override suspend fun updateSchedule(
            homeworkId: Long,
            startTime: Instant,
            estimatedMinutes: Int,
            sessionRole: Role,
        ): ScheduleUpdateResult = unsupported()

        override suspend fun clearSchedule(homeworkId: Long, sessionRole: Role): HomeworkOperationResult =
            unsupported()

        override suspend fun updateContent(
            homeworkId: Long,
            content: String,
            sessionRole: Role,
        ): HomeworkOperationResult = unsupported()

        override suspend fun updateTemplate(
            homeworkId: Long,
            type: HomeworkType,
            stageRange: StageRange?,
            deadline: Instant?,
            sessionRole: Role,
        ): HomeworkOperationResult = unsupported()

        override suspend fun deleteHomework(homeworkId: Long, sessionRole: Role): HomeworkOperationResult =
            unsupported()

        override suspend fun markPending(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        override suspend fun startProgress(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        override suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        override suspend fun reopen(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        private fun <T> unsupported(): T = throw UnsupportedOperationException("探针不触达该能力")
    }

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L

        /** 未配置时 Handler 给出的底层文案（role=null 沿用；学生侧必须被替换） */
        const val EXPECTED_NOT_CONFIGURED_MESSAGE =
            "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"

        /** 家长会话最终可见文案：等于既有原文（「去设置」只作为 Snackbar 动作，不写进正文） */
        const val EXPECTED_PARENT_NOT_CONFIGURED_NOTICE = EXPECTED_NOT_CONFIGURED_MESSAGE

        /** 指向设置页的文案标记：学生侧任何提示中都不得出现 */
        val STUDENT_FORBIDDEN_MARKERS = listOf("设置页", "去设置", "设置中", "开启并填写厂商参数")
    }
}