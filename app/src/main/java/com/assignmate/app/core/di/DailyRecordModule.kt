package com.assignmate.app.core.di

import com.assignmate.app.core.data.db.HomeworkDailyRecordRepositoryImpl
import com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

/**
 * 作业每天详情的 Hilt 模块。
 *
 * 两项职责，均属「全应用唯一来源」：
 * 1. 把 core.domain 的 [HomeworkDailyRecordRepository] 契约绑定到 Room 默认实现
 *    [HomeworkDailyRecordRepositoryImpl]；
 * 2. 提供**全应用唯一的业务时区绑定**（无限定的 [ZoneId]）。
 *
 * ## 业务时区唯一来源约定（重要）
 * 本模块的 [ZoneId] 绑定是整个应用「业务自然日（epochDay）」与「今天」口径的**唯一来源**：
 * - core：每天详情仓库（epochDay 折算）经构造注入本绑定；
 * - homework：归属日折算（创建时刻/阶段起始日）经本绑定；
 * - timer：会话/暂停的逐日归属与阶段作业「当天 + 每日截止时刻」超时判定经本绑定；
 * - stats：当日常与历史「今天」解析经本绑定。
 *
 * 因此：
 * - 业务模块（homework / timer / stats）**不得**再自建业务时区 @Provides/@Binds——重复绑定
 *   既是 Hilt 的 DuplicateBindings 编译错误，也会造成「覆写一处只对一半模块生效」的口径漂移
 *   （跨零点时作业归属日与统计当日常不一致）；
 * - 需要替换时区（测试注入固定时区、未来按用户偏好切换）时，**只覆写这一处绑定**即可全局生效；
 * - [ZoneId.systemDefault] 只允许出现在本方法的实现里，其余模块不得再以它作为业务时区默认值。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DailyRecordModule {

    /** 作业每天详情仓库默认绑定（Room 实现，业务模块只依赖 domain 契约） */
    @Binds
    @Singleton
    abstract fun bindHomeworkDailyRecordRepository(
        impl: HomeworkDailyRecordRepositoryImpl,
    ): HomeworkDailyRecordRepository

    companion object {

        /**
         * 全应用唯一的业务时区绑定（默认系统时区）。
         *
         * 为什么集中在此：homework / timer / stats 都需要「今天」的 epochDay 口径，
         * 各自 `LocalDate.now()` 或各自声明绑定会在跨时区、跨零点与测试场景下漂移；
         * 统一到这一处后，覆写本绑定即全局生效（含 core 每天详情折算）。
         *
         * 说明：模块类为 abstract class（@Binds 方法必须抽象），故 @Provides 放在
         * companion object 并标注 @JvmStatic，供 Dagger 以静态方法调用。
         */
        @Provides
        @Singleton
        @JvmStatic
        fun provideBusinessZoneId(): ZoneId = ZoneId.systemDefault()
    }
}
