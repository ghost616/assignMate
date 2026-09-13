package com.assignmate.app.timer.data

import com.assignmate.app.core.data.db.dao.PauseRecordDao
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版暂停明细 DAO（单元测试替身）：以 MutableList 模拟 pause_record 表。
 *
 * 关键行为与真实 SQL 对齐：
 * - 查询排序按 pause_start_at ASC；
 * - [findUnfinishedBySession] 只取 pause_end_at 为 null 的记录（按开始时刻倒序取最新一条）；
 * - [finishPause] 仅对未结束记录生效（重复调用不覆盖已有结束时刻）；
 * - [writeCount] 累计写操作次数，供「越权被拒绝时不得产生任何写入」的断言使用。
 */
class FakePauseRecordDao : PauseRecordDao {

    private val rows = mutableListOf<PauseRecordEntity>()
    private var nextId = 1L
    private val revision = MutableStateFlow(0)

    /** 累计写操作次数（只增不减，供越权「零写入」断言比对前后快照） */
    var writeCount: Int = 0
        private set

    override suspend fun insert(item: PauseRecordEntity): Long {
        val id = nextId++
        rows += item.copy(id = id)
        writeCount++
        touch()
        return id
    }

    override suspend fun insertAll(items: List<PauseRecordEntity>): List<Long> = items.map { insert(it) }

    override suspend fun update(item: PauseRecordEntity) {
        val index = rows.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            rows[index] = item
            writeCount++
            touch()
        }
    }

    override suspend fun delete(item: PauseRecordEntity) {
        if (rows.removeAll { it.id == item.id }) {
            writeCount++
            touch()
        }
    }

    override fun observeBySession(sessionId: Long): Flow<List<PauseRecordEntity>> =
        revision.map { orderedBySession(sessionId) }

    override suspend fun loadBySession(sessionId: Long): List<PauseRecordEntity> =
        orderedBySession(sessionId)

    override suspend fun loadByHomework(homeworkId: Long): List<PauseRecordEntity> =
        rows.filter { it.homeworkId == homeworkId }.sortedBy { it.pauseStartAt }

    override suspend fun findUnfinishedBySession(sessionId: Long): PauseRecordEntity? =
        rows.filter { it.sessionId == sessionId && it.pauseEndAt == null }
            .maxByOrNull { it.pauseStartAt }

    override suspend fun finishPause(pauseRecordId: Long, pauseEndAtMillis: Long) {
        val index = rows.indexOfFirst { it.id == pauseRecordId && it.pauseEndAt == null }
        if (index >= 0) {
            rows[index] = rows[index].copy(pauseEndAt = Instant.ofEpochMilli(pauseEndAtMillis))
            writeCount++
            touch()
        }
    }

    /** 测试断言辅助：返回全部暂停明细快照（按开始时刻升序） */
    fun all(): List<PauseRecordEntity> = rows.sortedBy { it.pauseStartAt }

    private fun touch() {
        revision.value += 1
    }

    private fun orderedBySession(sessionId: Long): List<PauseRecordEntity> =
        rows.filter { it.sessionId == sessionId }.sortedBy { it.pauseStartAt }
}
