package com.assignmate.app.homework.data

import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版「作业每天详情」仓库（单元测试替身）：
 *
 * 复刻 Room 实现的关键语义——同一（作业 + 自然日）至多一条（upsert 原地更新且保留原创建时刻）、
 * 读取按自然日升序、epochDay 按注入的业务时区折算（禁止 UTC 毫秒折算）。
 *
 * 供仓库层的「开始 / 完成 / 撤销完成 → 每天详情」链路与阶段进度推导可确定性验证。
 */
class FakeHomeworkDailyRecordRepository(
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
) : HomeworkDailyRecordRepository {

    private val rows = mutableListOf<HomeworkDailyRecord>()
    private var nextId = 1L

    /** Room 的 Flow 查询在数据变更后重新发射：以 StateFlow 模拟同一行为 */
    private val revision = MutableStateFlow(0)

    /** 测试断言辅助：全部记录快照 */
    fun all(): List<HomeworkDailyRecord> = rows.toList()

    override fun observeByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): Flow<List<HomeworkDailyRecord>> = revision.map {
        rows.filter { row -> row.studentId == studentId && row.epochDay == epochDay }
            .sortedBy { row -> row.homeworkId }
    }

    override suspend fun loadByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): List<HomeworkDailyRecord> =
        rows.filter { row -> row.studentId == studentId && row.epochDay == epochDay }
            .sortedBy { row -> row.homeworkId }

    override suspend fun find(homeworkId: Long, epochDay: Long): HomeworkDailyRecord? =
        rows.firstOrNull { row -> row.homeworkId == homeworkId && row.epochDay == epochDay }

    override suspend fun loadByHomework(homeworkId: Long): List<HomeworkDailyRecord> =
        rows.filter { row -> row.homeworkId == homeworkId }.sortedBy { row -> row.epochDay }

    override fun observeByHomework(homeworkId: Long): Flow<List<HomeworkDailyRecord>> = revision.map {
        rows.filter { row -> row.homeworkId == homeworkId }.sortedBy { row -> row.epochDay }
    }

    override suspend fun upsert(record: HomeworkDailyRecord): Long {
        val index = rows.indexOfFirst { row ->
            row.homeworkId == record.homeworkId && row.epochDay == record.epochDay
        }
        return if (index >= 0) {
            // 原地更新且保留原创建时刻与主键（与 Room 实现同口径）
            val existing = rows[index]
            rows[index] = record.copy(id = existing.id, createdAtMillis = existing.createdAtMillis)
            revision.value += 1
            existing.id
        } else {
            val id = if (record.id != 0L) record.id else nextId++
            rows += record.copy(id = id)
            revision.value += 1
            id
        }
    }

    override suspend fun upsertStatus(
        homeworkId: Long,
        studentId: Long,
        epochDay: Long,
        status: HomeworkDayStatus,
        nowMillis: Long,
    ): Long {
        val existing = find(homeworkId, epochDay)
        return upsert(
            existing?.copy(status = status)
                ?: HomeworkDailyRecord(
                    homeworkId = homeworkId,
                    studentId = studentId,
                    epochDay = epochDay,
                    status = status,
                    createdAtMillis = nowMillis,
                ),
        )
    }

    override suspend fun updateStatus(id: Long, status: HomeworkDayStatus) {
        val index = rows.indexOfFirst { row -> row.id == id }
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
        val index = rows.indexOfFirst { row -> row.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(
                startedAtMillis = startedAtMillis,
                estimatedMinutes = estimatedMinutes,
                actualMinutes = actualMinutes,
                pauseCount = pauseCount,
                pausedTotalMinutes = pausedTotalMinutes,
                finishedAtMillis = finishedAtMillis,
            )
            revision.value += 1
        }
    }

    override suspend fun deleteByHomework(homeworkId: Long): Int {
        val removed = rows.count { row -> row.homeworkId == homeworkId }
        rows.removeAll { row -> row.homeworkId == homeworkId }
        if (removed > 0) {
            revision.value += 1
        }
        return removed
    }

    override fun epochDayOf(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().toEpochDay()
}
