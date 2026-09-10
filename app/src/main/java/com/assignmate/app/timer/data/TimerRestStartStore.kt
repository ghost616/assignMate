package com.assignmate.app.timer.data

import com.assignmate.app.core.domain.prefs.KeyValueStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 休息起点持久化：记住「某项作业完成后的这次休息是从什么时候开始的」。
 *
 * 引入原因：休息倒计时起点原先只存在内存中，进程被回收后重新进入休息页会从 10:00 重新起算，
 * 语义不成立（孩子明明已经休息过 5 分钟）。持久化后休息页可**幂等重建**：
 * 仍在休息窗口内则接着原起点倒计时，否则以当前时刻重新起算。
 *
 * 维度：按「学生 + 作业」隔离（同一作业重复完成、不同作业分别休息互不影响）。
 * 落盘经 core 的 [KeyValueStore]（DataStore）。
 */
interface TimerRestStartStore {

    /** 读取某学生某作业最近一次休息的起点（无记录返回 null） */
    suspend fun restStartedAtMillis(studentId: Long, homeworkId: Long): Long?

    /** 记录休息起点（覆盖式写入） */
    suspend fun markRestStarted(studentId: Long, homeworkId: Long, atMillis: Long)

    /** 清除休息起点（如作业被删除/重置场景由调用方决定是否清理） */
    suspend fun clear(studentId: Long, homeworkId: Long)
}

/** [TimerRestStartStore] 的默认实现（基于 core KeyValueStore） */
@Singleton
class DataStoreTimerRestStartStore @Inject constructor(
    private val keyValueStore: KeyValueStore,
) : TimerRestStartStore {

    override suspend fun restStartedAtMillis(studentId: Long, homeworkId: Long): Long? =
        keyValueStore.getString(keyOf(studentId, homeworkId))?.toLongOrNull()

    override suspend fun markRestStarted(studentId: Long, homeworkId: Long, atMillis: Long) {
        keyValueStore.putString(keyOf(studentId, homeworkId), atMillis.toString())
    }

    override suspend fun clear(studentId: Long, homeworkId: Long) {
        keyValueStore.remove(keyOf(studentId, homeworkId))
    }

    companion object {

        /** 键前缀（后接学生 id 与作业 id） */
        const val KEY_PREFIX = "timer_rest_start_"

        /** 学生 + 作业 -> 存储键（两个维度都隔离，避免相互覆盖） */
        fun keyOf(studentId: Long, homeworkId: Long): String = "$KEY_PREFIX${studentId}_$homeworkId"
    }
}
