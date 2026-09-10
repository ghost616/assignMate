package com.assignmate.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 计时会话 DAO：数据访问只覆盖"按作业/学生检索会话"与"更新结束/暂停汇总"，
 * 计时状态机（RUNNING/PAUSED/FINISHED 流转）、暂停汇总口径、休息与提醒规则在 timer 层，
 * DAO 不承载业务判定。
 */
@Dao
interface TimerSessionDao : BaseDao<TimerSessionEntity> {

    /** 观察某作业的全部计时会话（按开始时刻升序），供作业详情页展示执行历史 */
    @Query(
        "SELECT * FROM timer_session WHERE homework_id = :homeworkId " +
            "ORDER BY started_at ASC",
    )
    fun observeByHomework(homeworkId: Long): Flow<List<TimerSessionEntity>>

    /** 读取某作业的全部计时会话快照（按开始时刻升序，排序口径与 [observeByHomework] 一致） */
    @Query(
        "SELECT * FROM timer_session WHERE homework_id = :homeworkId " +
            "ORDER BY started_at ASC",
    )
    suspend fun loadByHomework(homeworkId: Long): List<TimerSessionEntity>

    /** 读取某学生的全部计时会话（按开始时刻升序），供按时段统计使用 */
    @Query(
        "SELECT * FROM timer_session WHERE student_id = :studentId " +
            "ORDER BY started_at ASC",
    )
    suspend fun loadByStudent(studentId: Long): List<TimerSessionEntity>

    /** 按学生 + 状态查询会话（按开始时刻升序），如"查询未结束会话以恢复计时现场" */
    @Query(
        "SELECT * FROM timer_session WHERE student_id = :studentId AND status = :status " +
            "ORDER BY started_at ASC",
    )
    suspend fun loadByStudentAndStatus(studentId: Long, status: String): List<TimerSessionEntity>

    /** 按主键查询（返回 null 表示不存在） */
    @Query("SELECT * FROM timer_session WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TimerSessionEntity?

    /** 结束会话：写入结束时刻并置为终态（未结束会话的 finished_at 始终为 null） */
    @Query(
        "UPDATE timer_session SET finished_at = :finishedAtMillis, status = :status " +
            "WHERE id = :sessionId",
    )
    suspend fun updateFinish(sessionId: Long, finishedAtMillis: Long, status: String)

    /** 更新暂停汇总与当前状态（累计暂停毫秒/次数由 timer 层计算后写入） */
    @Query(
        "UPDATE timer_session SET paused_total_millis = :pausedTotalMillis, " +
            "pause_count = :pauseCount, status = :status WHERE id = :sessionId",
    )
    suspend fun updatePauseSummary(
        sessionId: Long,
        pausedTotalMillis: Long,
        pauseCount: Int,
        status: String,
    )

    /** 仅更新会话状态（如 RUNNING -> PAUSED 或 PAUSED -> RUNNING），不动暂停汇总 */
    @Query("UPDATE timer_session SET status = :status WHERE id = :sessionId")
    suspend fun updateStatus(sessionId: Long, status: String)
}