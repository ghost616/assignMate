package com.assignmate.app.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.assignmate.app.core.domain.prefs.KeyValueStore
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [KeyValueStore] 的 DataStore 默认实现。
 *
 * 字符串/布尔值键均以偏好文件明文存取；如未来需要加密键值，请改走 ValueEncryptor/Keystore 方案，
 * 不要在本实现内拼装加密逻辑（保持职责单一）。
 */
class DataStoreKeyValueStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : KeyValueStore {

    override fun observeString(key: String): Flow<String?> =
        dataStore.data.map { prefs -> prefs[stringPreferencesKey(key)] }

    override fun observeBoolean(key: String): Flow<Boolean?> =
        dataStore.data.map { prefs -> prefs[booleanPreferencesKey(key)] }

    override suspend fun getString(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        dataStore.data.first()[booleanPreferencesKey(key)] ?: defaultValue

    override suspend fun putString(key: String, value: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey(key)] = value }
    }

    override suspend fun putBoolean(key: String, value: Boolean) {
        dataStore.edit { prefs -> prefs[booleanPreferencesKey(key)] = value }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(booleanPreferencesKey(key))
        }
    }
}
