package com.assignmate.app.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.assignmate.app.core.domain.util.CoreConstants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 全局偏好 DataStore 委托：单进程内文件只能由一个 delegate 持有 */
private val Context.assignMatePreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = CoreConstants.DATASTORE_FILE_NAME,
)

/**
 * DataStore Hilt 模块：提供全局单例 [DataStore]（偏好文件 assignmate_prefs）。
 *
 * OCR 厂商配置、通用开关等键值统一存于此；敏感字段（如 apiKey）经 ValueEncryptor
 * 密文落盘（当前透传明文 + 预留 Keystore 方案），密钥不落日志。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.assignMatePreferencesDataStore
}
