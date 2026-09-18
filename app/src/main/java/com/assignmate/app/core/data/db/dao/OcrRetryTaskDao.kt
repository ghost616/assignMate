package com.assignmate.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
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

    /** 读取全表记录（按登记时刻升序），供「清空识别缓存」前收集图片本地路径 */
    @Query("SELECT * FROM ocr_retry_task ORDER BY created_at ASC")
    suspend fun loadAll(): List<OcrRetryTaskEntity>

    /** 全表清空（不区分状态，含历史遗留的未知状态行），返回被删除的行数 */
    @Query("DELETE FROM ocr_retry_task")
    suspend fun deleteAll(): Int

    /**
     * 事务化的「先取回全部记录、再清空全表」：供「清空识别缓存」使用。
     *
     * 为什么放在 DAO：调用方需要「拿到待删图片路径」与「记录被删除」两件事原子完成，
     * 否则读与写之间新登记的任务会成为孤儿（图片路径已不在返回集合中，文件却留在磁盘）。
     *
     * 为什么用全表清空而不是按状态筛选：PENDING/PROCESSING/SUCCEEDED/FAILED 四种状态全部要清，
     * 且状态列是 String，历史脏数据可能落在未知取值上；全表清空才能保证「清空」语义彻底，
     * 返回值与库内被删除的记录严格一致（不会留下引用已返回路径之外记录的残留行）。
     *
     * 注意：Room 会为 @Transaction 注解的默认方法生成「在单事务内执行」的实现，
     * 因此这里保持为接口默认方法而不是抽象方法（见 HomeworkItemDao 的同类写法）。
     */
    @Transaction
    suspend fun clearAllInTransaction(): List<OcrRetryTaskEntity> {
        val removed = loadAll()
        deleteAll()
        return removed
    }
}
