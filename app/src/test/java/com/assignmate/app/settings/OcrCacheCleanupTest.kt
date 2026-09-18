package com.assignmate.app.settings

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.ocr.AndroidOcrImageFileCleaner
import com.assignmate.app.core.data.ocr.OcrCacheCleanerImpl
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.settings.data.GuardedOcrCacheCleaner
import com.assignmate.app.settings.data.OcrCacheCleanResult
import com.assignmate.app.settings.data.OcrCacheRepositoryImpl
import com.assignmate.app.settings.domain.DataCleanupResult
import com.assignmate.app.settings.domain.DataCleanupService
import com.assignmate.app.settings.domain.OcrCleanupSnapshot
import com.assignmate.app.settings.domain.SettingsDenialReason
import com.assignmate.app.settings.domain.SettingsRoleGuard
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据清理面板单测：**条数统计（覆盖 4 种状态）**、二次确认文案、执行后记录与图片均被清理、
 * 空态文案与按钮置灰口径、**文件缺失容错**、以及学生会话清理被拒。
 *
 * 与生产链路同构：core 的 [OcrCacheCleanerImpl]（先清记录、再删图片）+ settings 的
 * [GuardedOcrCacheCleaner]（分权）+ [OcrCacheRepositoryImpl]（4 状态计数）+ [DataCleanupService]（编排）。
 * 图片删除分别以「记录型替身」与「真实平台实现（[AndroidOcrImageFileCleaner]，临时目录）」两条路径验证。
 */
class OcrCacheCleanupTest {

    private val parentSession = SessionState(role = Role.PARENT, parentId = 1L, studentId = null)
    private val studentSession = SessionState(role = Role.STUDENT, parentId = 1L, studentId = 2L)

    private class Env(
        val pending: FakePendingOcrRepository,
        val imageCleaner: RecordingOcrImageFileCleaner,
        val repository: OcrCacheRepositoryImpl,
        val service: DataCleanupService,
    )

    private fun newEnv(
        session: SessionState = SessionState(role = Role.PARENT, parentId = 1L),
        imageFileCleaner: RecordingOcrImageFileCleaner = RecordingOcrImageFileCleaner(),
    ): Env {
        val pending = FakePendingOcrRepository()
        val auth = FakeSettingsAuthRepository(session)
        val repository = OcrCacheRepositoryImpl(pending)
        val cleaner = GuardedOcrCacheCleaner(
            OcrCacheCleanerImpl(pending, imageFileCleaner),
            SettingsRoleGuard(auth),
        )
        return Env(
            pending = pending,
            imageCleaner = imageFileCleaner,
            repository = repository,
            service = DataCleanupService(repository, repository, cleaner),
        )
    }

    /** 4 种状态各登记一条 */
    private fun FakePendingOcrRepository.insertAllStatuses() {
        PendingOcrStatus.entries.forEach { status ->
            insert("/images/${status.name.lowercase()}.jpg", status)
        }
    }

    // ---- 条数统计 ----

    @Test
    fun `条数统计覆盖全部 4 种状态`() = runTest {
        val env = newEnv()
        env.pending.insertAllStatuses()

        assertEquals(4, env.repository.reloadCount())
    }

    @Test
    fun `只统计待识别与识别中会漏掉成功与失败态`() = runTest {
        val env = newEnv()
        env.pending.insert("/images/done.jpg", PendingOcrStatus.SUCCEEDED)
        env.pending.insert("/images/failed.jpg", PendingOcrStatus.FAILED)

        assertEquals(
            "面板口径必须含 SUCCEEDED/FAILED，否则清理会删到未展示的内容",
            2,
            env.repository.reloadCount(),
        )
    }

    @Test
    fun `计数随库内变化自动重算`() = runTest {
        val env = newEnv()
        val counts = mutableListOf<Int>()
        val job = launch { env.repository.countUpdates().collect { counts += it } }
        testScheduler.advanceUntilIdle()

        env.pending.insert("/images/a.jpg", PendingOcrStatus.PENDING)
        testScheduler.advanceUntilIdle()
        env.pending.insert("/images/b.jpg", PendingOcrStatus.FAILED)
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertTrue("应随变更重算并发射计数，实际：$counts", counts.contains(2))
        assertEquals("首次订阅应先给出当前计数", 0, counts.first())
    }

    @Test
    fun `空库计数为零且空态文案固定`() = runTest {
        val env = newEnv()

        assertEquals(0, env.repository.reloadCount())
        assertTrue(OcrCleanupSnapshot().isEmpty)
        assertEquals("暂无可清理内容", OcrCleanupSnapshot.EMPTY_HINT)
    }

    @Test
    fun `二次确认文案与条数严格一致`() {
        assertEquals("将删除 3 条识别任务（含图片），不可恢复", OcrCleanupSnapshot(recordCount = 3).confirmMessage)
        assertEquals("将删除 0 条识别任务（含图片），不可恢复", OcrCleanupSnapshot().confirmMessage)
        assertFalse(OcrCleanupSnapshot(recordCount = 1).isEmpty)
    }

    @Test
    fun `服务重算后快照与库内条数一致`() = runTest {
        val env = newEnv()
        env.pending.insertAllStatuses()

        val count = env.service.reload()

        assertEquals(4, count)
        assertEquals(4, env.service.snapshot.value.recordCount)
        assertEquals(0, env.service.cleanedCount.value)
    }

    // ---- 执行清理 ----

    @Test
    fun `清理后记录与图片均被删除`() = runTest {
        val env = newEnv()
        env.pending.insert("/images/a.jpg", PendingOcrStatus.PENDING)
        env.pending.insert("/images/b.jpg", PendingOcrStatus.PROCESSING)
        env.pending.insert("/images/c.jpg", PendingOcrStatus.SUCCEEDED)
        env.pending.insert("/images/d.jpg", PendingOcrStatus.FAILED)

        val result = env.service.clear()

        assertEquals(DataCleanupResult.Executed, result)
        assertTrue("数据库记录应被清空", env.pending.snapshot().isEmpty())
        assertEquals(
            "图片文件必须一并删除，不得只删库",
            setOf("/images/a.jpg", "/images/b.jpg", "/images/c.jpg", "/images/d.jpg"),
            env.imageCleaner.deletedPaths.toSet(),
        )
    }

    @Test
    fun `清理回报条数与确认弹窗条数同源`() = runTest {
        val env = newEnv()
        env.pending.insertAllStatuses()
        // 同一张图重复登记：条数 5、去重图片 4
        env.pending.insert("/images/pending.jpg", PendingOcrStatus.PENDING)

        env.service.clear()

        assertEquals("回报的应是条数口径", 5, env.service.cleanedCount.value)
        assertEquals(0, env.service.snapshot.value.recordCount)
    }

    @Test
    fun `清理后计数刷新为零且空态成立`() = runTest {
        val env = newEnv()
        env.pending.insertAllStatuses()

        env.service.clear()

        assertEquals(0, env.service.snapshot.value.recordCount)
        assertTrue(env.service.snapshot.value.isEmpty)
    }

    @Test
    fun `空库清理幂等且不报错`() = runTest {
        val env = newEnv()

        assertEquals(DataCleanupResult.Executed, env.service.clear())
        assertEquals(DataCleanupResult.Executed, env.service.clear())
        assertEquals(0, env.service.cleanedCount.value)
        assertTrue(env.imageCleaner.deletedPaths.isEmpty())
    }

    @Test
    fun `清理后新登记的任务不受影响`() = runTest {
        val env = newEnv()
        env.pending.insert("/images/old.jpg", PendingOcrStatus.PENDING)
        env.service.clear()

        env.pending.insert("/images/new.jpg", PendingOcrStatus.PENDING)

        assertEquals(1, env.repository.reloadCount())
        assertEquals(setOf("/images/new.jpg"), env.pending.snapshot().map { it.localImagePath }.toSet())
    }

    // ---- 真实文件删除与缺失容错 ----

    @Test
    fun `真实文件清理 存在的图片被删除 缺失文件不抛错`() = runTest {
        val dir = Files.createTempDirectory("settings-cleanup").toFile()
        try {
            val existing = File(dir, "shot.jpg").apply { writeText("fake-image-bytes") }
            val missing = File(dir, "gone.jpg")
            val pending = FakePendingOcrRepository()
            pending.insert(existing.absolutePath, PendingOcrStatus.SUCCEEDED)
            pending.insert(missing.absolutePath, PendingOcrStatus.FAILED)
            val auth = FakeSettingsAuthRepository(parentSession)
            val statistics = OcrCacheRepositoryImpl(pending)
            val service = DataCleanupService(
                statistics,
                statistics,
                GuardedOcrCacheCleaner(
                    OcrCacheCleanerImpl(pending, AndroidOcrImageFileCleaner()),
                    SettingsRoleGuard(auth),
                ),
            )

            val result = service.clear()

            assertEquals(DataCleanupResult.Executed, result)
            assertFalse("真实存在的图片应被删除", existing.exists())
            assertTrue("记录应被清空（缺失文件不得造成残留）", pending.snapshot().isEmpty())
            assertEquals(0, service.snapshot.value.recordCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `图片路径指向目录时不误删目录`() = runTest {
        val dir = Files.createTempDirectory("settings-cleanup-dir").toFile()
        try {
            val pending = FakePendingOcrRepository()
            pending.insert(dir.absolutePath, PendingOcrStatus.PENDING)

            OcrCacheCleanerImpl(pending, AndroidOcrImageFileCleaner()).clear()

            assertTrue("目录不得被当作图片误删", dir.isDirectory)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `批量删除逐条委托且顺序与去重路径一致`() = runTest {
        val recordingCleaner = RecordingOcrImageFileCleaner()
        val pending = FakePendingOcrRepository()
        pending.insert("/images/a.jpg", PendingOcrStatus.PENDING)
        pending.insert("/images/b.jpg", PendingOcrStatus.FAILED)
        // 同一张图重复登记：条数 3、待删路径去重为 2
        pending.insert("/images/a.jpg", PendingOcrStatus.SUCCEEDED)

        val removed = OcrCacheCleanerImpl(pending, recordingCleaner).clear()

        assertEquals(setOf("/images/a.jpg", "/images/b.jpg"), removed)
        assertEquals(2, recordingCleaner.deletedPaths.size)
        assertTrue(pending.snapshot().isEmpty())
    }

    // ---- 分权 ----

    @Test
    fun `学生会话清理被拒且不删记录不删图片`() = runTest {
        val env = newEnv(session = studentSession)
        env.pending.insertAllStatuses()

        val result = env.service.clear()

        assertTrue(result is DataCleanupResult.Denied)
        assertEquals(
            SettingsDenialReason.STUDENT_FORBIDDEN,
            (result as DataCleanupResult.Denied).reason,
        )
        assertEquals(4, env.pending.snapshot().size)
        assertTrue(env.imageCleaner.deletedPaths.isEmpty())
    }

    @Test
    fun `未登录会话清理被拒`() = runTest {
        val env = newEnv(session = SessionState(role = null))
        env.pending.insert("/images/a.jpg", PendingOcrStatus.PENDING)

        val result = env.service.clear()

        assertEquals(
            SettingsDenialReason.NOT_SIGNED_IN,
            (result as DataCleanupResult.Denied).reason,
        )
        assertEquals(1, env.pending.snapshot().size)
    }

    @Test
    fun `清理用例直接被调用时同样分权拦截`() = runTest {
        val pending = FakePendingOcrRepository()
        pending.insert("/images/a.jpg", PendingOcrStatus.PENDING)
        val auth = FakeSettingsAuthRepository(studentSession)
        val cleaner = GuardedOcrCacheCleaner(
            OcrCacheCleanerImpl(pending, RecordingOcrImageFileCleaner()),
            SettingsRoleGuard(auth),
        )

        assertTrue(cleaner.clear() is OcrCacheCleanResult.Denied)
        assertEquals(1, pending.snapshot().size)
    }

    @Test
    fun `重算后可观察条数与当前条数一致`() = runTest {
        val env = newEnv()
        env.pending.insert("/images/a.jpg", PendingOcrStatus.PENDING)

        // 条数流由统计实现持有：先重算（等价于进入面板），再比对流值与一次性取数
        val reloaded = env.repository.reloadCount()

        assertEquals(1, reloaded)
        assertEquals(1, env.repository.recordCount.first())
        assertEquals(1, env.service.currentRecordCount())
    }
}