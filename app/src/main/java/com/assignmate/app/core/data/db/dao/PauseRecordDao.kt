package com.assignmate.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 暂停明细 DAO：按会话/作业检索暂停过程，并提供"查询未结束暂停"用于恢复现场。
 * 暂停时长计算与是否允许暂停等业务规则在 timer 层，DAO 只负责数据访问。
 */
@Dao
interface PauseRecordDao : BaseDao<PauseRecordEntity> {

    /** 观察某会话的全部暂停明细（按暂停开始时刻升序），供计时页展示暂停记录 */
    @Query(
        "SELECT * FROM pause_record WHERE session_id = :sessionId " +
            "ORDER BY pause_start_at ASC",
    )
    fun observeBySession(sessionId: Long): Flow<List<PauseRecordEntity>>

    /** 读取某会话的全部暂停明细（按暂停开始时刻升序），供结束会话时汇总暂停时长 */
    @Query(
        "SELECT * FROM pause_record WHERE session_id = :sessionId " +
            "ORDER BY pause_start_at ASC",
    )
    suspend fun loadBySession(sessionId: Long): List<PauseRecordEntity>

    /** 读取某作业的全部暂停明细（按暂停开始时刻升序），供按作业维度统计使用 */
    @Query(
        "SELECT * FROM pause_record WHERE homework_id = :homeworkId " +
            "ORDER BY pause_start_at ASC",
    )
    suspend fun loadByHomework(homeworkId: Long): List<PauseRecordEntity>

    /**
     * 查询某会话中未结束的暂停记录（pause_end_at 为 NULL，返回 null 表示当前未暂停）。
     * 同一会话至多一条未结束记录，由 timer 层状态机保证；查询仍以开始时刻倒序取最新一条兜底。
     */
    @Query(
        "SELECT * FROM pause_record WHERE session_id = :sessionId AND pause_end_at IS NULL " +
            "ORDER BY pause_start_at DESC LIMIT 1",
    )
    suspend fun findUnfinishedBySession(sessionId: Long): PauseRecordEntity?

    /** 结束暂停：写入暂停结束时刻（仅对未结束记录生效，重复调用不会覆盖已有结束时刻） */
    @Query(
        "UPDATE pause_record SET pause_end_at = :pauseEndAtMillis " +
            "WHERE id = :pauseRecordId AND pause_end_at IS NULL",
    )
    suspend fun finishPause(pauseRecordId: Long, pauseEndAtMillis: Long)
}