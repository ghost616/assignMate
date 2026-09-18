package com.assignmate.app.core.data.db

import com.assignmate.app.core.data.db.dao.OcrRetryTaskDao
import com.assignmate.app.core.data.db.entity.OcrRetryTaskEntity
import com.assignmate.app.core.domain.ocr.PendingOcrRepository
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [PendingOcrRepository] 的 Room 默认实现：entity/domain 双向映射在此收敛，业务层不感知表结构。
 */
class OcrRetryTaskRepositoryImpl @Inject constructor(
    private val dao: OcrRetryTaskDao,
) : PendingOcrRepository {

    override suspend fun add(task: PendingOcrTask): Long =
        dao.insert(task.toEntity())

    override fun observePending(): Flow<List<PendingOcrTask>> =
        dao.observeByStatuses(listOf(PendingOcrStatus.PENDING.name, PendingOcrStatus.PROCESSING.name))
            .map { list -> list.map { it.toDomain() } }

    override suspend fun loadByStatus(status: PendingOcrStatus): List<PendingOcrTask> =
        dao.loadByStatus(status.name).map { it.toDomain() }

    override suspend fun updateStatus(id: Long, status: PendingOcrStatus, retryCount: Int) {
        dao.updateStatus(id, status.name, retryCount)
    }

    override suspend fun remove(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun clearAll(): Set<String> =
        // 事务内「先取回全部记录、再清空全表」：4 种状态（PENDING/PROCESSING/SUCCEEDED/FAILED）
        // 一并清理，返回的路径集合即本次被删除记录引用的图片，保证与库内结果一致。
        dao.clearAllInTransaction()
            .mapTo(mutableSetOf<String>()) { it.imageLocalPath }

    private fun PendingOcrTask.toEntity(): OcrRetryTaskEntity = OcrRetryTaskEntity(
        id = id,
        imageLocalPath = localImagePath,
        createdAt = Instant.ofEpochMilli(createdAtMillis),
        status = status.name,
        retryCount = retryCount,
    )

    private fun OcrRetryTaskEntity.toDomain(): PendingOcrTask = PendingOcrTask(
        id = id,
        localImagePath = imageLocalPath,
        createdAtMillis = createdAt.toEpochMilli(),
        // 未知存量状态按 PENDING 兜底，避免历史脏数据击穿状态机
        status = PendingOcrStatus.entries.firstOrNull { it.name == status } ?: PendingOcrStatus.PENDING,
        retryCount = retryCount,
    )
}
