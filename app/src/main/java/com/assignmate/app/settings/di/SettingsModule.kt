package com.assignmate.app.settings.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.assignmate.app.core.domain.ocr.PendingOcrRepository
import com.assignmate.app.settings.data.AndroidOcrLogSink
import com.assignmate.app.settings.data.GuardedOcrCacheCleaner
import com.assignmate.app.settings.data.OcrCacheCleaner
import com.assignmate.app.settings.data.OcrCacheRepository
import com.assignmate.app.settings.data.OcrCacheRepositoryImpl
import com.assignmate.app.settings.data.SettingsOcrRepository
import com.assignmate.app.settings.data.SettingsOcrRepositoryImpl
import com.assignmate.app.settings.data.ThemeSettingsRepository
import com.assignmate.app.settings.data.ThemeSettingsRepositoryImpl
import com.assignmate.app.settings.domain.DataCleanupService
import com.assignmate.app.settings.domain.OcrLogSink
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * settings 模块 Hilt 绑定模块。
 *
 * 绑定内容（实现类均标注可注入构造器，会话与存储依赖全部复用 core/auth 既有绑定）：
 * - [SettingsOcrRepository] -> [SettingsOcrRepositoryImpl]（OCR 配置读写 + 分权守卫；存储只经 core 的 OcrConfigStore）；
 * - [OcrCacheRepository] -> [OcrCacheRepositoryImpl]（待清理条数统计，数据源为 core 的 PendingOcrRepository）；
 * - [OcrCacheCleaner] -> [GuardedOcrCacheCleaner]（分权 + 委托 core 的 OcrCacheCleaner：记录与图片同删）；
 * - [ThemeSettingsRepository] -> [ThemeSettingsRepositoryImpl]（护眼三档，落 core 的 ThemePreferenceStore）；
 * - [OcrLogSink] -> [AndroidOcrLogSink]（日志出口；仅 debug 输出，使「密钥不落日志」可被单测断言）。
 *
 * 不新增数据库表、不新增 DAO、不新增 DataStore 键：settings 只做配置编排与分权。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    @Singleton
    abstract fun bindSettingsOcrRepository(impl: SettingsOcrRepositoryImpl): SettingsOcrRepository

    @Binds
    @Singleton
    abstract fun bindOcrCacheRepository(impl: OcrCacheRepositoryImpl): OcrCacheRepository

    @Binds
    @Singleton
    abstract fun bindOcrCacheCleaner(impl: GuardedOcrCacheCleaner): OcrCacheCleaner

    @Binds
    @Singleton
    abstract fun bindThemeSettingsRepository(
        impl: ThemeSettingsRepositoryImpl,
    ): ThemeSettingsRepository

    companion object {

        /**
         * 待清理条数统计的具体实现。
         *
         * 为什么显式 @Provides：设置主页需要 [OcrCacheRepositoryImpl] 的 `countUpdates()`
         * （由 core 的待识别任务流驱动重算），而接口 [OcrCacheRepository] 刻意不暴露该能力
         * （避免把 core 的 Flow 细节扩散到接口）。@Binds 与 @Provides 指向同一实例，
         * 计数口径不会分叉。
         */
        @Provides
        @Singleton
        fun provideOcrCacheRepositoryImpl(
            pendingOcrRepository: PendingOcrRepository,
        ): OcrCacheRepositoryImpl = OcrCacheRepositoryImpl(pendingOcrRepository)

        /**
         * 数据清理用例装配：接口（重算/计数）与具体实现（变更流驱动重算）指向同一单例，
         * 清理器已内含分权守卫，故本用例只负责编排与条数回报。
         */
        @Provides
        @Singleton
        fun provideDataCleanupService(
            cacheRepository: OcrCacheRepository,
            cacheObservation: OcrCacheRepositoryImpl,
            cleaner: OcrCacheCleaner,
        ): DataCleanupService = DataCleanupService(cacheRepository, cacheObservation, cleaner)

        /**
         * 调试开关：与 core 网络层日志策略同源（ApplicationInfo.FLAG_DEBUGGABLE），
         * 正式版不输出设置模块日志。
         */
        @Provides
        @Singleton
        fun provideIsDebuggable(@ApplicationContext context: Context): Boolean =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        /**
         * 日志实现：唯一绑定（构造参数为布尔，不经 Hilt 自动注入，故用 @Provides 显式构造）。
         * 与 core 网络层一致：仅 debug 可调试构建输出，正式版静默。
         */
        @Provides
        @Singleton
        fun provideOcrLogSink(isDebuggable: Boolean): OcrLogSink = AndroidOcrLogSink(isDebuggable)
    }
}