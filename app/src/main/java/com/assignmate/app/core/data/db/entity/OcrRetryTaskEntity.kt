package com.assignmate.app.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * “拍摄图片暂存、联网后重试 OCR”任务的本地表（core 数据库首张公共表，无业务语义）。
 *
 * 后续业务模块新增数据库能力时遵循统一约定：
 * 1. 在本包新增业务实体与 DAO；
 * 2. 在 AppDatabase.entities 中注册实体、暴露 DAO 抽象方法；
 * 3. CoreConstants.DATABASE_VERSION +1，并在 DatabaseModule 中接入 Migration。
 */
@Entity(tableName = "ocr_retry_task")
data class OcrRetryTaskEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 图片本地绝对路径（拍摄后经相机/相册临时目录落盘） */
    @ColumnInfo(name = "image_local_path")
    val imageLocalPath: String,

    /** 登记时刻（epoch 毫秒，Instant <-> Long 由 AppTypeConverters 转换） */
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    /** 状态枚举名（PendingOcrStatus.name），与 domain 层映射 */
    @ColumnInfo(name = "status")
    val status: String,

    /** 累计重试次数，超过上限置 FAILED 并提示用户 */
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
)
