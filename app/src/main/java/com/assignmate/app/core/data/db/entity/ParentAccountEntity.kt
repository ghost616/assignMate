package com.assignmate.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * 家长账号表（auth 模块持久化基础，core 持有表结构与迁移）。
 *
 * 安全约定：只存密码哈希（password_hash），严禁明文存储密码；
 * 哈希/加盐算法与校验由 auth 模块负责（本表不感知算法细节）。
 */
@Entity(
    tableName = "parent_account",
    indices = [Index(value = ["account"], unique = true)],
)
data class ParentAccountEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 登录账号（邮箱/手机号等），唯一约束防止重复注册 */
    @ColumnInfo(name = "account")
    val account: String,

    /** 密码哈希值（SHA/BCrypt 等哈希结果，auth 模块维护算法）；禁止存储明文密码 */
    @ColumnInfo(name = "password_hash")
    val passwordHash: String,

    /** 创建时刻（epoch 毫秒，Instant <-> Long 由 AppTypeConverters 转换） */
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)
