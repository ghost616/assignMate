package com.assignmate.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.assignmate.app.core.data.db.entity.OcrRetryTaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * 待重试 OCR 任务 DAO：单表查询，业务逻辑（状态机/重试策略）在 repository 层，不在 DAO 展开。
 */
@Dao
interface OcrRetryTaskDao : BaseDao<OcrRetryTaskEntity> {

    /** 观察指定状态集合的任务（最早登记在前） */
    @Query("SELECT * FROM ocr_retry_task WHERE status IN (:statuses) ORDER BY created_at ASC")
    fun observeByStatuses(statuses: List<String>): Flow<List<OcrRetryTaskEntity>>

    /** 按状态查询（最早登记在前） */
    @Query("SELECT * FROM ocr_retry_task WHERE status = :status ORDER BY created_at ASC")
    suspend fun loadByStatus(status: String): List<OcrRetryTaskEntity>

    /** 更新状态与重试计数 */
    @Query("UPDATE ocr_retry_task SET status = :status, retry_count = :retryCount WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, retryCount: Int)

    /** 按主键删除 */
    @Query("DELETE FROM ocr_retry_task WHERE id = :id")
    suspend fun deleteById(id: Long)
}
