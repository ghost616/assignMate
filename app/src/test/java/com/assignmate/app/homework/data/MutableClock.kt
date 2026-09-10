package com.assignmate.app.homework.data

import com.assignmate.app.core.domain.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * 可推进的测试时钟：默认固定在某时刻，测试可显式推进以构造「先后创建」「时段校验」等时间敏感场景。
 * 实现线程安全计数，避免 runTest 多协程推进时丢更新。
 */
class MutableClock(initialMillis: Long) : Clock {

    private val current = AtomicLong(initialMillis)

    override fun currentTimeMillis(): Long = current.get()

    /** 向前推进 [millis] 毫秒 */
    fun advance(millis: Long) {
        current.addAndGet(millis)
    }

    /** 直接设置当前时刻 */
    fun set(millis: Long) {
        current.set(millis)
    }
}