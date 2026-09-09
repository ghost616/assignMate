package com.assignmate.app.core.di

import com.assignmate.app.core.data.time.SystemClock
import com.assignmate.app.core.domain.time.Clock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 时间工具 Hilt 绑定模块：[Clock] -> 系统实现 [SystemClock]。
 * 测试中可通过覆写绑定或直接构造实现注入固定时钟（FixedClock）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {

    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
