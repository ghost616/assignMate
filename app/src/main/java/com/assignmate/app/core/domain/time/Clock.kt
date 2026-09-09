package com.assignmate.app.core.domain.time

/**
 * 可注入时钟抽象：所有时间敏感逻辑（计时、截止时间校验、待重试任务时间戳、统计）
 * 一律依赖本接口而非直接调用 System.currentTimeMillis()，
 * 便于单元测试注入固定时间源（FixedClock）。
 */
fun interface Clock {

    /** 返回当前时刻的 epoch 毫秒 */
    fun currentTimeMillis(): Long
}
