package com.assignmate.app.core.data.time

import com.assignmate.app.core.domain.time.Clock
import javax.inject.Inject

/**
 * 系统真实时钟：时间源为 System.currentTimeMillis()。
 * 由 Hilt 绑定为 [Clock] 默认实现；单元测试注入固定时钟（如 object : Clock { ... }）即可替换。
 */
class SystemClock @Inject constructor() : Clock {

    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
