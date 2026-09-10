package com.assignmate.app.timer.data

import com.assignmate.app.core.domain.prefs.KeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 超时鼓励的「已提醒」记录：同一作业最近一次给出超时鼓励的时刻。
 *
 * 用途：与 [com.assignmate.app.timer.domain.TimerReminderRules.shouldPromptOverdue] 配合，
 * 避免超时后每隔一秒的走秒刷新都重复播报/弹提示（按间隔去重），也避免家长重新打开应用即被重复骚扰。
 *
 * 落盘经 core 的 [KeyValueStore]（DataStore），键按作业 id 隔离。
 */
interface TimerOverduePromptStore {

    /** 读取该作业最近一次超时鼓励时刻（从未提醒返回 null） */
    suspend fun lastPromptedAtMillis(homeworkId: Long): Long?

    /** 记录该作业本次超时鼓励时刻 */
    suspend fun markPrompted(homeworkId: Long, atMillis: Long)
}

/** [TimerOverduePromptStore] 的默认实现（基于 core KeyValueStore） */
@Singleton
class DataStoreTimerOverduePromptStore @Inject constructor(
    private val keyValueStore: KeyValueStore,
) : TimerOverduePromptStore {

    override suspend fun lastPromptedAtMillis(homeworkId: Long): Long? =
        keyValueStore.getString(keyOf(homeworkId))?.toLongOrNull()

    override suspend fun markPrompted(homeworkId: Long, atMillis: Long) {
        keyValueStore.putString(keyOf(homeworkId), atMillis.toString())
    }

    companion object {

        /** 键前缀（后接作业 id） */
        const val KEY_PREFIX = "timer_overdue_prompt_"

        /** 作业 id -> 存储键（按作业隔离，互不影响） */
        fun keyOf(homeworkId: Long): String = "$KEY_PREFIX$homeworkId"
    }
}
