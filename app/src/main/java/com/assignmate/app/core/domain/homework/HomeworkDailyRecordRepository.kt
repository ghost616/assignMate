package com.assignmate.app.core.domain.homework

import kotlinx.coroutines.flow.Flow

/**
 * 作业每天详情的数据访问契约（core 对外提供，homework / timer / stats 只依赖本接口，不直接碰 Room DAO）。
 *
 * 模型与接口关系：
 * - 领域模型 [HomeworkDailyRecord] / [HomeworkDayStatus] 在 core.domain.homework；
 * - 默认实现 HomeworkDailyRecordRepositoryImpl（core.data.db）经 Hilt 绑定（见 core.di.DailyRecordModule），
 *   业务模块可覆写绑定以替换实现。
 *
 * 关键约定：
 * - epochDay 为**业务自然日**，实现内部按注入的业务时区 [ZoneId] 折算，绝不使用 UTC 毫秒折算；
 * - upsert 语义：同一（作业 + 自然日）至多一条，已存在则原地更新且保留原创建时刻。
 */
interface HomeworkDailyRecordRepository {

    /** 观察某学生某自然日的全部作业每日详情（按作业 id 升序） */
    fun observeByStudentAndDay(studentId: Long, epochDay: Long): Flow<List<HomeworkDailyRecord>>

    /** 读取某学生某自然日的全部作业每日详情快照（排序口径与观察版一致） */
    suspend fun loadByStudentAndDay(studentId: Long, epochDay: Long): List<HomeworkDailyRecord>

    /** 读取某作业在某自然日的详情；返回 null 表示当天尚无记录 */
    suspend fun find(homeworkId: Long, epochDay: Long): HomeworkDailyRecord?

    /** 读取某作业的全部天详情（按自然日升序） */
    suspend fun loadByHomework(homeworkId: Long): List<HomeworkDailyRecord>

    /** 观察某作业的全部天详情（按自然日升序） */
    fun observeByHomework(homeworkId: Long): Flow<List<HomeworkDailyRecord>>

    /**
     * 写入某作业某自然日的详情：不存在则插入，已存在则原地更新（保留原创建时刻），
     * 返回该记录的主键 id。
     */
    suspend fun upsert(record: HomeworkDailyRecord): Long

    /**
     * 便捷写入：按（作业 + 学生 + 自然日）与状态创建/更新当天详情，其余执行数据保持不变。
     *
     * 语义：
     * - 当天无记录 -> 以 [nowMillis] 为创建时刻新增；
     * - 当天已有记录 -> 只改状态，保留原创建时刻与既有执行数据。
     *
     * 注意（**两步非同事务**）：实现为「先查后写」，两步之间不构成一个数据库事务，
     * 并发写同一（作业 + 自然日）时状态遵循「最后一次写入者胜」；唯一性由唯一索引与
     * 事务化 upsert 兜底（见实现类与 DAO 的 KDoc）。需要严格原子读改写时用 [upsert]。
     *
     * @return 该记录的主键 id
     */
    suspend fun upsertStatus(
        homeworkId: Long,
        studentId: Long,
        epochDay: Long,
        status: HomeworkDayStatus,
        nowMillis: Long,
    ): Long

    /** 仅更新每日状态（记录必须已存在） */
    suspend fun updateStatus(id: Long, status: HomeworkDayStatus)

    /**
     * 更新每日执行数据（开始时刻/预估时长/实际时长/暂停汇总/完成时刻）。
     *
     * **整体覆盖语义**：所有字段一次性写入，传 null 即**清空该字段**（不是「保持原值」）。
     * 调用方只想更新其中一项时，需把其余字段按当前值原样传入（或用 [upsert] 写入完整快照），
     * 否则会把未传的预估/实际时长/完成时刻抹掉。
     */
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
     * 删除某作业的全部天详情（作业本身删除时另由外键级联清理），返回被删除条数。
     */
    suspend fun deleteByHomework(homeworkId: Long): Int

    /**
     * 把 epoch 毫秒折算为业务自然日（epochDay）。
     *
     * 口径唯一来源：实现内部持有业务时区 [ZoneId]（默认系统时区，可由 Hilt 覆写），
     * 供 homework / timer / stats 取「今天」时复用，避免各模块自建换算导致口径漂移。
     */
    fun epochDayOf(millis: Long): Long
}
