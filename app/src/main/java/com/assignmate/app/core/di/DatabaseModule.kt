package com.assignmate.app.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.assignmate.app.core.data.db.AppDatabase
import com.assignmate.app.core.data.db.dao.HomeworkItemDao
import com.assignmate.app.core.data.db.dao.OcrRetryTaskDao
import com.assignmate.app.core.data.db.dao.ParentAccountDao
import com.assignmate.app.core.data.db.dao.StudentDao
import com.assignmate.app.core.domain.util.CoreConstants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库 Hilt 模块：提供单例 [AppDatabase] 及其 DAO，并登记全部 schema 迁移。
 *
 * 数据库升级约定：业务模块新增实体/DAO 后，版本号 +1 时在此为 Room.databaseBuilder
 * 链式添加对应 Migration（.addMigrations(...)）；禁止启用 fallbackToDestructiveMigration。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /** v1 -> v2：新增家长账号表 parent_account 与学生档案表 student（含索引/外键）。 */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `parent_account` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`account` TEXT NOT NULL, " +
                    "`password_hash` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL" +
                    ")",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_parent_account_account` " +
                    "ON `parent_account` (`account`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `student` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`parent_account_id` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`verification_code` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`parent_account_id`) REFERENCES `parent_account`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE " +
                    ")",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_student_parent_account_id` " +
                    "ON `student` (`parent_account_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_student_parent_account_id_verification_code` " +
                    "ON `student` (`parent_account_id`, `verification_code`)",
            )
        }
    }

    /** v2 -> v3：新增作业项表 homework_item（含外键与常用查询索引）。 */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `homework_item` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`parent_account_id` INTEGER NOT NULL, " +
                    "`student_id` INTEGER NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL, " +
                    "`stage_range` TEXT, " +
                    "`deadline` INTEGER, " +
                    "`priority` INTEGER NOT NULL, " +
                    "`start_time` INTEGER, " +
                    "`estimated_minutes` INTEGER, " +
                    "`status` TEXT NOT NULL, " +
                    "`created_by_role` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`student_id`) REFERENCES `student`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE " +
                    ")",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_homework_item_parent_account_id` " +
                    "ON `homework_item` (`parent_account_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_homework_item_student_id` " +
                    "ON `homework_item` (`student_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_homework_item_student_id_status` " +
                    "ON `homework_item` (`student_id`, `status`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_homework_item_student_id_priority` " +
                    "ON `homework_item` (`student_id`, `priority`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_homework_item_student_id_start_time` " +
                    "ON `homework_item` (`student_id`, `start_time`)",
            )
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            CoreConstants.DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

    @Provides
    fun provideOcrRetryTaskDao(database: AppDatabase): OcrRetryTaskDao = database.ocrRetryTaskDao()

    @Provides
    fun provideParentAccountDao(database: AppDatabase): ParentAccountDao = database.parentAccountDao()

    @Provides
    fun provideStudentDao(database: AppDatabase): StudentDao = database.studentDao()

    @Provides
    fun provideHomeworkItemDao(database: AppDatabase): HomeworkItemDao = database.homeworkItemDao()
}
