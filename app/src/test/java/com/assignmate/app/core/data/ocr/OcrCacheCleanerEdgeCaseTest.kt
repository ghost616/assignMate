package com.assignmate.app.core.data.ocr

import com.assignmate.app.core.data.db.FakeOcrRetryTaskDao
import com.assignmate.app.core.data.db.OcrRetryTaskRepositoryImpl
import com.assignmate.app.core.domain.ocr.OcrImageFileCleaner
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
 * 识别缓存清理的补充边界单测（OcrCacheCleanerImpl / OcrImageFileCleaner.deleteAll）：
 *
 * 1. 清理前记录必须已从库中移除（删除实现回看 DAO 只能看到"已清空"的世界），
 *    以此约束实现顺序为「先清记录、后删文件」，顺序颠倒时本用例会失败；
 * 2. 传给图片删除的路径集合与返回集合、与库内被清理路径严格一致（集合语义）；
 * 3. 同一路径多条记录时只请求删除一次；
 * 4. 批量删除空集合不抛错；
 * 5. 目录不被误删而普通文件仍被删除。
 */
class OcrCacheCleanerEdgeCaseTest {

    private lateinit var tempDir: File
    private lateinit var dao: FakeOcrRetryTaskDao
    private lateinit var repository: OcrRetryTaskRepositoryImpl

    private var tick = 1_700_000_000_000L

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ocr-cache-cleaner-edge-test").toFile()
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

    /** 记录删除调用，并在调用当时检查「记录是否已被清空」，用于约束清理顺序 */
    private class OrderAssertingCleaner(
        private val dao: FakeOcrRetryTaskDao,
    ) : OcrImageFileCleaner {

        var rowsAtDeleteTime: Int? = null

        override suspend fun delete(path: String) {
            if (rowsAtDeleteTime == null) {
                rowsAtDeleteTime = dao.snapshot().size
            }
        }
    }

    @Test
    fun `先清空记录再删除图片文件`() = runTest {
        val file = newFile("order.jpg")
        addTask(file.absolutePath, PendingOcrStatus.PENDING)
        addTask("/images/other.jpg", PendingOcrStatus.FAILED)
        val orderAsserting = OrderAssertingCleaner(dao)

        val removed = OcrCacheCleanerImpl(repository, orderAsserting).clear()

        assertEquals(2, removed.size)
        assertEquals("删除图片时库内记录应已被清空", 0, orderAsserting.rowsAtDeleteTime)
        assertTrue(dao.snapshot().isEmpty())
    }

    @Test
    fun `传入图片删除的路径集合与返回值严格一致`() = runTest {
        val a = newFile("same.jpg")
        val b = newFile("other.jpg")
        // 同一路径两条记录（重复登记）+ 另一路径，覆盖去重与多状态
        addTask(a.absolutePath, PendingOcrStatus.PENDING)
        addTask(a.absolutePath, PendingOcrStatus.SUCCEEDED)
        addTask(b.absolutePath, PendingOcrStatus.PROCESSING)
        val cleaner = FakeOcrImageFileCleaner()

        val removed = OcrCacheCleanerImpl(repository, cleaner).clear()

        assertEquals(setOf(a.absolutePath, b.absolutePath), removed)
        assertEquals("重复路径只应请求删除一次", removed.size, cleaner.deletedPaths.size)
        assertEquals(removed, cleaner.deletedPaths.toSet())
        assertTrue("替身不落盘，文件仍存在（真实删除由 AndroidOcrImageFileCleaner 用例覆盖）", a.exists())
    }

    @Test
    fun `批量删除空集合不抛错`() = runTest {
        AndroidOcrImageFileCleaner().deleteAll(emptyList())

        assertTrue(tempDir.exists())
    }

    @Test
    fun `目录不被误删而普通文件仍被删除`() = runTest {
        val dir = File(tempDir, "nested").apply { mkdirs() }
        val inner = File(dir, "inner.jpg").apply { writeBytes(byteArrayOf(1)) }
        val cleaner = AndroidOcrImageFileCleaner()

        cleaner.deleteAll(listOf(dir.absolutePath, inner.absolutePath))

        assertTrue("目录本身不应被删除", dir.isDirectory)
        assertFalse("普通文件仍应被删除", inner.exists())
    }
}
