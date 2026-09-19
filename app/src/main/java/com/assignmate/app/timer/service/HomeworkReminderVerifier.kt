package com.assignmate.app.timer.service

import android.content.Context
import androidx.room.Room
import com.assignmate.app.core.data.db.AppDatabase
import com.assignmate.app.core.data.db.entity.HomeworkItemEntity
import com.assignmate.app.core.di.DatabaseModule
import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.core.domain.util.CoreConstants
import com.assignmate.app.homework.domain.CreatorRole
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange
import com.assignmate.app.timer.domain.TimerReminderRules

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
 * - **迁移列表**：core 目前没有统一的 `ALL_MIGRATIONS` 常量（[DatabaseModule] 内部逐个迁移登记，
 *   见其 `provideDatabase` 装配），故本类保留自维护列表并在 KDoc 注明理由：新增数据库版本时需同步，
 *   否则核对退化为 Unknown（安全但降级、走中性文案）。core 将来若暴露统一常量，应改为复用该常量。
 *
 * 逐日核对（阶段作业「1 条 + 每天详情」后）：[verify] 收到 [epochDay] 时，除作业状态外
 * 还核对**该自然日的每天详情**（已完成当天不再打扰）与**该天是否仍在阶段覆盖区间内**
 * （阶段范围缩短/类型切回 TODAY 后，库内事实已不含该天 → 静默）。核对规则集中在纯函数
 * [HomeworkReminderVerificationRules]，本类只负责读库与兜底。
 */
class HomeworkReminderVerifier(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 核对作业（以及可选的第 [epochDay] 天）是否仍值得提醒：
     * 通过则返回库内内容，否则 Stale；核对失败返回 Unknown。
     *
     * @param epochDay 逐日提醒所属业务自然日；null 表示不区分逐日（单次提醒/旧闹钟），
     *   此时沿用「作业状态须为待完成」的既有口径。
     */
    suspend fun verify(homeworkId: Long, epochDay: Long? = null): ReminderVerification = runCatching {
        val row = database().homeworkItemDao().findById(homeworkId)
        val dayStatus = epochDay
            ?.let { database().homeworkDailyRecordDao().findByHomeworkAndDay(homeworkId, it) }
            ?.let { HomeworkDayStatus.fromRawValue(it.status) }
        HomeworkReminderVerificationRules.verify(
            content = row?.content,
            status = row?.status?.let(HomeworkStatus::fromName),
            isStage = HomeworkType.fromName(row?.type) == HomeworkType.STAGE,
            epochDay = epochDay,
            dayStatus = dayStatus,
            withinStageRange = withinStageRange(row, epochDay),
        )
    }.getOrElse { ReminderVerification.Unknown }

    /**
     * 该天是否仍在**库内当前**阶段覆盖区间内（区间口径与调度侧同源，见 [TimerReminderRules.isWithinStageRange]）。
     *
     * 读到的行是 core 的实体（不经 homework 领域仓库），故由实体字段还原判定所需三要素：
     * 类型、每日截止时刻（deadline 编码）、阶段覆盖天数（stage_range）；任一缺失或无法还原时，
     * 判定交给 [TimerReminderRules.isWithinStageRange] 的保守兜底（视为在区间内，宁可提醒不静默）。
     *
     * 不区分逐日（[epochDay] 为 null）时不参与判定：单次提醒没有「哪一天」的概念。
     */
    private fun withinStageRange(row: HomeworkItemEntity?, epochDay: Long?): Boolean {
        if (row == null || epochDay == null) {
            return true
        }
        return TimerReminderRules.isWithinStageRange(
            item = row.toItemForRangeCheck(),
            epochDay = epochDay,
        )
    }

    /**
     * 只为「区间核对」构造最小领域对象：库内实体的 type/deadline/stage_range 是区间判定的全部依据，
     * 其余字段（内容、优先级、排定时间等）与判定无关，取中性值即可——避免为一次只读核对
     * 拼装完整作业模型并引入 homework 仓库依赖（冷启动路径刻意不碰 Hilt/仓库）。
     */
    private fun HomeworkItemEntity.toItemForRangeCheck(): HomeworkItem = HomeworkItem(
        id = id,
        parentAccountId = parentAccountId,
        studentId = studentId,
        content = content,
        type = HomeworkType.fromName(type) ?: HomeworkType.TODAY,
        stageRange = stageRange?.let(StageRange::fromName),
        deadline = deadline,
        priority = priority,
        startTime = startTime,
        estimatedMinutes = estimatedMinutes,
        status = HomeworkStatus.fromName(status) ?: HomeworkStatus.PENDING,
        createdByRole = CreatorRole.fromName(createdByRole) ?: CreatorRole.PARENT,
        createdAt = createdAt,
    )

    private fun database(): AppDatabase = INSTANCE ?: synchronized(LOCK) {
        INSTANCE ?: Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            CoreConstants.DATABASE_NAME,
        ).addMigrations(
            DatabaseModule.MIGRATION_1_2,
            DatabaseModule.MIGRATION_2_3,
            DatabaseModule.MIGRATION_3_4,
            DatabaseModule.MIGRATION_4_5,
        ).build().also { INSTANCE = it }
    }

    private companion object {

        private val LOCK = Any()

        @Volatile
        private var INSTANCE: AppDatabase? = null
    }
}

/**
 * 到点提醒的核对规则（纯函数，集中可单测）：把「读到的库内事实」映射为 [ReminderVerification]。
 *
 * 决策表（[isStage] 且携带 [epochDay] 时按「逐日提醒」处理）：
 * - 作业不存在（[content] 为 null）→ [ReminderVerification.Stale]；
 * - 逐日提醒：
 *   · [withinStageRange] 为假（该天已超出阶段覆盖区间）→ [ReminderVerification.Stale]
 *     —— 阶段范围被缩短/类型切回 TODAY/作业被删后残留的旧闹钟不得再打扰用户；
 *   · 作业整体未完成且**当天详情未完成** → 确认提醒；当天尚无详情（未开始）同样应当提醒；
 *   · 作业整体已完成（阶段收尾）或**当天已完成** → [ReminderVerification.Stale]（已完成当天不再打扰）；
 * - 单次提醒（TODAY 或未携带 [epochDay]）：沿用既有口径，仅「待完成」确认提醒。
 *
 * @param withinStageRange 该天是否仍在阶段覆盖区间内；不区分逐日时恒为 true（不参与判定）
 */
object HomeworkReminderVerificationRules {

    fun verify(
        content: String?,
        status: HomeworkStatus?,
        isStage: Boolean,
        epochDay: Long?,
        dayStatus: HomeworkDayStatus?,
        withinStageRange: Boolean = true,
    ): ReminderVerification {
        if (content == null) {
            return ReminderVerification.Stale
        }
        if (isStage && epochDay != null) {
            if (!withinStageRange) {
                return ReminderVerification.Stale
            }
            val dayCompleted = dayStatus == HomeworkDayStatus.COMPLETED
            val stageCompleted = status == HomeworkStatus.COMPLETED
            return if (dayCompleted || stageCompleted) {
                ReminderVerification.Stale
            } else {
                ReminderVerification.ConfirmedPending(content)
            }
        }
        return if (status == HomeworkStatus.PENDING) {
            ReminderVerification.ConfirmedPending(content)
        } else {
            ReminderVerification.Stale
        }
    }
}
