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
 * - [StatsRepository] -> 默认实现 [StatsRepositoryImpl]：只读聚合 homework 的作业清单与
 *   core 的**作业每天详情**（[com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository]，
 *   由 core.di.DailyRecordModule 绑定，本模块只依赖其 domain 契约，不直接碰 Room DAO）。
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