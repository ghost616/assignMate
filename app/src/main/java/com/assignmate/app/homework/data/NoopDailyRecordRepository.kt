package com.assignmate.app.homework.data

import com.assignmate.app.core.domain.homework.HomeworkDailyRecord
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 「作业每天详情」的空实现（默认值注入用，非生产绑定）。
 *
 * 存在意义：让 [HomeworkRepositoryImpl] 的构造参数带默认值，既有「只依赖清单快照」的测试替身
 * 无需改动即可编译与运行（不写每天详情、读取返回空表，清单行为与旧口径完全一致）。
 *
 * 生产环境由 [com.assignmate.app.homework.di.HomeworkModule] 显式注入 core 的真实实现
 * （[com.assignmate.app.core.data.db.HomeworkDailyRecordRepositoryImpl] 经其契约绑定），
 * 因此本空实现不会被生产代码使用。
 *
 * 时区口径：构造**必须**接收业务时区（生产侧由 [HomeworkRepositoryImpl] 传入注入的业务时区），
 * 使 [epochDayOf] 与生产实现同口径——既不再把系统时区写死在实现里，也不提供
 * `ZoneId.systemDefault()` 默认值（否则覆写 core 唯一绑定后这里会落到第二个口径）。
 */
internal class NoopDailyRecordRepository(
    private val zoneId: ZoneId,
) : HomeworkDailyRecordRepository {

    override fun observeByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): Flow<List<HomeworkDailyRecord>> = flowOf(emptyList())

    override suspend fun loadByStudentAndDay(
        studentId: Long,
        epochDay: Long,
    ): List<HomeworkDailyRecord> = emptyList()

    override suspend fun find(homeworkId: Long, epochDay: Long): HomeworkDailyRecord? = null

    override suspend fun loadByHomework(homeworkId: Long): List<HomeworkDailyRecord> = emptyList()

    override fun observeByHomework(homeworkId: Long): Flow<List<HomeworkDailyRecord>> =
        flowOf(emptyList())

    override suspend fun upsert(record: HomeworkDailyRecord): Long = record.id

    override suspend fun upsertStatus(
        homeworkId: Long,
        studentId: Long,
        epochDay: Long,
        status: HomeworkDayStatus,
        nowMillis: Long,
    ): Long = 0L

    override suspend fun updateStatus(id: Long, status: HomeworkDayStatus) = Unit

    override suspend fun updateExecution(
        id: Long,
        startedAtMillis: Long?,
        estimatedMinutes: Int?,
        actualMinutes: Int?,
        pauseCount: Int,
        pausedTotalMinutes: Int,
        finishedAtMillis: Long?,
    ) = Unit

    override suspend fun deleteByHomework(homeworkId: Long): Int = 0

    /** 与业务时区口径一致的自然日折算（空实现不参与任何业务判定，仅保持接口口径一致） */
    override fun epochDayOf(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().toEpochDay()
}
