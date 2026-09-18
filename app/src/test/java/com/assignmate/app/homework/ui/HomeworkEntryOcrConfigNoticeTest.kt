package com.assignmate.app.homework.ui

import com.assignmate.app.auth.data.AddStudentResult
import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.data.DeleteStudentResult
import com.assignmate.app.auth.data.ParentLoginResult
import com.assignmate.app.auth.data.ParentRegisterResult
import com.assignmate.app.auth.data.RenameStudentResult
import com.assignmate.app.auth.data.StudentEnterResult
import com.assignmate.app.auth.data.UpdateVerificationCodeResult
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.auth.domain.Student
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrResult
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.speech.SpeechToText
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.AddHomeworkResult
import com.assignmate.app.homework.data.FakeHomeworkFileStore
import com.assignmate.app.homework.data.FakeOcrConfigStore
import com.assignmate.app.homework.data.FakeOcrRecognizer
import com.assignmate.app.homework.data.FakePendingOcrRepository
import com.assignmate.app.homework.data.HomeworkOperationResult
import com.assignmate.app.homework.data.HomeworkOrderResult
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkStatusResult
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
 * 录入页「识别服务未配置」提示的角色收敛单测（走 [HomeworkEntryViewModel] 真实失败链路）。
 *
 * **收敛口径**：正文与「去设置」动作**只在 UI 渲染入口** [ocrNoticeFor] 一次产出；ViewModel 只发
 * [HomeworkEntryEvent.NeedsOcrConfiguration]（携带底层原文）且**不预先收敛**。未配置场景也不再写
 * 常驻 `formError`——该提示有**专门的呈现通道**：家长是带动作的 Snackbar、学生是就地卡片，
 * 两个分支消费的**是同一产物**（家长取正文+动作、学生取正文），因此同一角色同屏只会出现一处提示
 * （家长与 role=null 恰好一条 Snackbar、学生 0 条 Snackbar + 1 张卡片）。
 *
 * 覆盖（**断言对准渲染分支的真实消费值**，见 [OcrNoticeRenderCounter]）：
 * 1. 学生 + 未配置：不预下发 Snackbar 文案、恰好一条事件（携带底层原文）；**卡片实际正文**
 *    = 渲染入口产物（无汇总信息时等于 [OCR_PARENT_SETUP_NOTICE]，零设置页字样、保留待重试信息），
 *    无动作、0 条 Snackbar，且不再重复写 formError；
 * 2. 学生 + 重试部分成功（1 成功 + 1 未配置）：**卡片实际正文必须含「识别成功 1 张」**；
 * 3. 家长 + 未配置：渲染恰好一次、正文 = 既有原文（无重复后缀、不含按钮文案与「点击」）、
 *    actionLabel = 「去设置」且仅动作执行时才计为跳转；
 * 4. 未注入设置页入口：无按钮且正文不声称可点击；role = null：沿用原文、无动作；
 * 5. 其他失败类型（NetworkError / ServiceError / ParseError / Unknown）文案与分派不回归；
 * 6. 未配置仍登记 PENDING 待重试任务（不丢图片、计数不累加）。
 *
 * 说明：Compose 渲染无法在 JVM 断言（本仓库无设备/未引入 Robolectric），故这里对
 * 「事件原文 + UI 渲染入口（文案 + 动作 + 渲染次数）」做组合断言（[OcrNoticeRenderCounter]
 * 复刻 Route 的消费逻辑），实际像素观感仍需真机验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkEntryOcrConfigNoticeTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var tempDir: File
    private lateinit var fileStore: FakeHomeworkFileStore
    private lateinit var recognizer: FakeOcrRecognizer
    private lateinit var pendingRepository: FakePendingOcrRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tempDir = Files.createTempDirectory("homework-entry-ocr-notice").toFile()
        fileStore = FakeHomeworkFileStore(tempDir)
        recognizer = FakeOcrRecognizer()
        pendingRepository = FakePendingOcrRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    // ---- 学生分支：卡片实际正文取自收敛产物，且不含指向设置页的提示 ----

    @Test
    fun `学生会话未配置时卡片实际正文取自收敛产物且不含设置页引导`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        // ViewModel 不为未配置场景下发 ShowMessage：提示只由携带渲染入口的事件产出，避免弹两次
        assertEquals("不得预下发 Snackbar 文案", emptyList<String>(), notices.messages)
        assertEquals("未配置应恰好发一条事件", 1, notices.needsConfiguration.size)
        assertEquals("事件携带底层原文（供渲染入口改写）", EXPECTED_RAW_NOT_CONFIGURED, notices.needsConfiguration.single())

        // 渲染分支真实消费值：学生 → 卡片正文（0 条 Snackbar）
        render.renderAll(notices, viewModel, hasSettingsEntry = true)
        assertEquals("学生不得有 Snackbar", 0, render.snackbarCount)
        val cardText = render.cardText.single()
        assertEquals("卡片正文 = 收敛产物（无汇总信息即本常量）", OCR_PARENT_SETUP_NOTICE, cardText)
        assertTrue(
            "学生不得看到任何指向设置页的文案，实际：$cardText",
            STUDENT_FORBIDDEN_MARKERS.none { cardText.contains(it) },
        )
        assertTrue(
            "学生提示仍须保留「图片已保存待重试」信息，实际：$cardText",
            cardText.contains("图片已保存待重试"),
        )
        assertTrue("学生文案须指向家长配置", cardText.contains(OCR_PARENT_SETUP_HINT))
        notices.collector.cancel()
    }

    @Test
    fun `学生会话未配置时卡片正文与渲染入口产物逐字一致`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()
        render.renderAll(notices, viewModel, hasSettingsEntry = true)

        // 渲染分支消费的必须是 ocrNoticeFor 的产物本身（不是任何静态短句）
        assertEquals(
            "卡片正文必须等于渲染入口对同一事件原文的产物",
            ocrNoticeFor(
                role = viewModel.uiState.value.role,
                hasSettingsEntry = true,
                baseMessage = notices.needsConfiguration.single(),
            ).message,
            render.cardText.single(),
        )
        notices.collector.cancel()
    }

    @Test
    fun `学生会话未配置时同屏只保留就地卡片不再重复写内联错误`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()
        render.renderAll(notices, viewModel, hasSettingsEntry = true)

        // 未配置提示只由就地卡片承担；formError 不再重复写一遍，否则同屏会出现两处「请家长配置」
        assertNull("未配置场景不再写表单内联错误（避免同屏重复提示）", viewModel.uiState.value.formError)
        assertEquals("学生侧恰好一张卡片", 1, render.cardText.size)
        assertEquals("学生侧 0 条 Snackbar", 0, render.snackbarCount)
        notices.collector.cancel()
    }

    @Test
    fun `家长会话未配置时内联错误不重复写且不回归其他失败`() = runTest {
        val viewModel = entryViewModel(Role.PARENT)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(PARENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()
        render.renderAll(notices, viewModel, hasSettingsEntry = true)

        // 家长侧该提示由带动作的 Snackbar 承担，不再重复写常驻内联错误
        assertNull("未配置场景家长侧也不重复写内联错误", viewModel.uiState.value.formError)
        assertEquals("家长侧不渲染就地卡片", 0, render.cardText.size)
        assertEquals("家长侧恰好一条 Snackbar", 1, render.snackbarCount)
        notices.collector.cancel()
    }

    @Test
    fun `会话失效时提示沿用既有原文且不写内联错误`() = runTest {
        val viewModel = entryViewModel(role = null)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()
        render.renderAll(notices, viewModel, hasSettingsEntry = true)

        assertNull("未配置场景不写内联错误", viewModel.uiState.value.formError)
        assertEquals("会话失效渲染一条 Snackbar（无动作）", 1, render.snackbarCount)
        assertNull("会话失效无动作", render.actionLabels.single())
        assertEquals("会话失效沿用底层原文", EXPECTED_RAW_NOT_CONFIGURED, render.messages.single())
        assertEquals("会话失效不渲染就地卡片", 0, render.cardText.size)
        notices.collector.cancel()
    }

    @Test
    fun `学生会话未配置时不触碰仓库且待重试任务仍被登记`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        val tasks = pendingRepository.all()
        assertEquals("未配置不丢图片：仍登记一条 PENDING 待重试任务", 1, tasks.size)
        assertEquals(PENDING_STATUS, tasks.single().status)
        assertTrue("转正后的图片仍在磁盘上，配置补齐后可直接重试", File(tasks.single().localImagePath).isFile)
    }

    @Test
    fun `学生卡片正文与 UI 是否注入设置页入口无关`() = runTest {
        // ViewModel 只依角色产出、UI 只按 onGoToOcrSettings != null 决定有无按钮；
        // 故两个同构实例的学生侧**卡片实际正文**必须完全一致（且都等于收敛产物）
        val first = entryViewModel(Role.STUDENT)
        val second = entryViewModel(Role.STUDENT)
        val firstNotices = collectNotices(first, this)
        val secondNotices = collectNotices(second, this)
        val firstRender = OcrNoticeRenderCounter()
        val secondRender = OcrNoticeRenderCounter()
        first.start(STUDENT_ID)
        second.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        first.onGalleryPicked(localImage("a.jpg"))
        advanceUntilIdle()
        second.onGalleryPicked(localImage("b.jpg"))
        advanceUntilIdle()
        firstRender.renderAll(firstNotices, first, hasSettingsEntry = true)
        secondRender.renderAll(secondNotices, second, hasSettingsEntry = false)

        assertEquals(OCR_PARENT_SETUP_NOTICE, firstRender.cardText.single())
        assertEquals(OCR_PARENT_SETUP_NOTICE, secondRender.cardText.single())
        assertEquals(
            "学生卡片正文不得随 UI 注入情况漂移",
            firstRender.cardText,
            secondRender.cardText,
        )
        assertEquals("两个实例的学生内联错误也应一致", first.uiState.value.formError, second.uiState.value.formError)
        firstNotices.collector.cancel()
        secondNotices.collector.cancel()
    }

    @Test
    fun `会话失效时提示沿用既有文案且不额外引导`() = runTest {
        val viewModel = entryViewModel(role = null)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        // role=null 沿用既有会话失效处理：只回显识别失败原文，不追加角色引导
        assertEquals("不得预下发 Snackbar 文案", emptyList<String>(), notices.messages)
        assertEquals(listOf(EXPECTED_RAW_NOT_CONFIGURED), notices.needsConfiguration)
        render.renderAll(notices, viewModel, hasSettingsEntry = true)
        assertEquals("会话失效渲染一条 Snackbar", 1, render.snackbarCount)
        assertEquals("会话失效沿用既有原文", EXPECTED_RAW_NOT_CONFIGURED, render.messages.single())
        assertNull("会话失效不渲染按钮", render.actionLabels.single())
        assertEquals("会话失效不渲染就地卡片", 0, render.cardText.size)
        notices.collector.cancel()
    }

    // ---- 家长分支：既有「去设置」引导不回归 ----

    @Test
    fun `家长会话未配置时渲染恰好一次且后缀不重复`() = runTest {
        val viewModel = entryViewModel(Role.PARENT)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(PARENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        // 组合路径断言：事件原文 + UI 渲染入口（正文 + 动作 + 渲染次数）
        assertEquals("不得预下发 Snackbar 文案（否则会弹两条）", emptyList<String>(), notices.messages)
        render.renderAll(notices, viewModel, hasSettingsEntry = true)
        val message = render.messages.single()
        assertEquals("同一提示只渲染一次", 1, render.snackbarCount)
        assertEquals("正文 = 既有原文（无重复后缀、正文不夹带按钮说明）", EXPECTED_PARENT_NOT_CONFIGURED_NOTICE, message)
        assertFalse(
            "「去设置」是 actionLabel（按钮），不得混入正文，实际：$message",
            message.contains(OCR_SETTINGS_ACTION_LABEL),
        )
        assertFalse("正文不得声称可点击，实际：$message", message.contains("点击"))
        assertTrue("家长文案保留待重试事实", message.contains("图片已保存待重试"))
        assertEquals("家长按钮动作文案不变（动作只走 actionLabel）", OCR_SETTINGS_ACTION_LABEL, render.actionLabels.single())
        assertEquals("仅在 ActionPerformed 时才跳设置页", 0, render.navigationCount)
        assertEquals("家长侧不渲染就地卡片", 0, render.cardText.size)
        assertNull(
            "未配置场景改由 Snackbar 承担提示，不再重复写常驻内联错误",
            viewModel.uiState.value.formError,
        )
        assertEquals("家长分支不渲染就地提示卡片", 0, render.cardText.size)
        notices.collector.cancel()
    }

    @Test
    fun `家长会话动作执行时才触发去设置导航`() = runTest {
        val viewModel = entryViewModel(Role.PARENT)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(PARENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        // 模拟 SnackbarResult.ActionPerformed：仅此时才应触发 onGoToOcrSettings
        render.renderAll(notices, viewModel, hasSettingsEntry = true, performAction = true)
        assertEquals("点击「去设置」才跳设置页", 1, render.navigationCount)
        assertEquals(OCR_SETTINGS_ACTION_LABEL, render.actionLabels.single())
        notices.collector.cancel()
    }

    @Test
    fun `家长会话未注入设置页入口时只提示无按钮且文案不声称可点击`() = runTest {
        val viewModel = entryViewModel(Role.PARENT)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(PARENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        render.renderAll(notices, viewModel, hasSettingsEntry = false)
        val message = render.messages.single()
        assertEquals("未注入回调时仍只提示一次", 1, render.snackbarCount)
        assertNull("无按钮时不得携带 actionLabel", render.actionLabels.single())
        assertFalse("无按钮时文案不得声称可点击，实际：$message", message.contains("点击"))
        assertTrue("家长仍须知晓图片已暂存，配置补齐后可直接重试", message.contains("图片已保存待重试"))
        assertNull(
            "未注入回调时 guidance 不提供按钮",
            ocrSetupGuidance(viewModel.uiState.value.role, hasSettingsEntry = false).actionLabel,
        )
        notices.collector.cancel()
    }

    // ---- 其他失败类型：文案与分派不回归 ----

    @Test
    fun `网络失败提示已保存待重试且不视为需要配置`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val notices = collectNotices(viewModel, this)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NetworkError())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        assertEquals(listOf("需要联网才能识别图片，已保存待重试"), notices.messages)
        assertTrue("网络失败属可重试，不应触发未配置引导", notices.needsConfiguration.isEmpty())
        notices.collector.cancel()
    }

    @Test
    fun `服务端失败提示带状态码且不视为需要配置`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val notices = collectNotices(viewModel, this)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.ServiceError(code = 503, userMessage = "服务不可用"))

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        assertEquals(listOf("识别服务暂时不可用（503），已保存待重试"), notices.messages)
        assertTrue(notices.needsConfiguration.isEmpty())
        notices.collector.cancel()
    }

    @Test
    fun `解析失败提示更换图片且不视为需要配置`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val notices = collectNotices(viewModel, this)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.ParseError())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        assertEquals(listOf("没认出文字，请换一张更清晰的图片"), notices.messages)
        assertTrue(notices.needsConfiguration.isEmpty())
        notices.collector.cancel()
    }

    @Test
    fun `未知失败通用提示且不视为需要配置`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val notices = collectNotices(viewModel, this)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.Unknown())

        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        assertEquals(listOf("识别失败，请稍后重试"), notices.messages)
        assertTrue(notices.needsConfiguration.isEmpty())
        notices.collector.cancel()
    }

    // ---- 批量重试链路：同口径 + 部分成功保留计数 ----

    @Test
    fun `家长会话重试撞上未配置时渲染一次且保留原文`() = runTest {
        val viewModel = entryViewModel(Role.PARENT)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(PARENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()

        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        viewModel.onRetryPendingClick()
        advanceUntilIdle()

        render.renderAll(notices, viewModel, hasSettingsEntry = true)
        assertEquals(
            "家长重试链路文案不变（仍指向设置页）且只渲染一次",
            listOf(EXPECTED_PARENT_NOT_CONFIGURED_NOTICE),
            render.messages,
        )
        assertEquals(1, render.snackbarCount)
        assertEquals(OCR_SETTINGS_ACTION_LABEL, render.actionLabels.single())
        notices.collector.cancel()
    }

    @Test
    fun `学生会话重试撞上未配置时不下发指向设置页的文案`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val notices = collectNotices(viewModel, this)
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        viewModel.onGalleryPicked(localImage())
        advanceUntilIdle()
        assertEquals(
            "前置网络失败提示（非未配置场景，由 ViewModel 直接下发）",
            listOf("需要联网才能识别图片，已保存待重试"),
            notices.messages,
        )
        notices.messages.clear()

        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        viewModel.onRetryPendingClick()
        advanceUntilIdle()

        assertEquals("未配置场景由事件驱动渲染，不再预下发 Snackbar 文案", emptyList<String>(), notices.messages)
        val render = OcrNoticeRenderCounter()
        render.renderAll(notices, viewModel, hasSettingsEntry = true)
        val cardText = render.cardText.single()
        assertEquals("重试链路学生卡片正文 = 收敛产物", OCR_PARENT_SETUP_NOTICE, cardText)
        assertTrue(
            "重试链路同样不得给学生下发指向设置页的文案",
            STUDENT_FORBIDDEN_MARKERS.none { cardText.contains(it) },
        )
        assertEquals("学生侧 0 条 Snackbar", 0, render.snackbarCount)
        notices.collector.cancel()
    }

    @Test
    fun `学生侧重试部分成功时卡片实际正文保留识别成功计数`() = runTest {
        val viewModel = entryViewModel(Role.STUDENT)
        val notices = collectNotices(viewModel, this)
        val render = OcrNoticeRenderCounter()
        viewModel.start(STUDENT_ID)
        advanceUntilIdle()
        // 两条待重试任务：一条本次成功、一条仍因未配置失败
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        viewModel.onGalleryPicked(localImage("first.jpg"))
        advanceUntilIdle()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        viewModel.onGalleryPicked(localImage("second.jpg"))
        advanceUntilIdle()
        assertEquals("两条待重试任务已登记", 2, pendingRepository.all().size)

        recognizer.enqueue(OcrResult.Success(text = "第一条识别成功"))
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        viewModel.onRetryPendingClick()
        advanceUntilIdle()

        // 断言对准**渲染分支真实消费的卡片正文**（不是纯函数自身的值）
        render.renderAll(notices, viewModel, hasSettingsEntry = true)
        val cardText = render.cardText.single()
        assertTrue("学生卡片正文必须保留成功条数，实际：$cardText", cardText.contains("识别成功 1 张"))
        assertTrue("须指向家长配置，实际：$cardText", cardText.contains(OCR_PARENT_SETUP_HINT))
        assertTrue(
            "不得残留设置页字样，实际：$cardText",
            STUDENT_FORBIDDEN_MARKERS.none { cardText.contains(it) },
        )
        assertTrue("待重试事实不得弱化，实际：$cardText", cardText.contains("图片已保存待重试"))
        assertFalse(
            "卡片正文不得等于「无汇总信息」的静态短句（否则成功计数丢失）",
            cardText == OCR_PARENT_SETUP_NOTICE,
        )
        assertEquals("学生侧 0 条 Snackbar", 0, render.snackbarCount)
        // 成功那张已清理，未配置那张必须保留（供配置补齐后重试）
        assertEquals("未配置任务保留待重试", 1, pendingRepository.all().size)
        notices.collector.cancel()
    }

    @Test
    fun `家长与学生重试链路渲染产物按角色互斥`() = runTest {
        val perRole = Role.entries.associateWith { role ->
            val viewModel = entryViewModel(role)
            val notices = collectNotices(viewModel, this)
            val render = OcrNoticeRenderCounter()
            viewModel.start(STUDENT_ID)
            advanceUntilIdle()
            recognizer.enqueue(OcrResult.Failure.NetworkError())
            viewModel.onGalleryPicked(localImage("retry-${role.name}.jpg"))
            advanceUntilIdle()

            recognizer.enqueue(OcrResult.Failure.NotConfigured())
            viewModel.onRetryPendingClick()
            advanceUntilIdle()
            render.renderAll(notices, viewModel, hasSettingsEntry = true)
            notices.collector.cancel()
            render
        }

        val parentRender = perRole.getValue(Role.PARENT)
        val studentRender = perRole.getValue(Role.STUDENT)
        assertEquals("家长重试正文 = 既有原文", EXPECTED_PARENT_NOT_CONFIGURED_NOTICE, parentRender.messages.single())
        assertEquals("家长重试动作 = 去设置（按钮）", OCR_SETTINGS_ACTION_LABEL, parentRender.actionLabels.single())
        assertEquals("家长侧不渲染就地卡片", 0, parentRender.cardText.size)

        val studentCard = studentRender.cardText.single()
        assertEquals("学生侧 0 条 Snackbar", 0, studentRender.snackbarCount)
        assertTrue(
            "学生重试卡片须指向家长配置，实际：$studentCard",
            studentCard.contains(OCR_PARENT_SETUP_HINT),
        )
        assertFalse(
            "学生重试卡片不得出现去设置引导，实际：$studentCard",
            studentCard.contains(OCR_SETTINGS_ACTION_LABEL),
        )
        assertTrue(
            "学生重试卡片不得残留设置页字样，实际：$studentCard",
            STUDENT_FORBIDDEN_MARKERS.none { studentCard.contains(it) },
        )
    }

    // ---- 测试工具 ----

    private fun entryViewModel(role: Role?): HomeworkEntryViewModel = HomeworkEntryViewModel(
        homeworkRepository = NoopHomeworkRepository(),
        authRepository = FakeEntryAuthRepository(sessionOf(role)),
        ocrHandler = HomeworkOcrHandler(
            ocrRecognizer = recognizer,
            ocrConfigStore = FakeOcrConfigStore(OcrConfig()),
            pendingOcrRepository = pendingRepository,
            fileStore = fileStore,
            clock = FixedClock(),
        ),
        speechToText = NoopSpeechToText(),
        fileStore = fileStore,
        clock = FixedClock(),
        zoneId = zone,
    )

    private fun sessionOf(role: Role?): SessionState = when (role) {
        Role.PARENT -> SessionState(role = Role.PARENT, parentId = PARENT_ID, studentId = null)
        Role.STUDENT -> SessionState(role = Role.STUDENT, parentId = PARENT_ID, studentId = STUDENT_ID)
        null -> SessionState.NONE
    }

    /** 写入一张本地图片并返回路径（模拟相册选图已复制到应用缓存） */
    private fun localImage(name: String = "picked.jpg"): String {
        val file = File(tempDir, name)
        file.writeBytes(byteArrayOf(9, 8, 7, 6))
        return file.absolutePath
    }

    /** 采集一次性事件：提示文案 + 未配置事件携带的底层文案 */
    private fun collectNotices(viewModel: HomeworkEntryViewModel, scope: CoroutineScope): CollectedNotices {
        val sink = CollectedNotices()
        sink.collector = scope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is HomeworkEntryEvent.ShowMessage -> sink.messages += event.message
                    is HomeworkEntryEvent.NeedsOcrConfiguration -> sink.needsConfiguration += event.message
                    is HomeworkEntryEvent.Saved -> sink.messages += event.message
                    is HomeworkEntryEvent.LaunchCamera -> Unit
                }
            }
        }
        return sink
    }

    /**
     * 复刻 UI 渲染分支（[HomeworkEntryRoute] 的 NeedsOcrConfiguration 分支）的消费逻辑，
     * 且**消费生产代码的同一推导函数** [ocrSetupGuidanceAction]（两个分支都不跳过）：
     * - 学生 → 1 张就地卡片：[cardText] = `action.cardText`，Snackbar 计数保持 0；
     * - 家长/会话失效 → 恰好一次 Snackbar：[messages]/[actionLabels] =
     *   `action.snackbarMessage` / `action.actionLabel`，仅在 `performAction` 且存在动作时计一次导航。
     *
     * 用途：① 断言「事件原文 + 渲染入口」的组合结果；② 捕获「学生实际文案 ≠ 收敛结果」
     * （如渲染分支误取静态短句）这类缺口——跳过学生分支或另写一份推导都抓不到。
     */
    private class OcrNoticeRenderCounter {
        val messages = mutableListOf<String>()
        val actionLabels = mutableListOf<String?>()

        /** 学生侧就地卡片的实际正文（复刻 Route 的 `ocrSetupHint = action.cardText`） */
        val cardText = mutableListOf<String>()
        var snackbarCount = 0
            private set
        var navigationCount = 0
            private set

        fun renderAll(
            notices: CollectedNotices,
            viewModel: HomeworkEntryViewModel,
            hasSettingsEntry: Boolean,
            performAction: Boolean = false,
        ) {
            val guidance = ocrSetupGuidance(viewModel.uiState.value.role, hasSettingsEntry)
            notices.needsConfiguration.forEach { baseMessage ->
                val action = ocrSetupGuidanceAction(guidance, baseMessage)
                when (guidance) {
                    // 学生：唯一通道是就地卡片，正文取生产推导产物（不是静态短句）
                    is OcrSetupGuidance.StudentAskParent -> action.cardText?.let { cardText += it }

                    is OcrSetupGuidance.Parent, OcrSetupGuidance.SessionInvalid -> {
                        snackbarCount++
                        messages += action.snackbarMessage
                        actionLabels += action.actionLabel
                        if (performAction && action.actionLabel != null) {
                            navigationCount++
                        }
                    }
                }
            }
        }
    }

    /** 事件采集结果（含取消句柄，避免测试结束时协程悬挂） */
    private class CollectedNotices {
        val messages = mutableListOf<String>()
        val needsConfiguration = mutableListOf<String>()
        lateinit var collector: Job
    }

    private companion object Constants {

        const val PARENT_ID = 1L
        const val STUDENT_ID = 2L

        val PENDING_STATUS: PendingOcrStatus = PendingOcrStatus.PENDING

        /** 未配置时 Handler 给出的**底层原文**（事件携带；由渲染入口按角色改写） */
        const val EXPECTED_RAW_NOT_CONFIGURED =
            "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"

        /**
         * 家长会话最终可见文案：**等于底层原文**——「去设置」只作为 Snackbar 的 actionLabel
         * （按钮）呈现，不写进正文，因此既无重复后缀、也不在正文里声称可点击。
         */
        const val EXPECTED_PARENT_NOT_CONFIGURED_NOTICE = EXPECTED_RAW_NOT_CONFIGURED

        /** 指向设置页的文案标记：学生侧任何提示中都不得出现 */
        val STUDENT_FORBIDDEN_MARKERS = listOf("设置页", "去设置", "设置中")
    }

    /** 固定时钟（业务时区 2023-11-15） */
    private class FixedClock : Clock {
        override fun currentTimeMillis(): Long = 1_700_000_000_000L
    }

    /** 语音替身：本用例不涉及语音链路 */
    private class NoopSpeechToText : SpeechToText {
        override fun startListening(): Flow<SpeechToText.SpeechEvent> = flowOf()
        override fun stopListening() = Unit
        override fun release() = Unit
    }

    /** 作业仓库替身：录入页的识别提示链路不触达仓库，误用即失败 */
    private class NoopHomeworkRepository : HomeworkRepository {

        override fun observeHomework(studentId: Long): Flow<List<HomeworkItem>> = flowOf(emptyList())

        override suspend fun listHomework(studentId: Long): List<HomeworkItem> = emptyList()

        override suspend fun getHomework(homeworkId: Long): HomeworkItem? = null

        override suspend fun addHomework(
            template: HomeworkTemplate,
            studentId: Long,
        ): AddHomeworkResult = unsupported()

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

        override suspend fun clearSchedule(
            homeworkId: Long,
            sessionRole: Role,
        ): HomeworkOperationResult = unsupported()

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

        override suspend fun deleteHomework(
            homeworkId: Long,
            sessionRole: Role,
        ): HomeworkOperationResult = unsupported()

        override suspend fun markPending(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        override suspend fun startProgress(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        override suspend fun complete(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        override suspend fun reopen(homeworkId: Long, sessionRole: Role): HomeworkStatusResult =
            unsupported()

        private fun <T> unsupported(): T =
            throw UnsupportedOperationException("本用例不涉及该仓库能力")
    }

    /** auth 仓库替身：仅提供会话与学生档案读取，其余能力误用即失败 */
    private class FakeEntryAuthRepository(private val session: SessionState) : AuthRepository {

        private val sessionFlow = MutableStateFlow(session)

        override fun observeSession(): Flow<SessionState> = sessionFlow

        override suspend fun currentSession(): SessionState = sessionFlow.value

        override suspend fun logout() {
            sessionFlow.value = SessionState.NONE
        }

        override suspend fun listStudents(parentId: Long): List<Student> = emptyList()

        override suspend fun getStudent(studentId: Long): Student? = Student(
            id = studentId,
            parentAccountId = PARENT_ID,
            name = "小明",
            verificationCode = "123456",
            createdAt = Instant.ofEpochMilli(0L),
        )

        override suspend fun registerParent(account: String, password: String): ParentRegisterResult =
            unsupported()

        override suspend fun loginParent(account: String, password: String): ParentLoginResult =
            unsupported()

        override suspend fun addStudent(parentId: Long, name: String): AddStudentResult = unsupported()

        override suspend fun renameStudent(studentId: Long, newName: String): RenameStudentResult =
            unsupported()

        override suspend fun deleteStudent(studentId: Long): DeleteStudentResult = unsupported()

        override suspend fun resetStudentVerificationCode(
            studentId: Long,
        ): UpdateVerificationCodeResult = unsupported()

        override suspend fun updateStudentVerificationCode(
            studentId: Long,
            newCode: String,
        ): UpdateVerificationCodeResult = unsupported()

        override suspend fun enterAsStudent(
            parentAccount: String,
            verificationCode: String,
        ): StudentEnterResult = unsupported()

        private fun <T> unsupported(): T =
            throw UnsupportedOperationException("本用例不涉及该 auth 能力")
    }
}
