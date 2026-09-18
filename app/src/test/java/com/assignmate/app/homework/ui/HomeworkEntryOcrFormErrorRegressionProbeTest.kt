package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrResult
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
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkTemplate
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 离朱补强回归探针（对应测试说明「四、建议复测重点」第 3 条）：
 * **本轮把「未配置」从 [HomeworkEntryUiState.formError] 通道摘除时，不得连带清空其他失败类型的内联错误。**
 *
 * 为什么单独立探针：改动点是 `failureFormError(message, needsConfiguration)`（ViewModel 私有），
 * 最危险的写法是「一律返回 null」；而既有 5 个测试类只断言了 Snackbar 文案与 `needsConfiguration`
 * 标志，**没有任何用例断言过 formError 的写入结果**，这类回归会静默通过。
 *
 * 实测口径（与 HEAD 版本逐字对照，见报告「关键发现」）：
 * - 写内联错误的只有「不可重试失败」：[OcrOutcome.Failed] + `needsConfiguration = false`
 *   —— ParseError / Unknown / 图片不可读；
 * - 不写内联错误的：
 *   * 可重试失败 [OcrOutcome.NeedsRetry]（NetworkError / ServiceError）—— **HEAD 起既有口径**
 *     （“可重试失败不进表单内联错误，由待重试入口与提示承担”），不是本轮改动引入；
 *   * 未配置（`needsConfiguration = true`）—— 本轮改动：提示由就地卡片 / 带动作 Snackbar 专门承担。
 *
 * 覆盖：
 * A. 正向：解析 / 未知 / 图片不可读仍按原文写入 formError 且与 Snackbar 同源（回归红线）；
 * B. 反向：未配置（Failed 路径）与网络/服务端（NeedsRetry 路径）在三个角色下均不得写 formError；
 * C. 全 6 条失败分支 × 3 角色的 formError 真值表，防「一律 null」型回归；
 * D. 状态机：formError 的写入—清除流转（onContentChange 清除、下次识别开始清除、
 *    未配置失败不残留上一条非未配置原文）。
 *
 * 说明：Compose 渲染分支无法在 JVM 断言（本仓库无设备、未引入 Robolectric），
 * 故以「ViewModel 真实失败链路的 uiState + 同源文案」覆盖 FormError 渲染分支的输入契约。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkEntryOcrFormErrorRegressionProbeTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock = MutableClock(1_700_000_000_000L)

    private lateinit var tempDir: File
    private lateinit var fileStore: FakeHomeworkFileStore
    private lateinit var recognizer: FakeOcrRecognizer
    private lateinit var pendingRepository: FakePendingOcrRepository
    private lateinit var configStore: FakeOcrConfigStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("homework-entry-form-error-probe").toFile()
        fileStore = FakeHomeworkFileStore(tempDir)
        recognizer = FakeOcrRecognizer()
        pendingRepository = FakePendingOcrRepository()
        configStore = FakeOcrConfigStore(
            OcrConfig(
                apiBaseUrl = "https://example.com/v1",
                modelName = "test-model",
                apiKey = "secret",
                enabled = true,
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    // ---- A. 正向：不可重试失败仍写内联错误（回归红线） ----

    @Test
    fun `解析失败仍按原文写入内联错误且与提示同源`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val messages = collectMessages(viewModel, this)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.ParseError())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        val expected = "没认出文字，请换一张更清晰的图片"
        assertEquals("解析失败必须写入常驻内联错误（不得被本轮改动清空）", expected, viewModel.uiState.value.formError)
        assertEquals("内联错误与 Snackbar 文案同源", listOf(expected), messages.messages)
        messages.collector.cancel()
    }

    @Test
    fun `未知失败仍按原文写入内联错误且与提示同源`() = runTest {
        val viewModel = entryViewModel(Role.PARENT)
        val messages = collectMessages(viewModel, this)
        viewModel.start(PARENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.Unknown(userMessage = "识别失败，请稍后重试"))

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        val expected = "识别失败，请稍后重试"
        assertEquals("未知失败必须写入常驻内联错误", expected, viewModel.uiState.value.formError)
        assertEquals("内联错误与 Snackbar 文案同源", listOf(expected), messages.messages)
        messages.collector.cancel()
    }

    @Test
    fun `图片不可读时仍按原文写入内联错误`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()

        // 路径不存在 → fileStore.loadImage 返回 Missing → Failed(FILE_UNREADABLE_HINT, false)
        viewModel.onGalleryPicked(File(tempDir, "not-exists.jpg").absolutePath)
        advanceUntilIdle()

        assertEquals(
            "图片不可读必须写入内联错误（needsConfiguration=false）",
            HomeworkOcrHandler.FILE_UNREADABLE_HINT,
            viewModel.uiState.value.formError,
        )
    }

    @Test
    fun `不可重试失败在所有角色下都写内联错误且文案与角色无关`() = runTest {
        val failures = listOf(
            OcrResult.Failure.ParseError() to "没认出文字，请换一张更清晰的图片",
            OcrResult.Failure.Unknown(userMessage = "识别失败，请稍后重试") to "识别失败，请稍后重试",
        )
        listOf<Role?>(Role.PARENT, Role.STUDENT, null).forEach { role ->
            failures.forEachIndexed { index, pair ->
                val viewModel = entryViewModel(role)
                viewModel.start(if (role == Role.PARENT) PARENT_ID else STUDENT_ID)
                advanceUntilIdle()
                recognizer.enqueue(pair.first)

                viewModel.onGalleryPicked(localImage("${role?.name ?: "none"}-$index.jpg"))
                advanceUntilIdle()

                assertEquals(
                    "role=$role 失败类型「${pair.second}」必须写入与角色无关的原文",
                    pair.second,
                    viewModel.uiState.value.formError,
                )
            }
        }
    }

    // ---- B. 反向：未配置与可重试失败不得写内联错误 ----

    @Test
    fun `未配置失败在所有角色下都不写内联错误`() = runTest {
        listOf<Role?>(Role.PARENT, Role.STUDENT, null).forEach { role ->
            val viewModel = entryViewModel(role)
            viewModel.start(if (role == Role.PARENT) PARENT_ID else STUDENT_ID)
            advanceUntilIdle()
            recognizer.enqueue(OcrResult.Failure.NotConfigured())

            viewModel.onGalleryPicked(localImage("${role?.name ?: "none"}.jpg"))
            advanceUntilIdle()

            val label = "role=$role"
            assertNull(
                "$label：未配置场景不得写内联错误（提示由就地卡片 / Snackbar 专门承担）",
                viewModel.uiState.value.formError,
            )
            val guidance = ocrSetupGuidance(viewModel.uiState.value.role, hasSettingsEntry = true)
            when (role) {
                // 学生：唯一渲染通道是就地卡片，正文取渲染入口产物
                Role.STUDENT -> assertEquals(
                    "$label：就地卡片正文",
                    OCR_PARENT_SETUP_NOTICE,
                    guidance.notice(EXPECTED_NOT_CONFIGURED_MESSAGE).message,
                )

                Role.PARENT -> assertNotNull("$label：家长侧动作 = 去设置", guidance.notice(EXPECTED_NOT_CONFIGURED_MESSAGE).actionLabel)
                null -> assertNull("$label：会话失效无动作", guidance.notice(EXPECTED_NOT_CONFIGURED_MESSAGE).actionLabel)
            }
        }
    }

    @Test
    fun `可重试失败所有角色下都不写内联错误且仅下发一次提示`() = runTest {
        // 既有口径（HEAD 起）：可重试失败不进表单内联错误，由「待重试识别」入口与 Snackbar 承担
        val failures = listOf(
            OcrResult.Failure.NetworkError() to "需要联网才能识别图片，已保存待重试",
            OcrResult.Failure.ServiceError(code = 503, userMessage = "服务不可用") to
                "识别服务暂时不可用（503），已保存待重试",
        )
        listOf<Role?>(Role.PARENT, Role.STUDENT, null).forEach { role ->
            failures.forEachIndexed { index, pair ->
                val viewModel = entryViewModel(role)
                val messages = collectMessages(viewModel, this)
                viewModel.start(if (role == Role.PARENT) PARENT_ID else STUDENT_ID)
                advanceUntilIdle()
                recognizer.enqueue(pair.first)

                viewModel.onGalleryPicked(localImage("${role?.name ?: "none"}-retry-$index.jpg"))
                advanceUntilIdle()

                val label = "role=$role 失败类型「${pair.second}」"
                assertNull("$label：可重试失败既有口径不进内联错误", viewModel.uiState.value.formError)
                assertEquals("$label：提示只下发一次", listOf(pair.second), messages.messages)
                assertEquals("$label：不得误报未配置", emptyList<String>(), messages.needsConfiguration)
                messages.collector.cancel()
            }
        }
    }

    // ---- C. 全失败分支 × 角色的 formError 真值表 ----

    @Test
    fun `六条失败分支在三个角色下的内联错误真值表保持稳定`() = runTest {
        // (failure, 期望 formError，null 表示不写)
        val matrix = listOf(
            OcrResult.Failure.NotConfigured() to null,
            OcrResult.Failure.NetworkError() to null,
            OcrResult.Failure.ServiceError(code = 500, userMessage = "服务不可用") to null,
            OcrResult.Failure.ParseError() to "没认出文字，请换一张更清晰的图片",
            OcrResult.Failure.Unknown(userMessage = "识别失败，请稍后重试") to "识别失败，请稍后重试",
        )
        listOf<Role?>(Role.PARENT, Role.STUDENT, null).forEach { role ->
            matrix.forEachIndexed { index, pair ->
                val viewModel = entryViewModel(role)
                viewModel.start(if (role == Role.PARENT) PARENT_ID else STUDENT_ID)
                advanceUntilIdle()
                recognizer.enqueue(pair.first)

                viewModel.onGalleryPicked(localImage("matrix-${role?.name ?: "none"}-$index.jpg"))
                advanceUntilIdle()

                assertEquals(
                    "role=$role 分支 $index 的 formError 真值不符合契约",
                    pair.second,
                    viewModel.uiState.value.formError,
                )
            }
        }
    }

    // ---- D. 状态机：写入—清除流转 ----

    @Test
    fun `内联错误在用户修改内容后清除`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.ParseError())
        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()
        assertEquals(
            "前置条件：解析失败已写入内联错误",
            "没认出文字，请换一张更清晰的图片",
            viewModel.uiState.value.formError,
        )

        viewModel.onContentChange("手动补充的内容")

        assertNull("用户修改内容后内联错误应清除（既有口径不变）", viewModel.uiState.value.formError)
    }

    @Test
    fun `内联错误在下次识别开始时清除`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.ParseError())
        viewModel.onGalleryPicked(localImage("first.jpg"))
        advanceUntilIdle()
        assertEquals(
            "前置条件：解析失败已写入内联错误",
            "没认出文字，请换一张更清晰的图片",
            viewModel.uiState.value.formError,
        )

        // 成功识别：startOcr 先把 formError 置空，成功分支不再写入
        recognizer.enqueue(OcrResult.Success(text = "识别成功的内容"))
        viewModel.onGalleryPicked(localImage("second.jpg"))
        advanceUntilIdle()

        assertNull("识别成功后不得残留上一条内联错误", viewModel.uiState.value.formError)
        assertTrue("成功识别应回填内容", viewModel.uiState.value.content.contains("识别成功的内容"))
    }

    @Test
    fun `未配置失败不残留上一条非未配置内联错误`() = runTest {
        val viewModel = entryViewModel(Role.PARENT)
        viewModel.start(PARENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.ParseError())
        viewModel.onGalleryPicked(localImage("first.jpg"))
        advanceUntilIdle()
        assertEquals(
            "前置条件：解析失败已写入内联错误",
            "没认出文字，请换一张更清晰的图片",
            viewModel.uiState.value.formError,
        )

        // 下一次识别（未配置）开始时清空上一条，且未配置本身不再写入 → 保持 null
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        viewModel.onGalleryPicked(localImage("second.jpg"))
        advanceUntilIdle()

        assertNull("未配置识别后不得残留上一条非未配置原文", viewModel.uiState.value.formError)
    }

    @Test
    fun `批量重试撞上未配置清空上一条内联错误`() = runTest {
        val viewModel = entryViewModel(Role.PARENT)
        viewModel.start(PARENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.ParseError())
        viewModel.onGalleryPicked(localImage("seed.jpg"))
        advanceUntilIdle()
        assertEquals(
            "前置条件：解析失败写入内联错误",
            "没认出文字，请换一张更清晰的图片",
            viewModel.uiState.value.formError,
        )

        // 再登记一条 PENDING 任务供重试，重试链路撞上未配置
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        viewModel.onGalleryPicked(localImage("pending.jpg"))
        advanceUntilIdle()

        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        viewModel.onRetryPendingClick()
        advanceUntilIdle()

        assertNull("重试链路未配置同样不写内联错误，且不得残留上一条原文", viewModel.uiState.value.formError)
    }

    @Test
    fun `批量重试失败只走提示通道不写内联错误`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val messages = collectMessages(viewModel, this)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        viewModel.onGalleryPicked(localImage("pending.jpg"))
        advanceUntilIdle()
        messages.messages.clear()

        // 重试时返回不可重试失败（解析失败）：提示走 Snackbar，内联错误保持为 null
        // （HEAD 起既有口径：重试链路不写 formError，见 onRetryPendingClick 只 sendMessage）
        recognizer.enqueue(OcrResult.Failure.ParseError())
        viewModel.onRetryPendingClick()
        advanceUntilIdle()

        assertNull(
            "重试链路失败不写内联错误（提示由 Snackbar 承担，HEAD 起既有口径）",
            viewModel.uiState.value.formError,
        )
        assertEquals(
            "重试链路的失败提示必须原样下发一次",
            listOf("没认出文字，请换一张更清晰的图片"),
            messages.messages,
        )
        messages.collector.cancel()
    }

    // ---- 测试工具 ----

    private fun entryViewModel(role: Role?): HomeworkEntryViewModel = HomeworkEntryViewModel(
        homeworkRepository = NoopHomeworkRepository(),
        authRepository = FakeAuthRepository(sessionOf(role)),
        ocrHandler = HomeworkOcrHandler(
            ocrRecognizer = recognizer,
            ocrConfigStore = configStore,
            pendingOcrRepository = pendingRepository,
            fileStore = fileStore,
            clock = clock,
        ),
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

    private fun localImage(name: String = "picked.jpg"): String {
        val file = File(tempDir, name)
        file.writeBytes(byteArrayOf(9, 8, 7, 6))
        return file.absolutePath
    }

    private fun collectMessages(viewModel: HomeworkEntryViewModel, scope: CoroutineScope): CollectedMessages {
        val sink = CollectedMessages()
        sink.collector = scope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is HomeworkEntryEvent.ShowMessage -> sink.messages += event.message
                    is HomeworkEntryEvent.Saved -> sink.messages += event.message
                    is HomeworkEntryEvent.NeedsOcrConfiguration -> sink.needsConfiguration += event.message
                    is HomeworkEntryEvent.LaunchCamera -> Unit
                }
            }
        }
        return sink
    }

    private class CollectedMessages {
        val messages = mutableListOf<String>()
        val needsConfiguration = mutableListOf<String>()
        lateinit var collector: Job
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

        private fun <T> unsupported(): T = throw UnsupportedOperationException("本探针不触达作业仓库能力")
    }

    private companion object Constants {
        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L

        /** 未配置时 Handler 给出的底层原文（渲染入口按角色改写） */
        const val EXPECTED_NOT_CONFIGURED_MESSAGE =
            "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"
    }
}