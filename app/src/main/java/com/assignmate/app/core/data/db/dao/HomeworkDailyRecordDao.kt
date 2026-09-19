package com.assignmate.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.assignmate.app.core.data.db.entity.HomeworkDailyRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * 作业每天详情 DAO：按（作业 + 业务自然日）读写，并提供按学生 + 自然日、按作业的批量查询。
 *
 * 职责边界：只做数据访问。每日状态的流转规则（何时算「未完成【缺卡】」）、
 * 预估/实际时长口径、暂停汇总口径都在 homework / timer / stats 层，DAO 不承载业务判定。
 *
 * 自然日口径：epoch_day 一律由调用方按**业务时区**折算后传入（见 core.data.db 的
 * HomeworkDailyRecordRepositoryImpl），DAO 不做任何时区换算，禁止 UTC 毫秒折算。
 *
 * 唯一约束：表上存在唯一索引 (homework_id, epoch_day)，保证「同一作业同一天只有一条」；
 * 需要「存在则更新、不存在则插入」时用 [upsertInTransaction]（事务内先查后写）。
 */
@Dao
interface HomeworkDailyRecordDao : BaseDao<HomeworkDailyRecordEntity> {

    /** 观察某学生某自然日的全部作业每日详情（按作业 id 升序，便于与清单顺序对齐） */
    @Query(
        "SELECT * FROM homework_daily_record WHERE student_id = :studentId " +
            "AND epoch_day = :epochDay ORDER BY homework_id ASC",
    )
    fun observeByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): Flow<List<HomeworkDailyRecordEntity>>

    /** 读取某学生某自然日的全部作业每日详情快照（排序口径与观察版一致） */
    @Query(
        "SELECT * FROM homework_daily_record WHERE student_id = :studentId " +
            "AND epoch_day = :epochDay ORDER BY homework_id ASC",
    )
    suspend fun loadByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): List<HomeworkDailyRecordEntity>

    /** 读取某作业在某自然日的详情（同一作业同一天至多一条，返回 null 表示当天尚无记录） */
    @Query(
        "SELECT * FROM homework_daily_record WHERE homework_id = :homeworkId " +
            "AND epoch_day = :epochDay LIMIT 1",
    )
    suspend fun findByHomeworkAndDay(
        homeworkId: Long,
        epochDay: Long,
    ): HomeworkDailyRecordEntity?

    /** 读取某作业的全部天详情（按自然日升序），供作业详情页逐日展示 */
    @Query(
        "SELECT * FROM homework_daily_record WHERE homework_id = :homeworkId " +
            "ORDER BY epoch_day ASC",
    )
    suspend fun loadByHomework(homeworkId: Long): List<HomeworkDailyRecordEntity>

    /** 观察某作业的全部天详情（按自然日升序），供页面随写入自动刷新 */
    @Query(
        "SELECT * FROM homework_daily_record WHERE homework_id = :homeworkId " +
            "ORDER BY epoch_day ASC",
    )
    fun observeByHomework(homeworkId: Long): Flow<List<HomeworkDailyRecordEntity>>

    /**
     * 事务化「存在则更新、不存在则插入」：保证同一作业同一天只有一条详情，
     * 且并发写入不会因唯一约束冲突而抛出异常（事务内先查后写）。
     *
     * @return 写入记录的主键 id
     */
    @Transaction
    suspend fun upsertInTransaction(record: HomeworkDailyRecordEntity): Long {
        val existing = findByHomeworkAndDay(record.homeworkId, record.epochDay)
        return if (existing == null) {
            insert(record)
        } else {
            update(record.copy(id = existing.id, createdAt = existing.createdAt))
            existing.id
        }
    }

    /** 更新每日状态（如 未开始 -> 进行中 -> 已完成 / 未完成【缺卡】） */
    @Query("UPDATE homework_daily_record SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /**
     * 更新每日执行数据（开始时刻/预估时长/实际时长/暂停汇总/完成时刻一并写入）。
     *
     * **整体覆盖语义**：SQL 为全字段 SET，传 null 即把该列写为 NULL（不是「保持原值」）；
     * 调用方只改其中一项时须把其余字段按当前值原样传入。
     */
    @Query(
        "UPDATE homework_daily_record SET started_at = :startedAtMillis, " +
            "estimated_minutes = :estimatedMinutes, actual_minutes = :actualMinutes, " +
            "pause_count = :pauseCount, paused_total_minutes = :pausedTotalMinutes, " +
            "finished_at = :finishedAtMillis WHERE id = :id",
    )
    suspend fun updateExecution(
        id: Long,
        startedAtMillis: Long?,
        estimatedMinutes: Int?,
        actualMinutes: Int?,
        pauseCount: Int,
        pausedTotalMinutes: Int,
        finishedAtMillis: Long?,
    )

    /**
     * 删除某作业的全部天详情。
     *
     * 说明：作业删除时其每日详情已由 homework_id 外键 ON DELETE CASCADE 自动清理，
     * 本方法用于「保留作业、仅清空其逐日记录」的场景（并作为级联语义的显式入口）。
     *
     * @return 被删除的记录条数
     */
    @Query("DELETE FROM homework_daily_record WHERE homework_id = :homeworkId")
    suspend fun deleteByHomework(homeworkId: Long): Int
}
