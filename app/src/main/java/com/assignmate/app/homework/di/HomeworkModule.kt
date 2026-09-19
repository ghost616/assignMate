package com.assignmate.app.homework.di

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.core.data.db.dao.HomeworkItemDao
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import com.assignmate.app.core.domain.time.Clock
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
 * - [HomeworkRepository] -> [HomeworkRepositoryImpl]（基于 core DAO + auth 会话 + 可注入 Clock +
 *   core 的「作业每天详情」仓库）。
 *
 * 业务时区 [ZoneId] **不在本模块声明**：全应用唯一的业务时区绑定由 core 提供
 * （core.di.DailyRecordModule，默认系统时区），homework 与 timer / stats 一样直接注入消费。
 * 这是刻意的收敛——此前 homework 自建的裸 [ZoneId] 绑定与 core 的
 * @Named(dailyRecordZoneId) 绑定是两个独立入口，覆写其一即会造成「今天」口径漂移
 * （跨零点时作业归属日与每天详情折算不一致）；现在覆写 core 那一处绑定即全局生效。
 *
 * 如需替换实现（如未来远端作业服务），仅调整本处 @Binds/@Provides 即可，UI 与 ViewModel 零改动。
 *
 * 说明：仓库实现经 @Provides 显式装配（而非 @Binds）——其构造参数含带默认值的
 * [HomeworkDailyRecordRepository]（供只依赖清单快照的既有测试零改动），
 * Dagger 不会为带默认值的参数解析依赖，故在此显式传入 core 绑定
 * （[com.assignmate.app.core.data.db.HomeworkDailyRecordRepositoryImpl]，由 core.di.DailyRecordModule 绑定）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HomeworkModule {

    /** 录入图片（拍照/相册/待重试落盘）的本地文件存储 */
    @Binds
    @Singleton
    abstract fun bindHomeworkFileStore(impl: HomeworkFileStoreImpl): HomeworkFileStore

    companion object {

        /**
         * 作业仓库装配：把 core 的作业项 DAO、auth 会话、可注入时钟、业务时区与
         * core 的「作业每天详情」仓库一并注入默认实现。
         *
         * @param zoneId 业务时区：由 core 提供的**全应用唯一**绑定（core.di.DailyRecordModule），
         *   本模块不再自建，避免第二个入口造成口径漂移
         */
        @Provides
        @Singleton
        fun provideHomeworkRepository(
            homeworkItemDao: HomeworkItemDao,
            authRepository: AuthRepository,
            clock: Clock,
            zoneId: ZoneId,
            dailyRecordRepository: HomeworkDailyRecordRepository,
        ): HomeworkRepository = HomeworkRepositoryImpl(
            homeworkItemDao = homeworkItemDao,
            authRepository = authRepository,
            clock = clock,
            zoneId = zoneId,
            dailyRecordRepository = dailyRecordRepository,
        )
    }
}
