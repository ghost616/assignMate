package com.assignmate.app.core.di

import com.assignmate.app.core.data.prefs.DataStoreKeyValueStore
import com.assignmate.app.core.data.prefs.DataStoreThemePreferenceStore
import com.assignmate.app.core.data.prefs.IdentityValueEncryptor
import com.assignmate.app.core.domain.prefs.KeyValueStore
import com.assignmate.app.core.domain.prefs.ThemePreferenceStore
import com.assignmate.app.core.domain.security.ValueEncryptor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 偏好存储相关绑定模块：
 * - KeyValueStore：通用键值存取 -> DataStore 实现；
 * - ValueEncryptor：敏感值加密器默认透传实现（明文占位），后续替换为 Keystore 方案仅改此处绑定；
 * - ThemePreferenceStore：三档主题档位偏好 -> DataStore 实现（非法/缺失值兜底「跟随系统」）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PrefsModule {

    @Binds
    @Singleton
    abstract fun bindKeyValueStore(impl: DataStoreKeyValueStore): KeyValueStore

    @Binds
    @Singleton
    abstract fun bindValueEncryptor(impl: IdentityValueEncryptor): ValueEncryptor

    @Binds
    @Singleton
    abstract fun bindThemePreferenceStore(impl: DataStoreThemePreferenceStore): ThemePreferenceStore
}
