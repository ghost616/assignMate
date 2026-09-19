package com.assignmate.app.core.data.db

import com.assignmate.app.core.data.db.dao.HomeworkDailyRecordDao
import com.assignmate.app.core.data.db.entity.HomeworkDailyRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版 [HomeworkDailyRecordDao]（单元测试替身）：语义与 Room + SQLite 实现对齐——
 *
 * - 自增主键（id == 0 时分配新 id）；
 * - 唯一索引 (homework_id, epoch_day)：同一作业同一天**重复插入直接抛错**，
 *   与 SQLite 的 UNIQUE 约束行为一致（正常写入路径由 upsertInTransaction 先查后写规避）；
 * - **不覆写** `upsertInTransaction`：让接口默认方法里的真实「先查后写」逻辑在测试中执行；
 * - [deleteByHomework] 与 [cascadeDeleteByHomework] 模拟 ON DELETE CASCADE 的清理结果。
 */
class FakeHomeworkDailyRecordDao : HomeworkDailyRecordDao {

    private val rows = mutableListOf<HomeworkDailyRecordEntity>()
    private var nextId = 1L
    private val revision = MutableStateFlow(0)

    /** 库内快照（按自然日升序，同日按主键升序），供断言库内真实结果 */
    fun snapshot(): List<HomeworkDailyRecordEntity> =
        rows.sortedWith(compareBy({ it.epochDay }, { it.id }))

    override suspend fun insert(item: HomeworkDailyRecordEntity): Long {
        require(
            rows.none { it.homeworkId == item.homeworkId && it.epochDay == item.epochDay },
        ) { "违反唯一约束 (homework_id, epoch_day)" }
        val id = if (item.id == 0L) nextId++ else item.id
        rows += item.copy(id = id)
        revision.value += 1
        return id
    }

    override suspend fun insertAll(items: List<HomeworkDailyRecordEntity>): List<Long> =
        items.map { insert(it) }

    override suspend fun update(item: HomeworkDailyRecordEntity) {
        val index = rows.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            rows[index] = item
            revision.value += 1
        }
    }

    override suspend fun delete(item: HomeworkDailyRecordEntity) {
        rows.removeAll { it.id == item.id }
        revision.value += 1
    }

    override fun observeByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): Flow<List<HomeworkDailyRecordEntity>> = revision.map {
        rows.filter { it.studentId == studentId && it.epochDay == epochDay }
            .sortedBy { it.homeworkId }
    }

    override suspend fun loadByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): List<HomeworkDailyRecordEntity> =
        rows.filter { it.studentId == studentId && it.epochDay == epochDay }
            .sortedBy { it.homeworkId }

    override suspend fun findByHomeworkAndDay(
        homeworkId: Long,
        epochDay: Long,
    ): HomeworkDailyRecordEntity? =
        rows.firstOrNull { it.homeworkId == homeworkId && it.epochDay == epochDay }

    override suspend fun loadByHomework(homeworkId: Long): List<HomeworkDailyRecordEntity> =
        rows.filter { it.homeworkId == homeworkId }.sortedBy { it.epochDay }

    override fun observeByHomework(homeworkId: Long): Flow<List<HomeworkDailyRecordEntity>> =
        revision.map { rows.filter { it.homeworkId == homeworkId }.sortedBy { it.epochDay } }

    override suspend fun updateStatus(id: Long, status: String) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(status = status)
            revision.value += 1
        }
    }

    override suspend fun updateExecution(
        id: Long,
        startedAtMillis: Long?,
        estimatedMinutes: Int?,
        actualMinutes: Int?,
        pauseCount: Int,
        pausedTotalMinutes: Int,
        finishedAtMillis: Long?,
    ) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(
                startedAt = startedAtMillis?.let(java.time.Instant::ofEpochMilli),
                estimatedMinutes = estimatedMinutes,
                actualMinutes = actualMinutes,
                pauseCount = pauseCount,
                pausedTotalMinutes = pausedTotalMinutes,
                finishedAt = finishedAtMillis?.let(java.time.Instant::ofEpochMilli),
            )
            revision.value += 1
        }
    }

    override suspend fun deleteByHomework(homeworkId: Long): Int {
        val removed = rows.count { it.homeworkId == homeworkId }
        rows.removeAll { it.homeworkId == homeworkId }
        revision.value += 1
        return removed
    }

    /** 模拟 SQLite 外键 ON DELETE CASCADE：作业删除时其每日详情一并消失 */
    fun cascadeDeleteByHomework(homeworkId: Long): Int = run {
        val removed = rows.count { it.homeworkId == homeworkId }
        rows.removeAll { it.homeworkId == homeworkId }
        revision.value += 1
        removed
    }
}
