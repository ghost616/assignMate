package com.assignmate.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * 学生档案表（auth/家庭管理基础，core 持有表结构与迁移）。
 *
 * 归属关系：parent_account_id 外键关联家长账号表，家长账号删除时级联删除其学生档案
 * （ON DELETE CASCADE：业务上家庭账号注销即清除名下学生数据）。
 * 索引：(parent_account_id) 支撑"按家长列出学生"；(parent_account_id, verification_code)
 * 支撑"按家长 + 进入验证码查询学生"（验证码唯一性由 auth 业务层保证）。
 */
@Entity(
    tableName = "student",
    foreignKeys = [
        ForeignKey(
            entity = ParentAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["parent_account_id"]),
        Index(value = ["parent_account_id", "verification_code"]),
    ],
)
data class StudentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 所属家长账号 id（外键 -> parent_account.id，级联删除） */
    @ColumnInfo(name = "parent_account_id")
    val parentAccountId: Long,

    /** 学生姓名 */
    @ColumnInfo(name = "name")
    val name: String,

    /** 进入验证码：学生端/儿童端切换身份时使用（auth 模块校验） */
    @ColumnInfo(name = "verification_code")
    val verificationCode: String,

    /** 创建时刻（epoch 毫秒，Instant <-> Long 由 AppTypeConverters 转换） */
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)
