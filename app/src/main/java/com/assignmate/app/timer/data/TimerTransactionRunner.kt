package com.assignmate.app.timer.data

import androidx.room.withTransaction
import com.assignmate.app.core.data.db.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 计时写操作的事务边界抽象。
 *
 * 用途：把「多次 DAO 写必须一起成功」的场景（暂停落明细 + 置状态、完成时结束时刻 + 暂停汇总）
 * 收敛为一次原子提交，避免中断后留下「会话仍 RUNNING + 未结束暂停明细」这类不一致数据
 * （其后果是累计暂停虚高、暂停次数虚增）。
 *
 * 抽成接口的原因：仓库层单测使用内存 DAO 替身，无法构造 Room 的 `RoomDatabase`；
 * 生产实现 [RoomTimerTransactionRunner] 走 Room 事务，测试注入直接执行的替身即可验证调用边界。
 */
interface TimerTransactionRunner {

    /** 在事务中执行 [block]（块内异常会回滚并向上抛出，由仓库层收敛为密封结果） */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** [TimerTransactionRunner] 的 Room 实现：复用 core 提供的 [AppDatabase] 单例 */
@Singleton
class RoomTimerTransactionRunner @Inject constructor(
    private val database: AppDatabase,
) : TimerTransactionRunner {

    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}
