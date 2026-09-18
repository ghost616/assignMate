package com.assignmate.app.core.data.db

import com.assignmate.app.core.data.db.entity.OcrRetryTaskEntity
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「清空识别缓存」（[OcrRetryTaskRepositoryImpl.clearAll]）契约单测：
 * 4 种状态全覆盖、空表幂等、返回路径与库内被清理记录一致、历史脏数据一并清理、清理后新任务不受影响。
 *
 * 纯 JVM：DAO 用内存替身（[FakeOcrRetryTaskDao]），不覆写 clearAllInTransaction，
 * 因此接口默认方法里的「先取回、后全表清空」真实逻辑会被执行。
 */
class OcrRetryTaskClearAllTest {

    private lateinit var dao: FakeOcrRetryTaskDao
    private lateinit var repository: OcrRetryTaskRepositoryImpl

    private var tick = 1_700_000_000_000L

    @Before
    fun setUp() {
        dao = FakeOcrRetryTaskDao()
        repository = OcrRetryTaskRepositoryImpl(dao)
    }

    private suspend fun addTask(
        path: String,
        status: PendingOcrStatus,
        createdAtMillis: Long = tick++,
    ): Long = repository.add(
        PendingOcrTask(localImagePath = path, createdAtMillis = createdAtMillis, status = status),
    )

    @Test
    fun `清空识别缓存覆盖全部 4 种状态`() = runTest {
        PendingOcrStatus.entries.forEach { status ->
            addTask("/images/${status.name.lowercase()}.jpg", status)
        }
        assertEquals(4, dao.snapshot().size)

        val removed = repository.clearAll()

        assertEquals(
            setOf(
                "/images/pending.jpg",
                "/images/processing.jpg",
                "/images/succeeded.jpg",
                "/images/failed.jpg",
            ),
            removed,
        )
        assertTrue("库内记录应被清空", dao.snapshot().isEmpty())
        PendingOcrStatus.entries.forEach { status ->
            assertTrue("状态 ${status.name} 不应有残留", repository.loadByStatus(status).isEmpty())
        }
        assertTrue(repository.observePending().first().isEmpty())
    }

    @Test
    fun `空表清空返回空集合且不报错`() = runTest {
        assertTrue(repository.clearAll().isEmpty())
        // 幂等：重复调用仍为空且不抛异常
        assertTrue(repository.clearAll().isEmpty())
        assertTrue(dao.snapshot().isEmpty())
        assertTrue(repository.observePending().first().isEmpty())
    }

    @Test
    fun `返回路径与库内被清理记录严格一致`() = runTest {
        addTask("/images/a.jpg", PendingOcrStatus.PENDING)
        addTask("/images/b.jpg", PendingOcrStatus.PROCESSING)
        // 同一张图被登记两次：返回值按路径去重，但两条记录都要被清理
        addTask("/images/a.jpg", PendingOcrStatus.SUCCEEDED)
        addTask("/images/c.jpg", PendingOcrStatus.FAILED)
        val expectedPaths = dao.snapshot().mapTo(mutableSetOf()) { it.imageLocalPath }
        val recordCountBefore = dao.snapshot().size

        val removed = repository.clearAll()

        assertEquals(4, recordCountBefore)
        assertEquals(expectedPaths, removed)
        assertTrue(dao.snapshot().isEmpty())
        PendingOcrStatus.entries.forEach { status ->
            assertTrue(repository.loadByStatus(status).isEmpty())
        }
    }

    @Test
    fun `历史遗留的未知状态记录同样被清理`() = runTest {
        // 直接写库：状态列被写成未知取值（历史脏数据）时也要清，避免留下引用图片的孤儿记录
        dao.insert(
            OcrRetryTaskEntity(
                imageLocalPath = "/images/legacy.jpg",
                createdAt = Instant.ofEpochMilli(tick++),
                status = "LEGACY_UNKNOWN",
            ),
        )
        addTask("/images/pending.jpg", PendingOcrStatus.PENDING)

        val removed = repository.clearAll()

        assertEquals(setOf("/images/legacy.jpg", "/images/pending.jpg"), removed)
        assertTrue(dao.snapshot().isEmpty())
    }

    @Test
    fun `清理后新登记的任务不受影响且可再次清空`() = runTest {
        addTask("/images/old.jpg", PendingOcrStatus.PENDING)
        assertEquals(setOf("/images/old.jpg"), repository.clearAll())

        val newId = addTask("/images/new.jpg", PendingOcrStatus.PENDING)

        assertEquals(1, dao.snapshot().size)
        assertEquals(newId, dao.snapshot().first().id)
        assertEquals(setOf("/images/new.jpg"), repository.clearAll())
        assertTrue(dao.snapshot().isEmpty())
    }
}