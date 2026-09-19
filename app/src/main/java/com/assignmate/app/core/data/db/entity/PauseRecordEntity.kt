package com.assignmate.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * 暂停明细表 pause_record（timer 模块持久化基础，core 持有表结构与迁移）。
 *
 * 语义：一次计时会话（[TimerSessionEntity]）中的每次暂停对应一行明细，“暂停中”表现为
 * pause_end_at 为 null 的未结束记录（同一会话至多一条，由 timer 层状态机保证）。
 * 会话级汇总（paused_total_millis / pause_count）落在 timer_session，本表用于回溯暂停过程，
 * 供 stats 模块统计“暂停次数/暂停时长分布”。
 *
 * 归属关系：session_id 外键关联 timer_session.id，会话删除时级联删除其暂停明细
 * （ON DELETE CASCADE）；homework_id 为冗余维度（便于不经会话表直接按作业聚合统计），
 * 不加外键以免与 timer_session -> homework_item 的级联路径重复。
 *
 * 自然日维度：epoch_day 为**业务自然日**（业务时区下的 LocalDate.toEpochDay()），
 * 由暂停开始时刻按业务时区折算后写入，使暂停可按（作业 + 自然日）归属统计；
 * **禁止 UTC 毫秒折算**。
 *
 * 索引：session_id 支撑“按会话读取暂停明细/查询未结束暂停”；homework_id 支撑按作业聚合统计；
 * (homework_id, epoch_day) 支撑「某作业某自然日的暂停明细」逐日聚合。
 */
@Entity(
    tableName = "pause_record",
    foreignKeys = [
        ForeignKey(
            entity = TimerSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["homework_id"]),
        Index(value = ["homework_id", "epoch_day"]),
    ],
)
data class PauseRecordEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 所属计时会话 id（外键 -> timer_session.id，级联删除） */
    @ColumnInfo(name = "session_id")
    val sessionId: Long,

    /** 归属作业 id（冗余维度，便于按作业统计暂停明细） */
    @ColumnInfo(name = "homework_id")
    val homeworkId: Long,

    /**
     * 业务自然日（epochDay，按业务时区由暂停开始时刻折算；禁止 UTC 毫秒折算）。
     *
     * **无 Kotlin 默认值**（刻意为漏传暴露编译期错误）：`@ColumnInfo(defaultValue = "0")`
     * 仅是 v4 旧库 `ALTER TABLE ADD COLUMN` 加列时的**列默认值**（旧数据可丢弃，见
     * DatabaseModule.MIGRATION_4_5），正常写入路径必须显式传入折算结果——漏传会静默写出
     * `epoch_day = 0` 的不可查询脏行。
     */
    @ColumnInfo(name = "epoch_day", defaultValue = "0")
    val epochDay: Long,

    /** 暂停开始时刻（epoch 毫秒，Instant <-> Long 由 AppTypeConverters 转换） */
    @ColumnInfo(name = "pause_start_at")
    val pauseStartAt: Instant,

    /** 暂停结束时刻（epoch 毫秒）；仍处于暂停中时为 null */
    @ColumnInfo(name = "pause_end_at")
    val pauseEndAt: Instant? = null,
)
