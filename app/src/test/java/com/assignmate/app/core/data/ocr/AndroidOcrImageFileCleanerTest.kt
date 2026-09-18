package com.assignmate.app.core.data.ocr

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 图片文件删除默认实现单测（纯 JVM，临时目录真实读写）：
 * 删除存在的文件、批量删除、文件缺失/非法路径静默容错、目录不被误删。
 */
class AndroidOcrImageFileCleanerTest {

    private lateinit var tempDir: File
    private val cleaner = AndroidOcrImageFileCleaner()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ocr-image-cleaner-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun newFile(name: String): File =
        File(tempDir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `删除存在的图片文件`() = runTest {
        val file = newFile("a.jpg")

        cleaner.delete(file.absolutePath)

        assertFalse("图片文件应被删除", file.exists())
    }

    @Test
    fun `文件不存在时静默容错`() = runTest {
        val missing = File(tempDir, "missing.jpg")

        cleaner.delete(missing.absolutePath)

        assertFalse(missing.exists())
    }

    @Test
    fun `空路径与非法路径静默容错`() = runTest {
        cleaner.delete("")
        cleaner.delete("   ")

        assertTrue(tempDir.exists())
    }

    @Test
    fun `批量删除混合存在与缺失路径`() = runTest {
        val first = newFile("first.jpg")
        val second = newFile("second.jpg")
        val missing = File(tempDir, "missing.jpg").absolutePath

        cleaner.deleteAll(listOf(first.absolutePath, missing, second.absolutePath))

        assertFalse(first.exists())
        assertFalse(second.exists())
        assertFalse(File(missing).exists())
    }

    @Test
    fun `目录路径不被误删`() = runTest {
        val dir = File(tempDir, "sub").apply { mkdirs() }
        val inner = File(dir, "inner.jpg").apply { writeBytes(byteArrayOf(1)) }

        cleaner.delete(dir.absolutePath)

        assertTrue("目录不应被删除", dir.isDirectory)
        assertTrue("目录内文件不应被删除", inner.exists())
    }
}