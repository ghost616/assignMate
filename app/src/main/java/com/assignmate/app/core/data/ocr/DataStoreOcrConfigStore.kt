package com.assignmate.app.core.data.ocr

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrConfigStore
import com.assignmate.app.core.domain.security.ValueEncryptor
import com.assignmate.app.core.domain.util.CoreConstants
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [OcrConfigStore] 的 DataStore 默认实现。
 *
 * 敏感字段约定：KEY_API_KEY 落盘前经 [ValueEncryptor.encrypt]（当前透传明文，
 * 后续替换为 Keystore 实现后自动升级为密文），密钥不参与任何日志输出。
 */
class DataStoreOcrConfigStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val valueEncryptor: ValueEncryptor,
) : OcrConfigStore {

    override val config: Flow<OcrConfig> = dataStore.data.map { prefs ->
        OcrConfig(
            enabled = prefs[KEY_ENABLED] ?: false,
            apiBaseUrl = prefs[KEY_API_BASE_URL] ?: CoreConstants.DEFAULT_OCR_API_BASE_URL,
            modelName = prefs[KEY_MODEL_NAME] ?: CoreConstants.DEFAULT_OCR_MODEL_NAME,
            apiKey = prefs[KEY_API_KEY]?.let { valueEncryptor.decrypt(it) }.orEmpty(),
        )
    }

    override suspend fun save(config: OcrConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_ENABLED] = config.enabled
            prefs[KEY_API_BASE_URL] = config.apiBaseUrl.trim().trimEnd('/')
            prefs[KEY_MODEL_NAME] = config.modelName.trim()
            prefs[KEY_API_KEY] = valueEncryptor.encrypt(config.apiKey.trim())
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.clear() }
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("ocr_config_enabled")
        val KEY_API_BASE_URL = stringPreferencesKey("ocr_config_api_base_url")
        val KEY_MODEL_NAME = stringPreferencesKey("ocr_config_model_name")
        // 敏感字段：apiKey 密文落盘（当前为透传明文 + 预留 Keystore 方案）
        val KEY_API_KEY = stringPreferencesKey("ocr_config_api_key")
    }
}
