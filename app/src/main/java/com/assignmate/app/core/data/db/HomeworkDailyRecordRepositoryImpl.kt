package com.assignmate.app.core.data.db

import com.assignmate.app.core.data.db.dao.HomeworkDailyRecordDao
import com.assignmate.app.core.data.db.entity.HomeworkDailyRecordEntity
import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [HomeworkDailyRecordRepository] 的 Room 默认实现：entity / domain 双向映射与
 * **业务自然日折算**都收敛在这里，业务模块不感知表结构与时区细节。
 *
 * @param dao 每日详情 DAO
 * @param zoneId 业务时区：epochDay 折算与「今天」口径的唯一来源。生产由
 *   core.di.DailyRecordModule 提供（**全应用唯一**的业务时区绑定，无限定 [ZoneId]）；
 *   测试可注入固定时区，业务模块不得自建第二处绑定（覆写 core 那一处即全局生效）
 */
class HomeworkDailyRecordRepositoryImpl @Inject constructor(
    private val dao: HomeworkDailyRecordDao,
    private val zoneId: ZoneId,
) : HomeworkDailyRecordRepository {

    override fun observeByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): Flow<List<HomeworkDailyRecord>> =
        dao.observeByStudentAndDay(studentId, epochDay).map { list -> list.map { it.toDomain() } }

    override suspend fun loadByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): List<HomeworkDailyRecord> =
        dao.loadByStudentAndDay(studentId, epochDay).map { it.toDomain() }

    override suspend fun find(homeworkId: Long, epochDay: Long): HomeworkDailyRecord? =
        dao.findByHomeworkAndDay(homeworkId, epochDay)?.toDomain()

    override suspend fun loadByHomework(homeworkId: Long): List<HomeworkDailyRecord> =
        dao.loadByHomework(homeworkId).map { it.toDomain() }

    override fun observeByHomework(homeworkId: Long): Flow<List<HomeworkDailyRecord>> =
        dao.observeByHomework(homeworkId).map { list -> list.map { it.toDomain() } }

    override suspend fun upsert(record: HomeworkDailyRecord): Long =
        // 事务内先查后写（见 DAO.upsertInTransaction）：同一作业同一天至多一条，
        // 已存在时保留原创建时刻（createdAt 由 DAO 侧固化）。
        dao.upsertInTransaction(record.toEntity())

    /**
     * 便捷写入：不存在则新增（创建时刻取 [nowMillis]），已存在则**仅改状态**并保留原创建时刻与执行数据。
     *
     * 注意（非同事务）：实现为「先 findByHomeworkAndDay 读、再按结果写」两步，
     * 两步之间不是同一个数据库事务——并发写入同一（作业 + 自然日）时，可能有一次读到的
     * 快照已过期。这不破坏唯一性约束（写入侧由 [HomeworkDailyRecordDao.upsertInTransaction]
     * 与 `UPDATE ... WHERE id` 兜底），但**状态字段遵循「最后一次写入者胜」**。
     * 需要「读-改-写」严格原子时请改用 [upsert]（其内部为事务化先查后写）。
     */
    override suspend fun upsertStatus(
        homeworkId: Long,
        studentId: Long,
        epochDay: Long,
        status: HomeworkDayStatus,
        nowMillis: Long,
    ): Long {
        val existing = dao.findByHomeworkAndDay(homeworkId, epochDay)
        // 已存在则原地更新状态，保留原有执行数据与创建时刻；不存在则以「当天」为创建时刻新增。
        return if (existing == null) {
            dao.upsertInTransaction(
                HomeworkDailyRecordEntity(
                    homeworkId = homeworkId,
                    studentId = studentId,
                    epochDay = epochDay,
                    status = status.name,
                    createdAt = Instant.ofEpochMilli(nowMillis),
                ),
            )
        } else {
            dao.updateStatus(existing.id, status.name)
            existing.id
        }
    }

    override suspend fun updateStatus(id: Long, status: HomeworkDayStatus) {
        dao.updateStatus(id, status.name)
    }

    /**
     * 更新每日执行数据：**整体覆盖语义**——七个字段一次性写入，
     * 传 null 即**清空该字段**（不会「保留原值」）。例如只想补开始时刻时，
     * 必须把其余字段按既有值原样传入，否则会把预估/实际时长/完成时刻抹掉。
     */
    override suspend fun updateExecution(
        id: Long,
        startedAtMillis: Long?,
        estimatedMinutes: Int?,
        actualMinutes: Int?,
        pauseCount: Int,
        pausedTotalMinutes: Int,
        finishedAtMillis: Long?,
    ) {
        dao.updateExecution(
            id = id,
            startedAtMillis = startedAtMillis,
            estimatedMinutes = estimatedMinutes,
            actualMinutes = actualMinutes,
            pauseCount = pauseCount,
            pausedTotalMinutes = pausedTotalMinutes,
            finishedAtMillis = finishedAtMillis,
        )
    }

    override suspend fun deleteByHomework(homeworkId: Long): Int = dao.deleteByHomework(homeworkId)

    override fun epochDayOf(millis: Long): Long =
        // 业务时区口径：凌晨时刻不得被折算到前一天（禁止 millis / 86_400_000 的 UTC 折算）
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().toEpochDay()

    /** 领域模型 -> 表实体：新增统一走自增主键（id = 0 交给数据库分配） */
    private fun HomeworkDailyRecord.toEntity(): HomeworkDailyRecordEntity = HomeworkDailyRecordEntity(
        id = 0L,
        homeworkId = homeworkId,
        studentId = studentId,
        epochDay = epochDay,
        status = status.name,
        startedAt = startedAtMillis?.let(Instant::ofEpochMilli),
        estimatedMinutes = estimatedMinutes,
        actualMinutes = actualMinutes,
        pauseCount = pauseCount,
        pausedTotalMinutes = pausedTotalMinutes,
        finishedAt = finishedAtMillis?.let(Instant::ofEpochMilli),
        createdAt = Instant.ofEpochMilli(createdAtMillis),
    )

    /** 表实体 -> 领域模型：未知状态名兜底「未开始」，避免历史脏数据击穿状态流转 */
    private fun HomeworkDailyRecordEntity.toDomain(): HomeworkDailyRecord = HomeworkDailyRecord(
        id = id,
        homeworkId = homeworkId,
        studentId = studentId,
        epochDay = epochDay,
        status = HomeworkDayStatus.fromRawValue(status),
        startedAtMillis = startedAt?.toEpochMilli(),
        estimatedMinutes = estimatedMinutes,
        actualMinutes = actualMinutes,
        pauseCount = pauseCount,
        pausedTotalMinutes = pausedTotalMinutes,
        finishedAtMillis = finishedAt?.toEpochMilli(),
        createdAtMillis = createdAt.toEpochMilli(),
    )
}
