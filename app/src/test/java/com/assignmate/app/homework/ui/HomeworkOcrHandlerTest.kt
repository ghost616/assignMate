package com.assignmate.app.homework.ui

import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrResult
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import com.assignmate.app.homework.data.FakeHomeworkFileStore
import com.assignmate.app.homework.data.FakeOcrConfigStore
import com.assignmate.app.homework.data.FakeOcrRecognizer
import com.assignmate.app.homework.data.FakePendingOcrRepository
import com.assignmate.app.homework.data.ImageLoadResult
import com.assignmate.app.homework.data.MutableClock
import com.assignmate.app.homework.domain.HomeworkConstants
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 录入链路可测逻辑单测：OCR 失败分派（可重试登记 / 不可重试提示）、
 * 本地图片文件生命周期（成功与彻底失败清理、可重试失败转正）、
 * 待重试任务批量重试（含上限自动清理与 FAILED 任务可恢复重试）、孤儿文件清理。
 *
 * 依赖纯 JVM 替身（Fake OCR / Fake 待重试仓库 / Fake 文件存储），不触网。
 */
class HomeworkOcrHandlerTest {

    private lateinit var tempDir: File
    private lateinit var fileStore: FakeHomeworkFileStore
    private lateinit var recognizer: FakeOcrRecognizer
    private lateinit var pendingRepository: FakePendingOcrRepository
    private lateinit var configStore: FakeOcrConfigStore
    private lateinit var clock: MutableClock

    private val config = OcrConfig(
        apiBaseUrl = "https://example.com/v1",
        modelName = "test-model",
        apiKey = "secret",
        enabled = true,
    )

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("homework-ocr-test").toFile()
        fileStore = FakeHomeworkFileStore(tempDir)
        recognizer = FakeOcrRecognizer()
        pendingRepository = FakePendingOcrRepository()
        configStore = FakeOcrConfigStore(config)
        clock = MutableClock(1_700_000_000_000L)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun handler(): HomeworkOcrHandler = HomeworkOcrHandler(
        ocrRecognizer = recognizer,
        ocrConfigStore = configStore,
        pendingOcrRepository = pendingRepository,
        fileStore = fileStore,
        clock = clock,
    )

    /** 写入一张本地图片并返回路径 */
    private fun localImage(name: String = "picked.jpg"): String {
        val file = File(tempDir, name)
        file.writeBytes(byteArrayOf(9, 8, 7, 6))
        return file.absolutePath
    }

    /** 构造一条已登记任务（模拟上次失败落盘后的状态） */
    private suspend fun seedPendingTask(
        path: String,
        status: PendingOcrStatus = PendingOcrStatus.PENDING,
        retryCount: Int = 0,
    ): Long = pendingRepository.add(
        PendingOcrTask(
            localImagePath = path,
            createdAtMillis = clock.currentTimeMillis(),
            status = status,
            retryCount = retryCount,
        ),
    )

    // ---- 成功路径与文件生命周期 ----

    @Test
    fun `识别成功返回可编辑文本并清理本地图片`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Success(text = "语文第 3 课生字各写两遍"))

        val outcome = handler().recognizeLocalImage(path)

        assertEquals(OcrOutcome.Filled("语文第 3 课生字各写两遍"), outcome)
        assertEquals(1, recognizer.callCount)
        assertEquals(config, recognizer.lastConfig)
        // 成功即删除临时图片，避免 cacheDir 残留
        assertFalse(File(path).exists())
        assertEquals(0, fileStore.fileCount())
        assertTrue(pendingRepository.all().isEmpty())
    }

    @Test
    fun `无法读取图片时给出可读提示且不登记重试`() = runTest {
        val outcome = handler().recognizeLocalImage("/not/exists/pic.jpg")

        assertTrue(outcome is OcrOutcome.Failed)
        assertEquals(HomeworkOcrHandler.FILE_UNREADABLE_HINT, (outcome as OcrOutcome.Failed).message)
        assertFalse(outcome.needsConfiguration)
        assertEquals(0, recognizer.callCount)
        assertTrue(pendingRepository.all().isEmpty())
    }

    @Test
    fun `压缩后仍超上限时提示更换图片并清理文件`() = runTest {
        val path = localImage("huge.jpg")
        fileStore.loadResults[path] = ImageLoadResult.TooLarge

        val outcome = handler().recognizeLocalImage(path)

        assertTrue(outcome is OcrOutcome.Failed)
        assertEquals(HomeworkOcrHandler.SIZE_LIMIT_HINT, (outcome as OcrOutcome.Failed).message)
        assertEquals(0, recognizer.callCount)
        assertFalse("超限图片应被清理", File(path).exists())
    }

    @Test
    fun `图片读取走文件存储入口`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Success(text = "内容"))

        handler().recognizeLocalImage(path)

        assertEquals(1, fileStore.loadCallCount)
    }

    // ---- 失败分派 ----

    @Test
    fun `网络失败把已拍文件转正并登记待重试任务`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NetworkError())

        val outcome = handler().recognizeLocalImage(path)

        assertTrue(outcome is OcrOutcome.NeedsRetry)
        assertTrue((outcome as OcrOutcome.NeedsRetry).message.contains("需要联网"))
        val tasks = pendingRepository.all()
        assertEquals(1, tasks.size)
        assertEquals(PendingOcrStatus.PENDING, tasks.single().status)
        assertEquals(clock.currentTimeMillis(), tasks.single().createdAtMillis)
        // 转正：原临时文件被重命名（不再存在），任务指向转正后的文件（不重复复制字节）
        assertEquals(1, fileStore.promoteCallCount)
        assertFalse(File(path).exists())
        assertTrue(File(tasks.single().localImagePath).isFile)
        assertEquals(1, fileStore.fileCount())
    }

    @Test
    fun `服务端错误同样登记待重试任务`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.ServiceError(code = 503, userMessage = "服务不可用"))

        val outcome = handler().recognizeLocalImage(path)

        assertTrue(outcome is OcrOutcome.NeedsRetry)
        assertTrue((outcome as OcrOutcome.NeedsRetry).message.contains("503"))
        assertEquals(1, pendingRepository.all().size)
    }

    @Test
    fun `未配置识别服务时提示去设置页并保持待重试`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())

        val outcome = handler().recognizeLocalImage(path)

        assertTrue(outcome is OcrOutcome.Failed)
        val failed = outcome as OcrOutcome.Failed
        assertTrue(failed.needsConfiguration)
        assertTrue(failed.message.contains("设置页"))
        // 关键：未配置不丢弃图片，任务保持 PENDING，配置完成后仍可重试
        val tasks = pendingRepository.all()
        assertEquals(1, tasks.size)
        assertEquals(PendingOcrStatus.PENDING, tasks.single().status)
        assertTrue(File(tasks.single().localImagePath).isFile)
    }

    @Test
    fun `解析失败时提示更换图片并清理文件`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.ParseError())

        val outcome = handler().recognizeLocalImage(path)

        assertTrue(outcome is OcrOutcome.Failed)
        assertEquals("没认出文字，请换一张更清晰的图片", (outcome as OcrOutcome.Failed).message)
        assertTrue(pendingRepository.all().isEmpty())
        assertFalse("不可重试失败应清理图片", File(path).exists())
    }

    @Test
    fun `未知失败给出通用提示并清理文件`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.Unknown())

        val outcome = handler().recognizeLocalImage(path)

        assertTrue(outcome is OcrOutcome.Failed)
        assertEquals("识别失败，请稍后重试", (outcome as OcrOutcome.Failed).message)
        assertTrue(pendingRepository.all().isEmpty())
        assertFalse(File(path).exists())
    }

    @Test
    fun `可重试判定把未配置也纳入可重试`() {
        assertTrue(HomeworkOcrHandler.shouldRegisterRetry(OcrResult.Failure.NetworkError()))
        assertTrue(HomeworkOcrHandler.shouldRegisterRetry(OcrResult.Failure.ServiceError(500, "err")))
        // 全新安装未配置：图片必须保留可重试，不能锁死
        assertTrue(HomeworkOcrHandler.shouldRegisterRetry(OcrResult.Failure.NotConfigured()))
        assertFalse(HomeworkOcrHandler.shouldRegisterRetry(OcrResult.Failure.ParseError()))
        assertFalse(HomeworkOcrHandler.shouldRegisterRetry(OcrResult.Failure.Unknown()))
    }

    // ---- 批量重试 ----

    @Test
    fun `无待重试任务时返回空汇总`() = runTest {
        val summary = handler().retryPendingTasks()

        assertEquals(0, summary.attempted)
        assertEquals(0, summary.succeeded)
        assertNull(summary.firstText)
        assertTrue(summary.message.contains("没有待重试"))
    }

    @Test
    fun `重试成功后清理任务与图片并回填文本`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        handler().recognizeLocalImage(path)
        val task = pendingRepository.all().single()

        recognizer.enqueue(OcrResult.Success(text = "重试后的内容"))
        val summary = handler().retryPendingTasks()

        assertEquals(1, summary.attempted)
        assertEquals(1, summary.succeeded)
        assertEquals("重试后的内容", summary.firstText)
        assertTrue(pendingRepository.all().isEmpty())
        assertFalse(File(task.localImagePath).exists())
    }

    @Test
    fun `重试仍失败时任务保留为待识别且不重复登记`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        handler().recognizeLocalImage(path)

        recognizer.enqueue(OcrResult.Failure.NetworkError())
        val summary = handler().retryPendingTasks()

        assertEquals(1, summary.attempted)
        assertEquals(0, summary.succeeded)
        val task = pendingRepository.all().single()
        assertEquals(PendingOcrStatus.PENDING, task.status)
        assertEquals(1, task.retryCount)
    }

    @Test
    fun `重试撞上未配置时保留待重试并上抛未配置标记`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        handler().recognizeLocalImage(path)

        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        val summary = handler().retryPendingTasks()

        assertEquals(1, summary.attempted)
        assertEquals(0, summary.succeeded)
        // 未配置在重试链路仍属可重试：任务保持 PENDING、图片不丢，配置补齐后可直接重试
        val task = pendingRepository.all().single()
        assertEquals(PendingOcrStatus.PENDING, task.status)
        assertEquals("未配置不累加重试计数（环境问题不是任务无救）", 0, task.retryCount)
        assertTrue("配置补齐后重试依据的图片必须仍在", File(task.localImagePath).isFile)
        assertTrue("未配置事实必须上抛，供调用方按会话角色收敛提示", summary.needsConfiguration)
        assertTrue(summary.message.contains("设置页"))
    }

    @Test
    fun `未配置反复重试达上限后任务与图片仍不得被清理`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        handler().recognizeLocalImage(path)
        val task = pendingRepository.all().single()

        // 家长未配置期间连点重试：次数超过 MAX_OCR_RETRY_COUNT 也不得清理（图片必须留给配置补齐后重试）
        val attempts = HomeworkConstants.MAX_OCR_RETRY_COUNT + 2
        repeat(attempts) { recognizer.enqueue(OcrResult.Failure.NotConfigured()) }
        repeat(attempts) { handler().retryPendingTasks() }

        val remaining = pendingRepository.all()
        assertEquals("未配置属可重试，反复重试后任务仍应保留", 1, remaining.size)
        assertEquals(PendingOcrStatus.PENDING, remaining.single().status)
        assertEquals("未配置始终不累加计数", 0, remaining.single().retryCount)
        assertTrue("图片不得被清理", File(task.localImagePath).isFile)
    }

    @Test
    fun `未配置期间反复重试后配置补齐仍可直接重试成功`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        handler().recognizeLocalImage(path)
        val task = pendingRepository.all().single()
        repeat(HomeworkConstants.MAX_OCR_RETRY_COUNT + 2) {
            recognizer.enqueue(OcrResult.Failure.NotConfigured())
            handler().retryPendingTasks()
        }

        configStore.value = config.copy(apiKey = "configured")
        recognizer.enqueue(OcrResult.Success(text = "配置后终于识别成功"))
        val summary = handler().retryPendingTasks()

        assertEquals(1, summary.succeeded)
        assertEquals("配置后终于识别成功", summary.firstText)
        assertTrue(pendingRepository.all().isEmpty())
        assertFalse(File(task.localImagePath).exists())
    }

    @Test
    fun `重试的可重试失败不误报未配置标记`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        handler().recognizeLocalImage(path)

        recognizer.enqueue(OcrResult.Failure.NetworkError())
        val summary = handler().retryPendingTasks()

        assertEquals(PendingOcrStatus.PENDING, pendingRepository.all().single().status)
        assertFalse("网络失败不得被当成未配置（否则文案会被错误分流）", summary.needsConfiguration)
    }

    @Test
    fun `未配置失败登记的任务在配置完成后可重试成功`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        val outcome = handler().recognizeLocalImage(path)
        assertTrue(outcome is OcrOutcome.Failed)
        val task = pendingRepository.all().single()

        configStore.value = config.copy(apiKey = "configured")
        recognizer.enqueue(OcrResult.Success(text = "配置后识别成功"))
        val summary = handler().retryPendingTasks()

        assertEquals(1, summary.succeeded)
        assertEquals("配置后识别成功", summary.firstText)
        assertTrue(pendingRepository.all().isEmpty())
        assertFalse(File(task.localImagePath).exists())
    }

    @Test
    fun `图片丢失时清理任务并提示重新拍照`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        handler().recognizeLocalImage(path)
        val task = pendingRepository.all().single()
        File(task.localImagePath).delete()

        val summary = handler().retryPendingTasks()

        assertTrue(summary.message.contains("重新拍照"))
        assertTrue(pendingRepository.all().isEmpty())
    }

    @Test
    fun `不可重试失败在重试时置为识别失败但保留任务`() = runTest {
        val path = localImage()
        recognizer.enqueue(OcrResult.Failure.NetworkError())
        handler().recognizeLocalImage(path)

        recognizer.enqueue(OcrResult.Failure.ParseError())
        val summary = handler().retryPendingTasks()

        assertEquals(0, summary.succeeded)
        val task = pendingRepository.all().single()
        assertEquals(PendingOcrStatus.FAILED, task.status)
        assertEquals(1, task.retryCount)
    }

    @Test
    fun `FAILED 任务仍在重试入口中可恢复`() = runTest {
        val path = localImage()
        seedPendingTask(path, status = PendingOcrStatus.FAILED, retryCount = 1)

        recognizer.enqueue(OcrResult.Success(text = "恢复识别"))
        val summary = handler().retryPendingTasks()

        assertEquals(1, summary.attempted)
        assertEquals(1, summary.succeeded)
        assertTrue(pendingRepository.all().isEmpty())
    }

    @Test
    fun `重试次数超上限的任务自动清理任务与图片`() = runTest {
        val path = localImage()
        seedPendingTask(
            path = path,
            status = PendingOcrStatus.FAILED,
            retryCount = HomeworkConstants.MAX_OCR_RETRY_COUNT,
        )

        val summary = handler().retryPendingTasks()

        assertEquals(0, summary.succeeded)
        assertTrue(summary.message.contains("清理"))
        assertTrue(pendingRepository.all().isEmpty())
        assertFalse(File(path).exists())
        // 超限直接清理，未发起识别请求
        assertEquals(0, recognizer.callCount)
    }

    @Test
    fun `待重试数量随登记变化`() = runTest {
        val h = handler()
        assertEquals(0, h.observePendingCount().first())

        recognizer.enqueue(OcrResult.Failure.NetworkError())
        h.recognizeLocalImage(localImage())
        assertEquals(1, h.observePendingCount().first())
    }

    // ---- 孤儿文件清理 ----

    @Test
    fun `孤儿文件清理删除无引用图片并保留被引用图片`() = runTest {
        val referenced = localImage("pending_referenced.jpg")
        localImage("capture_orphan.jpg")
        seedPendingTask(referenced)

        val removed = handler().cleanupOrphanFiles()

        assertEquals(1, removed)
        assertTrue(File(referenced).isFile)
        assertEquals(1, fileStore.fileCount())
    }

    @Test
    fun `孤儿文件清理保留全部任务状态的图片`() = runTest {
        val pending = localImage("p1.jpg")
        val processing = localImage("p2.jpg")
        val failed = localImage("p3.jpg")
        seedPendingTask(pending, PendingOcrStatus.PENDING)
        seedPendingTask(processing, PendingOcrStatus.PROCESSING)
        seedPendingTask(failed, PendingOcrStatus.FAILED)

        val removed = handler().cleanupOrphanFiles()

        assertEquals(0, removed)
        assertEquals(3, fileStore.fileCount())
    }
}
