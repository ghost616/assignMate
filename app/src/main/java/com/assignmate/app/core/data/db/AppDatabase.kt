package com.assignmate.app.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.assignmate.app.core.data.db.dao.HomeworkDailyRecordDao
import com.assignmate.app.core.data.db.dao.HomeworkItemDao
import com.assignmate.app.core.data.db.dao.OcrRetryTaskDao
import com.assignmate.app.core.data.db.dao.ParentAccountDao
import com.assignmate.app.core.data.db.dao.PauseRecordDao
import com.assignmate.app.core.data.db.dao.StudentDao
import com.assignmate.app.core.data.db.dao.TimerSessionDao
import com.assignmate.app.core.data.db.entity.HomeworkDailyRecordEntity
import com.assignmate.app.core.data.db.entity.HomeworkItemEntity
import com.assignmate.app.core.data.db.entity.OcrRetryTaskEntity
import com.assignmate.app.core.data.db.entity.ParentAccountEntity
import com.assignmate.app.core.data.db.entity.PauseRecordEntity
import com.assignmate.app.core.data.db.entity.StudentEntity
import com.assignmate.app.core.data.db.entity.TimerSessionEntity
import com.assignmate.app.core.domain.util.CoreConstants

/**
 * 应用 Room 数据库（core 统一持有，经 Hilt 以单例注入）。
 *
 * 注册与升级约定（业务模块新增实体/DAO 时遵守）：
 * 1. entities 追加实体，新增对应 DAO 抽象方法；
 * 2. CoreConstants.DATABASE_VERSION +1，并在 di.DatabaseModule 的 Room.databaseBuilder
 *    前链式接入 RoomDatabase.Callback 或提供 Migration，禁止 fallbackToDestructiveMigration 破坏用户数据。
 *
 * Schema 导出：exportSchema = true，KSP 参数 room.schemaLocation 指向 app/schemas
 * （见 app/build.gradle.kts ksp 块），schema JSON 纳入版本控制，用于版本升级时校验与生成 Migration。
 */
@Database(
    entities = [
        OcrRetryTaskEntity::class,
        ParentAccountEntity::class,
        StudentEntity::class,
        HomeworkItemEntity::class,
        TimerSessionEntity::class,
        PauseRecordEntity::class,
        HomeworkDailyRecordEntity::class,
    ],
    version = CoreConstants.DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(AppTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {

    /** 待重试 OCR 任务 DAO */
    abstract fun ocrRetryTaskDao(): OcrRetryTaskDao

    /** 家长账号 DAO（auth 模块使用） */
    abstract fun parentAccountDao(): ParentAccountDao

    /** 学生档案 DAO（auth/家庭管理模块使用） */
    abstract fun studentDao(): StudentDao

    /** 作业项 DAO（homework 模块使用） */
    abstract fun homeworkItemDao(): HomeworkItemDao

    /** 计时会话 DAO（timer 模块使用） */
    abstract fun timerSessionDao(): TimerSessionDao

    /** 暂停明细 DAO（timer/stats 模块使用） */
    abstract fun pauseRecordDao(): PauseRecordDao

    /** 作业每天详情 DAO（homework/timer/stats 模块使用） */
    abstract fun homeworkDailyRecordDao(): HomeworkDailyRecordDao
}
