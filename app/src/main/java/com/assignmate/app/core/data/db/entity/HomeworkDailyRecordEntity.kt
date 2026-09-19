package com.assignmate.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * 作业每天详情表 homework_daily_record（homework/timer/stats 模块的持久化基础，core 持有表结构与迁移）。
 *
 * 语义：一条作业项（[HomeworkItemEntity]）不再按阶段逐日展开成多条作业，
 * 而是「一条作业项 + 每天一条详情」；同时**所有作业**（当天作业 TODAY 与阶段作业 STAGE）
 * 都按自然日记录每日执行数据，供清单逐日展示、计时回填与历史统计。
 *
 * 字段语义与枚举取值约定（枚举类型由 homework 模块在 domain 层定义，库内统一以枚举名 String
 * 存储，便于后续新增取值而不必迁移表结构）：
 * - status：未开始 NOT_STARTED / 进行中 IN_PROGRESS / 已完成 COMPLETED / 未完成（缺卡）MISSED；
 * - epochDay：业务自然日（epochDay = 业务时区下的 LocalDate.toEpochDay()），
 *   由调用方按业务时区折算后写入；**禁止用「毫秒 / 86400000」这类 UTC 折算**，
 *   否则凌晨时段的记录会落到前一天；
 * - estimatedMinutes / actualMinutes：预估时长与实际时长（分钟），均可空；
 * - pauseCount / pausedTotalMinutes：当日累计暂停次数与暂停总时长（分钟，默认 0）。
 *
 * 归属关系：homework_id 外键关联 homework_item.id，作业删除时级联删除其每日详情
 * （ON DELETE CASCADE）；student_id 为冗余归属维度（便于按学生 + 自然日批量查询/统计），
 * 不加外键以免与 homework_item -> student 的级联路径重复。
 *
 * 唯一约束与索引：
 * - 唯一索引 (homework_id, epoch_day)：保证「同一作业同一天至多一条」，
 *   重复写入由 SQLite 唯一约束拦截（写入前应经 DAO 的 upsertInTransaction 先查后写）；
 * - (student_id, epoch_day)：支撑「某学生某天的全部作业每日详情」批量查询；
 * - homework_id：支撑「某作业全部天的详情」查询与级联删除。
 */
@Entity(
    tableName = "homework_daily_record",
    foreignKeys = [
        ForeignKey(
            entity = HomeworkItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["homework_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["homework_id", "epoch_day"], unique = true),
        Index(value = ["student_id", "epoch_day"]),
        Index(value = ["homework_id"]),
    ],
)
data class HomeworkDailyRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 归属作业 id（外键 -> homework_item.id，级联删除） */
    @ColumnInfo(name = "homework_id")
    val homeworkId: Long,

    /** 归属学生 id（冗余维度，便于按学生 + 自然日批量查询） */
    @ColumnInfo(name = "student_id")
    val studentId: Long,

    /** 业务自然日（epochDay，按业务时区折算；禁止 UTC 毫秒折算） */
    @ColumnInfo(name = "epoch_day")
    val epochDay: Long,

    /** 每日状态：NOT_STARTED / IN_PROGRESS / COMPLETED / MISSED */
    @ColumnInfo(name = "status")
    val status: String,

    /** 当日开始时刻（epoch 毫秒）；未开始为 null */
    @ColumnInfo(name = "started_at")
    val startedAt: Instant? = null,

    /** 当日预估时长（分钟，可空） */
    @ColumnInfo(name = "estimated_minutes")
    val estimatedMinutes: Int? = null,

    /** 当日实际时长（分钟，可空，默认由 timer 回填） */
    @ColumnInfo(name = "actual_minutes")
    val actualMinutes: Int? = null,

    /** 当日累计暂停次数（默认 0） */
    @ColumnInfo(name = "pause_count", defaultValue = "0")
    val pauseCount: Int = 0,

    /** 当日累计暂停总时长（分钟，默认 0） */
    @ColumnInfo(name = "paused_total_minutes", defaultValue = "0")
    val pausedTotalMinutes: Int = 0,

    /** 当日完成时刻（epoch 毫秒）；未完成为 null */
    @ColumnInfo(name = "finished_at")
    val finishedAt: Instant? = null,

    /** 创建时刻（epoch 毫秒，Instant <-> Long 由 AppTypeConverters 转换） */
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)
