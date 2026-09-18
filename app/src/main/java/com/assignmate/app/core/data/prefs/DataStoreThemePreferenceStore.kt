package com.assignmate.app.core.data.prefs

import com.assignmate.app.core.domain.prefs.KeyValueStore
import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.core.domain.prefs.ThemePreferenceStore
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ThemePreferenceStore] 的默认实现：经 core 的 [KeyValueStore]（DataStore，偏好文件 assignmate_prefs）
 * 以枚举名落盘，读取时统一走 [ThemeMode.fromRawValue] 兜底，非法/缺失值一律回到「跟随系统」。
 */
class DataStoreThemePreferenceStore @Inject constructor(
    private val keyValueStore: KeyValueStore,
) : ThemePreferenceStore {

    override fun observeThemeMode(): Flow<ThemeMode> =
        keyValueStore.observeString(ThemePreferenceStore.KEY_THEME_MODE)
            .map { raw -> ThemeMode.fromRawValue(raw) }

    override suspend fun themeMode(): ThemeMode =
        ThemeMode.fromRawValue(keyValueStore.getString(ThemePreferenceStore.KEY_THEME_MODE))

    override suspend fun setThemeMode(mode: ThemeMode) {
        keyValueStore.putString(ThemePreferenceStore.KEY_THEME_MODE, mode.name)
    }
}