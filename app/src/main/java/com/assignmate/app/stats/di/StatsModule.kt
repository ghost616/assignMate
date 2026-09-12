package com.assignmate.app.stats.di

import com.assignmate.app.stats.data.StatsRepository
import com.assignmate.app.stats.data.StatsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * stats 模块 Hilt 绑定模块。
 *
 * - [StatsRepository] -> 默认实现 [StatsRepositoryImpl]：只读聚合 homework 的作业清单/状态与
 *   timer 的执行会话/暂停明细，配合可注入 Clock 与业务时区 ZoneId（由 homework 模块统一绑定，
 *   本模块直接注入复用，避免重复绑定造成 DuplicateBindings）。
 *
 * stats 为**纯读取聚合**模块，不新增数据库表、不注册新 DAO、不提供任何写入口；
 * 如需替换实现（如未来远端统计服务），仅调整本处 @Binds 即可，UI 与 ViewModel 零改动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StatsModule {

    @Binds
    @Singleton
    abstract fun bindStatsRepository(impl: StatsRepositoryImpl): StatsRepository
}