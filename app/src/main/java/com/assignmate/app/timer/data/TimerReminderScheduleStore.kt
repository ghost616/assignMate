package com.assignmate.app.timer.data

import com.assignmate.app.core.domain.prefs.KeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「阶段作业按天提醒」的已设闹钟登记表：作业 id → 已设置闹钟的业务自然日集合。
 *
 * 为什么需要登记表：阶段作业的每一天各有一个闹钟（请求码由「作业 + 自然日」派生），
 * 而**取消**发生在作业数据已不可读的场景——删除作业时只剩一个 homeworkId
 * （[HomeworkReminderCoordinator.cancelHomeworkReminder]），无法再从阶段范围反推有哪些天；
 * 变更阶段范围/截止时间时，也必须知道「上一次设了哪些天」才能把落在新范围外的闹钟清掉。
 * 登记表让「取消」可以精确枚举，从而不遗留无效闹钟。
 *
 * 落盘经 core 的 [KeyValueStore]（DataStore），进程回收/重启后仍可正确取消；
 * 数据本身可丢弃（丢失只会导致极端情况下漏取消一个闹钟，交付侧的状态核对仍会静默拦截陈旧提醒）。
 */
interface TimerReminderScheduleStore {

    /** 读取某作业已设置闹钟的自然日集合（无登记返回空集） */
    suspend fun load(homeworkId: Long): Set<Long>

    /** 覆盖写入某作业已设置闹钟的自然日集合（空集等于清除登记） */
    suspend fun save(homeworkId: Long, epochDays: Set<Long>)

    /** 清除某作业的登记（取消全部按天提醒后调用） */
    suspend fun clear(homeworkId: Long)
}

/**
 * [TimerReminderScheduleStore] 的默认实现：以「逗号分隔的 epochDay 列表」落盘，
 * 键按作业隔离（前缀 + 作业 id）。
 *
 * 容错口径：读取时忽略空白与非法片段（历史脏值/半截写入不会抛异常，只是少几天登记）；
 * 写入前按升序排序并去重，保证同一集合的落盘文本稳定（便于比对与调试）。
 */
@Singleton
class DataStoreTimerReminderScheduleStore @Inject constructor(
    private val keyValueStore: KeyValueStore,
) : TimerReminderScheduleStore {

    override suspend fun load(homeworkId: Long): Set<Long> =
        keyValueStore.getString(keyOf(homeworkId))
            .orEmpty()
            .split(SEPARATOR)
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()

    override suspend fun save(homeworkId: Long, epochDays: Set<Long>) {
        if (epochDays.isEmpty()) {
            clear(homeworkId)
            return
        }
        keyValueStore.putString(keyOf(homeworkId), epochDays.sorted().joinToString(SEPARATOR))
    }

    override suspend fun clear(homeworkId: Long) {
        keyValueStore.remove(keyOf(homeworkId))
    }

    /** 登记键：按作业隔离（前缀 + 作业 id），与其它偏好键不冲突 */
    private fun keyOf(homeworkId: Long): String = "$KEY_PREFIX$homeworkId"

    companion object {

        /** 登记键前缀（唯一来源，测试亦复用） */
        const val KEY_PREFIX = "timer_reminder_days_"

        /** 自然日列表分隔符 */
        private const val SEPARATOR = ","
    }
}
