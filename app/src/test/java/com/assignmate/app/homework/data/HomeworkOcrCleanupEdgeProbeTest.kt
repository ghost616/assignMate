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

class HomeworkOcrCleanupEdgeProbeTest {

    private lateinit var tempDir: File
    private lateinit var fileStore: FakeHomeworkFileStore
    private lateinit var recognizer: FakeOcrRecognizer
    private lateinit var pending: FakePendingOcrRepository
    private lateinit var handler: HomeworkOcrHandler

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("homework-ocr-cleanup-probe").toFile()
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

    private fun cleaner(): OcrCacheCleaner = OcrCacheCleanerImpl(pending, AndroidOcrImageFileCleaner())

    private suspend fun seedTask(path: String, status: PendingOcrStatus = PendingOcrStatus.PENDING) {
        pending.add(
            PendingOcrTask(
                localImagePath = path,
                createdAtMillis = 1_700_000_000_000L,
                status = status,
            ),
        )
    }

    @Test
    fun `记录引用的图片缺失时清理仍清空记录且静默容错`() = runTest {
        val missing = File(tempDir, "already_gone.jpg").absolutePath
        seedTask(missing)
        assertFalse("前置条件：引用的图片确实不存在", File(missing).exists())

        val removed = cleaner().clear()

        assertEquals(setOf(missing), removed)
        assertTrue("记录仍须被清空", pending.all().isEmpty())
    }

    @Test
    fun `记录引用的路径是目录时不得删除目录且不抛异常`() = runTest {
        val dir = File(tempDir, "not_a_file").apply { mkdirs() }
        val sentinel = File(dir, "sentinel.jpg").apply { writeBytes(byteArrayOf(1)) }
        seedTask(dir.absolutePath)

        val removed = cleaner().clear()

        assertEquals(setOf(dir.absolutePath), removed)
        assertTrue("记录应被清空", pending.all().isEmpty())
        assertTrue("目录不得被删除（仅删普通文件）", dir.isDirectory)
        assertTrue("目录内文件不得被误删", sentinel.isFile)
    }

    @Test
    fun `被清理路径为空串时不误删工作目录且不抛异常`() = runTest {
        val sentinel = File(tempDir, "must_survive.jpg").apply { writeBytes(byteArrayOf(2)) }
        val marker = File(".lizhu_cleanup_probe_marker").apply { writeBytes(byteArrayOf(3)) }
        seedTask("")
        seedTask("   ")

        try {
            cleaner().clear()

            assertTrue("记录应被清空", pending.all().isEmpty())
            assertTrue("空串路径不得被解析成上级目录而误删文件", sentinel.isFile)
            assertTrue("空串/空白路径不得被解析成工作目录", marker.isFile)
        } finally {
            marker.delete()
        }
    }

    @Test
    fun `多条记录引用同一图片时路径去重且文件被删除`() = runTest {
        val path = File(tempDir, "shared.jpg").apply { writeBytes(byteArrayOf(4)) }.absolutePath
        seedTask(path, PendingOcrStatus.PENDING)
        seedTask(path, PendingOcrStatus.FAILED)

        val removed = cleaner().clear()

        assertEquals(setOf(path), removed)
        assertTrue(pending.all().isEmpty())
        assertFalse("共享图片应被删除", File(path).exists())
    }

    @Test
    fun `大批量任务清理覆盖全部记录与图片`() = runTest {
        val expected = mutableSetOf<String>()
        repeat(300) { index ->
            val path = File(tempDir, "bulk_$index.jpg").apply { writeBytes(byteArrayOf(5)) }.absolutePath
            expected += path
            seedTask(path, PendingOcrStatus.entries[index % PendingOcrStatus.entries.size])
        }
        assertEquals(300, pending.all().size)

        val removed = cleaner().clear()

        assertEquals(expected, removed)
        assertTrue(pending.all().isEmpty())
        assertEquals("全部图片应被删除", 0, fileStore.fileCount())
    }

    @Test
    fun `作业侧真实落盘图片与孤儿图片混合时只删被引用者`() = runTest {
        recognizer.enqueue(OcrResult.Failure.NotConfigured())
        handler.recognizeLocalImage(fileStore.createCaptureFile())
        val registered = pending.all().single().localImagePath
        val orphan = File(tempDir, "capture_orphan_probe.jpg").apply { writeBytes(byteArrayOf(6)) }

        val removed = cleaner().clear()

        assertEquals(setOf(registered), removed)
        assertFalse("被登记图片应删除", File(registered).exists())
        assertTrue("孤儿图片应由 TTL 清理负责，不在此处删除", orphan.isFile)
        assertEquals(1, fileStore.fileCount())
        assertEquals(0, handler.observePendingCount().first())
    }

    @Test
    fun `清理入口把路径集合交给删除抽象`() = runTest {
        val path = File(tempDir, "counting.jpg").apply { writeBytes(byteArrayOf(7)) }.absolutePath
        seedTask(path)
        val counting = CountingImageFileCleaner()

        OcrCacheCleanerImpl(pending, counting).clear()

        assertEquals("批量删除应为一次调用", 1, counting.batches.size)
        assertEquals(setOf(path), counting.batches.single())
    }

    private class CountingImageFileCleaner : OcrImageFileCleaner {

        val batches = mutableListOf<Set<String>>()

        override suspend fun delete(path: String) = Unit

        override suspend fun deleteAll(paths: Collection<String>) {
            batches += paths.toSet()
        }
    }
}
