package com.assignmate.app.homework.di

import com.assignmate.app.homework.data.HomeworkFileStore
import com.assignmate.app.homework.data.HomeworkFileStoreImpl
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.data.HomeworkRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

/**
 * homework 模块 Hilt 绑定模块。
 *
 * - [HomeworkRepository] -> 默认实现 [HomeworkRepositoryImpl]（基于 core DAO + auth 会话 + 可注入 Clock）；
 * - [ZoneId]：时区口径集中绑定（默认系统时区），UI 展示与日期解析统一经此获取，
 *   测试可覆写绑定注入固定时区，避免各页面各自调用 ZoneId.systemDefault() 造成口径漂移。
 *
 * core 侧未声明同名绑定，不存在重复绑定冲突；如需替换实现（如未来远端作业服务），
 * 仅调整本处 @Binds/@Provides 即可，UI 与 ViewModel 零改动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HomeworkModule {

    @Binds
    @Singleton
    abstract fun bindHomeworkRepository(impl: HomeworkRepositoryImpl): HomeworkRepository

    /** 录入图片（拍照/相册/待重试落盘）的本地文件存储 */
    @Binds
    @Singleton
    abstract fun bindHomeworkFileStore(impl: HomeworkFileStoreImpl): HomeworkFileStore

    companion object {

        /** 业务时区（默认系统时区）：日期/时间展示与 epochDay 解析统一口径 */
        @Provides
        @Singleton
        fun provideHomeworkZoneId(): ZoneId = ZoneId.systemDefault()
    }
}