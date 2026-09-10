package com.assignmate.app.timer.service

import android.content.Context
import androidx.room.Room
import com.assignmate.app.core.data.db.AppDatabase
import com.assignmate.app.core.di.DatabaseModule
import com.assignmate.app.core.domain.util.CoreConstants
import com.assignmate.app.homework.domain.HomeworkStatus

/**
 * 到点提醒的作业状态核对器（**无依赖注入**，冷启动可用）。
 *
 * 设计取舍：
 * - 闹钟可能在应用进程未启动时唤起进程，故不引入 Hilt（[HomeworkAlarmReceiver] 亦不注入依赖），
 *   只依赖 ApplicationContext 以只读方式核对数据库，避免「指向已失效/已删除作业的陈旧提醒」；
 * - 复用 core 的 [AppDatabase] 定义与 [DatabaseModule] 已登记的迁移，**不改动 core 语义**；
 *   若数据库不可用或核对抛异常，一律返回 [ReminderVerification.Unknown]（绝不崩溃），
 *   由 [HomeworkReminderDeliveryRules] 退化为中性文案；
 * - 同进程内数据库实例缓存于伴生对象（懒建、只读使用，不写库、不影响主实例的 Flow 观察）；
 *   新增数据库版本时需同步本类的迁移列表，否则核对会退化为 Unknown（安全但降级）。
 */
class HomeworkReminderVerifier(context: Context) {

    private val appContext = context.applicationContext

    /** 核对作业是否仍为「待完成」：是则返回库内内容，否则 Stale；核对失败返回 Unknown */
    suspend fun verify(homeworkId: Long): ReminderVerification = runCatching {
        val row = database().homeworkItemDao().findById(homeworkId)
        when {
            row == null -> ReminderVerification.Stale
            HomeworkStatus.fromName(row.status) == HomeworkStatus.PENDING ->
                ReminderVerification.ConfirmedPending(row.content)

            else -> ReminderVerification.Stale
        }
    }.getOrElse { ReminderVerification.Unknown }

    private fun database(): AppDatabase = INSTANCE ?: synchronized(LOCK) {
        INSTANCE ?: Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            CoreConstants.DATABASE_NAME,
        ).addMigrations(
            DatabaseModule.MIGRATION_1_2,
            DatabaseModule.MIGRATION_2_3,
            DatabaseModule.MIGRATION_3_4,
        ).build().also { INSTANCE = it }
    }

    private companion object {

        private val LOCK = Any()

        @Volatile
        private var INSTANCE: AppDatabase? = null
    }
}
