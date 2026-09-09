package com.assignmate.app.core.domain.prefs

import kotlinx.coroutines.flow.Flow

/**
 * 通用 KeyValue 偏好存储抽象（默认实现基于 DataStore，见 core.data.prefs.DataStoreKeyValueStore）。
 *
 * 适用：通用开关、最近选择等非敏感小键值。敏感凭据（如 OCR API 密钥）请勿直接经本接口明文存储，
 * 应走专门的敏感配置存储（OcrConfigStore 内部经 ValueEncryptor 加密落盘）。
 */
interface KeyValueStore {

    /** 观察字符串值变化（不存在时为 null） */
    fun observeString(key: String): Flow<String?>

    /** 观察布尔值变化（不存在时为 null） */
    fun observeBoolean(key: String): Flow<Boolean?>

    /** 读取字符串（不存在返回 null） */
    suspend fun getString(key: String): String?

    /** 读取布尔值（不存在返回 [defaultValue]） */
    suspend fun getBoolean(key: String, defaultValue: Boolean = false): Boolean

    /** 写入字符串（空串也允许，语义与 null 区分） */
    suspend fun putString(key: String, value: String)

    /** 写入布尔值 */
    suspend fun putBoolean(key: String, value: Boolean)

    /** 删除指定键 */
    suspend fun remove(key: String)
}
