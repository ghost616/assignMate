package com.assignmate.app.core.data.ocr

import com.assignmate.app.core.data.db.FakeOcrRetryTaskDao
import com.assignmate.app.core.data.db.OcrRetryTaskRepositoryImpl
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 识别缓存清理契约单测（[OcrCacheCleanerImpl]）：
 * 记录被清空且图片删除被调用、返回路径与库内一致、空缓存幂等、真实文件删除下缺失文件不抛错。
 */
class OcrCacheCleanerTest {

    private lateinit var tempDir: File
    private lateinit var dao: FakeOcrRetryTaskDao
    private lateinit var repository: OcrRetryTaskRepositoryImpl

    private var tick = 1_700_000_000_000L

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ocr-cache-cleaner-test").toFile()
        dao = FakeOcrRetryTaskDao()
        repository = OcrRetryTaskRepositoryImpl(dao)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun newFile(name: String): File =
        File(tempDir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private suspend fun addTask(path: String, status: PendingOcrStatus) {
        repository.add(
            PendingOcrTask(localImagePath = path, createdAtMillis = tick++, status = status),
        )
    }

    @Test
    fun `清空识别缓存删除记录并调用图片删除`() = runTest {
        val cleaner = FakeOcrImageFileCleaner()
        val files = PendingOcrStatus.entries.map { status ->
            newFile("${status.name.lowercase()}.jpg").also { addTask(it.absolutePath, status) }
        }
        val expected = files.mapTo(mutableSetOf()) { it.absolutePath }

        val removed = OcrCacheCleanerImpl(repository, cleaner).clear()

        assertEquals(expected, removed)
        assertEquals("4 种状态的图片都应被请求删除", expected.sorted(), cleaner.deletedPaths.sorted())
        assertTrue("库内记录应被清空", dao.snapshot().isEmpty())
        PendingOcrStatus.entries.forEach { status ->
            assertTrue(repository.loadByStatus(status).isEmpty())
        }
    }

    @Test
    fun `缓存为空时返回空集合且不触发图片删除`() = runTest {
        val cleaner = FakeOcrImageFileCleaner()

        val removed = OcrCacheCleanerImpl(repository, cleaner).clear()

        assertTrue(removed.isEmpty())
        assertTrue(cleaner.deletedPaths.isEmpty())
        assertTrue(dao.snapshot().isEmpty())
    }

    @Test
    fun `真实文件删除下图片被清理且缺失文件不抛错`() = runTest {
        val existing = newFile("existing.jpg")
        val missing = File(tempDir, "missing.jpg")
        addTask(existing.absolutePath, PendingOcrStatus.PENDING)
        addTask(missing.absolutePath, PendingOcrStatus.SUCCEEDED)

        val removed = OcrCacheCleanerImpl(repository, AndroidOcrImageFileCleaner()).clear()

        assertEquals(setOf(existing.absolutePath, missing.absolutePath), removed)
        assertFalse("存在的图片应被真实删除", existing.exists())
        assertTrue(dao.snapshot().isEmpty())
    }
}