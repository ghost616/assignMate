package com.assignmate.app.core.domain.prefs

import kotlinx.coroutines.flow.Flow

/**
 * 主题档位偏好存储：设置页读写 [ThemeMode]，UI 订阅 [observeThemeMode] 后即可随档位切换即时生效。
 *
 * 落盘复用 [KeyValueStore]（默认 DataStore 实现），不新增存储通道；
 * 键名与默认值各只有一处来源：键名见 [KEY_THEME_MODE]，默认档位见 [ThemeMode.DEFAULT]。
 *
 * 容错约定：键缺失或存量值非法（如已被改名的旧枚举值）一律返回 [ThemeMode.DEFAULT]（跟随系统），
 * 读取链路不抛异常。
 */
interface ThemePreferenceStore {

    /** 观察主题档位变化（缺失/非法值兜底为跟随系统） */
    fun observeThemeMode(): Flow<ThemeMode>

    /** 读取当前主题档位（缺失/非法值兜底为跟随系统） */
    suspend fun themeMode(): ThemeMode

    /** 写入主题档位（存枚举名） */
    suspend fun setThemeMode(mode: ThemeMode)

    companion object {

        /** 主题档位持久化键（唯一来源，禁止在别处硬编码） */
        const val KEY_THEME_MODE = "theme_mode"
    }
}