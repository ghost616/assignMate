package com.assignmate.app.auth.data

import com.assignmate.app.core.domain.prefs.KeyValueStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 内存版 KeyValueStore（单元测试替身）：每个键独立持有 MutableStateFlow，
 * observe/get/put/remove 语义与 DataStore 实现一致且立即可观察。
 */
class FakeKeyValueStore : KeyValueStore {

    private val strings = mutableMapOf<String, MutableStateFlow<String?>>()
    private val booleans = mutableMapOf<String, MutableStateFlow<Boolean?>>()

    private fun stringFlow(key: String): MutableStateFlow<String?> =
        strings.getOrPut(key) { MutableStateFlow(null) }

    private fun booleanFlow(key: String): MutableStateFlow<Boolean?> =
        booleans.getOrPut(key) { MutableStateFlow(null) }

    override fun observeString(key: String): Flow<String?> = stringFlow(key)

    override fun observeBoolean(key: String): Flow<Boolean?> = booleanFlow(key)

    override suspend fun getString(key: String): String? = stringFlow(key).value

    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        booleanFlow(key).value ?: defaultValue

    override suspend fun putString(key: String, value: String) {
        stringFlow(key).value = value
    }

    override suspend fun putBoolean(key: String, value: Boolean) {
        booleanFlow(key).value = value
    }

    override suspend fun remove(key: String) {
        strings.remove(key)
        booleans.remove(key)
    }
}