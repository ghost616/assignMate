package com.assignmate.app.core.data.db

import com.assignmate.app.core.data.db.dao.OcrRetryTaskDao
import com.assignmate.app.core.data.db.entity.OcrRetryTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版 [OcrRetryTaskDao]（单元测试替身）：语义与 Room 实现一致（自增主键、按登记时刻升序）。
 *
 * 注意：**不覆写** `clearAllInTransaction`，让接口默认方法里的真实事务逻辑在测试中执行
 * （先取回全部记录、再全表清空），否则测到的只是替身自己的实现。
 */
class FakeOcrRetryTaskDao : OcrRetryTaskDao {

    private val rows = mutableListOf<OcrRetryTaskEntity>()
    private var nextId = 1L
    private val revision = MutableStateFlow(0)

    /** 库内记录快照（按登记时刻升序），供断言「库内清理结果」 */
    fun snapshot(): List<OcrRetryTaskEntity> = rows.sortedBy { it.createdAt }

    override suspend fun insert(item: OcrRetryTaskEntity): Long {
        val id = if (item.id == 0L) nextId++ else item.id
        rows += item.copy(id = id)
        revision.value += 1
        return id
    }

    override suspend fun insertAll(items: List<OcrRetryTaskEntity>): List<Long> =
        items.map { insert(it) }

    override suspend fun update(item: OcrRetryTaskEntity) {
        val index = rows.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            rows[index] = item
            revision.value += 1
        }
    }

    override suspend fun delete(item: OcrRetryTaskEntity) {
        rows.removeAll { it.id == item.id }
        revision.value += 1
    }

    override fun observeByStatuses(statuses: List<String>): Flow<List<OcrRetryTaskEntity>> =
        revision.map { rows.filter { it.status in statuses }.sortedBy { row -> row.createdAt } }

    override suspend fun loadByStatus(status: String): List<OcrRetryTaskEntity> =
        rows.filter { it.status == status }.sortedBy { it.createdAt }

    override suspend fun updateStatus(id: Long, status: String, retryCount: Int) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(status = status, retryCount = retryCount)
            revision.value += 1
        }
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
        revision.value += 1
    }

    override suspend fun loadAll(): List<OcrRetryTaskEntity> = rows.sortedBy { it.createdAt }

    override suspend fun deleteAll(): Int {
        val deleted = rows.size
        rows.clear()
        revision.value += 1
        return deleted
    }
}