package com.assignmate.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * 作业项表 homework_item（homework 模块持久化基础，core 持有表结构与迁移）。
 *
 * 字段语义与枚举取值约定（枚举类型由 homework 模块在 domain 层定义，库内统一以枚举名 String 存储，
 * 便于后续新增取值而不必迁移表结构）：
 * - type：当天作业 TODAY / 阶段作业 STAGE；
 * - stageRange：阶段范围 ONE_WEEK / TWO_WEEKS / THREE_WEEKS / ONE_MONTH（type=TODAY 时为 null）；
 * - status：已记录 RECORDED / 待完成 PENDING / 进行中 IN_PROGRESS / 已完成 COMPLETED；
 * - createdByRole：录入者角色 PARENT / STUDENT（供权限规则判定）。
 *
 * 归属关系：student_id 外键关联 student.id，学生档案删除时级联删除其作业（ON DELETE CASCADE）；
 * parent_account_id 为冗余归属维度（便于按家长聚合统计），不加外键以免与 student 级联路径重复。
 *
 * 索引：parent_account_id、student_id 及常用查询组合 (student_id, status)、(student_id, priority)
 * （清单按优先级排序）、(student_id, start_time)（已排定时间段查询/防冲突校验）。
 */
@Entity(
    tableName = "homework_item",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["id"],
            childColumns = ["student_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["parent_account_id"]),
        Index(value = ["student_id"]),
        Index(value = ["student_id", "status"]),
        Index(value = ["student_id", "priority"]),
        Index(value = ["student_id", "start_time"]),
    ],
)
data class HomeworkItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 归属家长账号 id（冗余维度，便于按家长聚合统计与权限过滤） */
    @ColumnInfo(name = "parent_account_id")
    val parentAccountId: Long,

    /** 归属学生 id（外键 -> student.id，级联删除） */
    @ColumnInfo(name = "student_id")
    val studentId: Long,

    /** 作业内容（可编辑文本，支持 OCR/语音录入结果） */
    @ColumnInfo(name = "content")
    val content: String,

    /** 作业类型：TODAY（当天）/ STAGE（阶段） */
    @ColumnInfo(name = "type")
    val type: String,

    /** 阶段范围：ONE_WEEK/TWO_WEEKS/THREE_WEEKS/ONE_MONTH；type=TODAY 时为 null */
    @ColumnInfo(name = "stage_range")
    val stageRange: String? = null,

    /** 截止时间（epoch 毫秒）；阶段作业必填，当天作业可空 */
    @ColumnInfo(name = "deadline")
    val deadline: Instant? = null,

    /** 优先级（清单排序用，数值越小越靠前/越紧急，由 homework 模块规则决定取值） */
    @ColumnInfo(name = "priority")
    val priority: Int,

    /** 开始时间（epoch 毫秒，可空）：用于时间段排定与冲突校验 */
    @ColumnInfo(name = "start_time")
    val startTime: Instant? = null,

    /** 预估时长（分钟，可空） */
    @ColumnInfo(name = "estimated_minutes")
    val estimatedMinutes: Int? = null,

    /** 状态：RECORDED / PENDING / IN_PROGRESS / COMPLETED */
    @ColumnInfo(name = "status")
    val status: String,

    /** 录入者角色：PARENT / STUDENT（权限规则判定依据） */
    @ColumnInfo(name = "created_by_role")
    val createdByRole: String,

    /** 创建时刻（epoch 毫秒，Instant <-> Long 由 AppTypeConverters 转换） */
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)
