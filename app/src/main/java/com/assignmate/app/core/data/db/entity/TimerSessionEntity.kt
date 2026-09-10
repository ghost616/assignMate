package com.assignmate.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * 计时执行会话表 timer_session（timer 模块持久化基础，core 持有表结构与迁移）。
 *
 * 语义：一次作业的“开始计时 -> 结束”过程对应一行会话记录；会话中的每次暂停以多条
 * [PauseRecordEntity]（pause_record）明细表达，本表只保存暂停汇总，便于计时页快速恢复现场。
 *
 * 字段语义与枚举取值约定（枚举类型由 timer 模块在 domain 层定义，库内统一以枚举名 String
 * 存储，便于后续新增取值而不必迁移表结构）：
 * - status：计时中 RUNNING / 暂停中 PAUSED / 已结束 FINISHED；
 * - pausedTotalMillis：会话内累计暂停毫秒数（默认 0，汇总快照，明细以 pause_record 为准）；
 * - pauseCount：会话内累计暂停次数（默认 0）；
 * - finishedAt：结束时刻，会话进行中（RUNNING/PAUSED）为 null。
 *
 * 归属关系：homework_id 外键关联 homework_item.id，作业删除时级联删除其计时会话
 * （ON DELETE CASCADE）；student_id、parent_account_id 为冗余归属维度（parent_account_id
 * 便于家长侧聚合展示，不加外键以免与 homework_item 的级联路径重复）。
 *
 * 索引：homework_id、student_id 支撑按作业/学生查询会话；(student_id, status) 支撑
 * “查询某学生当前进行中的会话”（进入计时页恢复未结束会话）。
 */
@Entity(
    tableName = "timer_session",
    foreignKeys = [
        ForeignKey(
            entity = HomeworkItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["homework_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["homework_id"]),
        Index(value = ["student_id"]),
        Index(value = ["student_id", "status"]),
    ],
)
data class TimerSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 归属作业 id（外键 -> homework_item.id，级联删除） */
    @ColumnInfo(name = "homework_id")
    val homeworkId: Long,

    /** 归属学生 id（冗余维度，便于按学生查询会话与统计） */
    @ColumnInfo(name = "student_id")
    val studentId: Long,

    /** 归属家长账号 id（冗余维度，便于家长侧聚合展示） */
    @ColumnInfo(name = "parent_account_id")
    val parentAccountId: Long,

    /** 计时开始时刻（epoch 毫秒，Instant <-> Long 由 AppTypeConverters 转换） */
    @ColumnInfo(name = "started_at")
    val startedAt: Instant,

    /** 计时结束时刻（epoch 毫秒）；会话未结束时为 null */
    @ColumnInfo(name = "finished_at")
    val finishedAt: Instant? = null,

    /** 累计暂停毫秒数（默认 0，汇总快照；明细以 pause_record 为准） */
    @ColumnInfo(name = "paused_total_millis", defaultValue = "0")
    val pausedTotalMillis: Long = 0L,

    /** 累计暂停次数（默认 0） */
    @ColumnInfo(name = "pause_count", defaultValue = "0")
    val pauseCount: Int = 0,

    /** 会话状态：RUNNING / PAUSED / FINISHED */
    @ColumnInfo(name = "status")
    val status: String,
)
