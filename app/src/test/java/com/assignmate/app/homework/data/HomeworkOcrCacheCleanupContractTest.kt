package com.assignmate.app.homework.data

import com.assignmate.app.core.data.ocr.AndroidOcrImageFileCleaner
import com.assignmate.app.core.data.ocr.OcrCacheCleanerImpl
import com.assignmate.app.core.domain.ocr.OcrCacheCleaner
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrImageFileCleaner
import com.assignmate.app.core.domain.ocr.OcrResult
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import com.assignmate.app.homework.ui.HomeworkOcrHandler
import com.assignmate.app.homework.ui.OcrOutcome
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 承接 core「识别缓存清理」契约的作业侧单测：调用清理入口后**任务记录被清空且图片删除被触发**。
 *
 * 接线口径（本模块不引入 feature 间硬依赖、不重复绑定）：
 * core 定义清理入口 [OcrCacheCleaner] 与其平台实现（[OcrCacheCleanerImpl] 组合
 * `PendingOcrRepository.clearAll()` + [OcrImageFileCleaner]，Hilt 绑定见 core.di.OcrModule），
 * homework 只负责把图片路径交给该契约——作业侧登记的 [PendingOcrTask.localImagePath] 就是
 * `HomeworkFileStore` 落盘的绝对路径，因此清理记录时删除的是同一批图片文件。
 * 另有「homework 提供文件删除实现」的等价接线示例（见文件末尾的 [HomeworkFileStoreImageCleaner]）：
 * 把 [OcrImageFileCleaner] 落到作业模块文件存储口径，不需要 settings/homework 互相依赖。
 *
 * 既有文件生命周期约定（HomeworkFileStore 顶部注释）不变：识别成功 / 彻底失败即删图，
 * 可重试失败转正为待重试图片，清空识别缓存只删除「被清理记录引用」的图片。
 */
class HomeworkOcrCacheCleanupContractTest {

    private lateinit var tempDir: File
    private lateinit var fileStore: FakeHomeworkFileStore
    private lateinit var recognizer: FakeOcrRecognizer
    private lateinit var pending: FakePendingOcrRepository
    private lateinit var handler: HomeworkOcrHandler

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("homework-ocr-cleanup-test").toFile()
        fileStore = FakeHomeworkFileStore(tempDir)
        recognizer = FakeOcrRecognizer()
        pending = FakePendingOcrRepository()
        handler = HomeworkOcrHandler(
            ocrRecognizer = recognizer,
            ocrConfigStore = FakeOcrConfigStore(OcrConfig()),
            pendingOcrRepository = pending,
            fileStore = fileStore,
            clock = MutableClock(1_700_000_000_000L),
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** core 契约的默认接线（真实平台图片删除实现） */
    private fun cleaner(): OcrCacheCleaner = OcrCacheCleanerImpl(pending, AndroidOcrImageFileCleaner())

    /** 写入一张本地图片并返回路径 */
    private fun localImage(name: String = "picked.jpg"): String {
        val file = File(tempDir, name)
        file.writeBytes(byteArrayOf(9, 8, 7, 6))
        return file.absolutePath
    }

    /** 构造一条已登记任务（模拟上次失败落盘后的状态） */
    private suspend fun seedTask(
        path: String,
        status: PendingOcrStatus = PendingOcrStatus.PENDING,
    ) {
        pending.add(
            PendingOcrTask(
                localImagePath = path,
                createdAtMillis = 1_700_000_000_000L,
                status = status,
            ),
        )
    }

    // ---- 契约主链路 ----

    @Test
    fun `清空识别缓存清空任务记录并删除作业图片`() = runTest {
        // 走作业侧真实登记链路：未配置识别服务 → 图片转正为待重试图片并登记 PENDING
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        val outcome = handler.recognizeLocalImage(localImage())

        assertTrue(outcome is OcrOutcome.Failed)
        assertEquals(1, handler.observePendingCount().first())
        val task = pending.all().single()
        assertTrue("登记后图片应落盘", File(task.localImagePath).isFile)

        val removed = cleaner().clear()

        assertEquals(setOf(task.localImagePath), removed)
        assertTrue("任务记录应被清空", pending.all().isEmpty())
        assertFalse("图片文件应被删除", File(task.localImagePath).exists())
        assertEquals(0, fileStore.fileCount())
        assertEquals("清空后待重试数量归零", 0, handler.observePendingCount().first())
    }

    @Test
    fun `清空识别缓存覆盖四种状态的任务图片`() = runTest {
        val paths = PendingOcrStatus.entries.associateWith { status ->
            val path = localImage("img_${status.name}.jpg")
            seedTask(path, status)
            path
        }
        assertEquals(4, pending.all().size)

        val removed = cleaner().clear()

        assertEquals(paths.values.toSet(), removed)
        assertTrue(pending.all().isEmpty())
        removed.forEach { path -> assertFalse("图片应被删除：$path", File(path).exists()) }
        assertEquals(0, fileStore.fileCount())
    }

    @Test
    fun `清空识别缓存幂等且空缓存不报错`() = runTest {
        assertTrue("空缓存返回空集合", cleaner().clear().isEmpty())

        val path = localImage()
        seedTask(path)
        assertEquals(setOf(path), cleaner().clear())
        assertTrue("再次清空返回空集合且不报错", cleaner().clear().isEmpty())
        assertTrue(pending.all().isEmpty())
    }

    @Test
    fun `未被记录引用的残留图片不被清空识别缓存误删`() = runTest {
        val referenced = localImage("pending_referenced.jpg")
        val orphan = localImage("capture_orphan.jpg")
        seedTask(referenced)

        cleaner().clear()

        assertFalse("被清理记录引用的图片应删除", File(referenced).exists())
        assertTrue(
            "残留图片由录入页启动时的 TTL 孤儿清理负责，清空识别缓存只删被记录引用的图片",
            File(orphan).isFile,
        )
    }

    // ---- 等价接线：由作业侧提供文件删除实现 ----

    @Test
    fun `作业侧文件删除实现同样在清空记录时删除图片`() = runTest {
        val path = localImage()
        seedTask(path)
        // 不引入 feature 间依赖：core 的删除抽象由作业侧文件存储口径落地（生产侧 Hilt 绑定归 core）
        val homeworkCleaner = OcrCacheCleanerImpl(pending, HomeworkFileStoreImageCleaner(fileStore))

        val removed = homeworkCleaner.clear()

        assertEquals(setOf(path), removed)
        assertTrue(pending.all().isEmpty())
        assertFalse("作业文件存储口径下图片应被删除", fileStore.exists(path))
    }

    // ---- 不回归：既有删图口径不受清理契约影响 ----

    @Test
    fun `既有识别成功与彻底失败删图口径不受清理契约影响`() = runTest {
        // 识别成功：即删图且不登记任务
        recognizer.enqueue(OcrResult.Success(text = "语文第 3 课生字各写两遍"))
        handler.recognizeLocalImage(localImage("success.jpg"))
        assertTrue(pending.all().isEmpty())
        assertEquals(0, fileStore.fileCount())

        // 彻底失败（解析失败）：删图且不登记任务
        val parseFailed = localImage("parse_failed.jpg")
        recognizer.enqueue(OcrResult.Failure.ParseError())
        handler.recognizeLocalImage(parseFailed)
        assertFalse(File(parseFailed).exists())
        assertTrue(pending.all().isEmpty())

        // 此时清空识别缓存为空操作：不抛异常、返回空集合
        assertTrue(cleaner().clear().isEmpty())
    }
}

/**
 * 「由 homework 提供文件删除实现」的等价接线示例：把 core 的 [OcrImageFileCleaner]
 * 落到作业模块的文件存储口径（[HomeworkFileStore.delete]），保持作业侧对缓存目录的唯一所有权。
 *
 * 生产侧不额外绑定：core.di.OcrModule 已是 [OcrImageFileCleaner] / [OcrCacheCleaner] 的唯一
 * @Binds 声明点（业务模块重复绑定会导致 Dagger 重复绑定编译失败），core 自身的平台实现
 * 与 [HomeworkFileStore.delete] 语义一致，故无需作业侧绑定，也不会形成 settings → homework 依赖。
 */
private class HomeworkFileStoreImageCleaner(
    private val fileStore: HomeworkFileStore,
) : OcrImageFileCleaner {

    override suspend fun delete(path: String) {
        fileStore.delete(path)
    }
}
