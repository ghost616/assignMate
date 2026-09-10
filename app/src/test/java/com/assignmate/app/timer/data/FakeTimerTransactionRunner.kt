package com.assignmate.app.timer.data

/**
 * 事务边界替身（单元测试）：直接执行块内逻辑并记录调用次数。
 *
 * 内存 DAO 没有真实事务，但本替身可断言「仓库确实把多次写收敛进了一个事务边界」
 * （例如暂停的「落明细 + 置状态」、完成的「写结束时刻 + 刷汇总」各只应有一次 inTransaction）。
 */
class FakeTimerTransactionRunner : TimerTransactionRunner {

    /** inTransaction 调用次数（按调用顺序累计） */
    var invocations: Int = 0
        private set

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        invocations++
        return block()
    }
}
