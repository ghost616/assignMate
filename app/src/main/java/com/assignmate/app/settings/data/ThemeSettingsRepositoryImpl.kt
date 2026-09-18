package com.assignmate.app.settings.data

import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.core.domain.prefs.ThemePreferenceStore
import com.assignmate.app.settings.domain.SettingsFeature
import com.assignmate.app.settings.domain.SettingsRoleGuard
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * [ThemeSettingsRepository] 默认实现：分权守卫（仅拦未登录）+ 委托 core 主题偏好存储。
 *
 * 读路径不设会话门槛：core 的档位读取本身容错（缺失/非法值兜底「跟随系统」），
 * 且在应用根主题接线之前就可能被读取（如启动恢复），故与写路径的门槛不完全对称：
 * 只有**写入**要求存在有效会话，避免未登录状态把偏好写脏。
 */
class ThemeSettingsRepositoryImpl @Inject constructor(
    private val themePreferenceStore: ThemePreferenceStore,
    private val guard: SettingsRoleGuard,
) : ThemeSettingsRepository {

    override fun observeThemeMode(): Flow<ThemeMode> = themePreferenceStore.observeThemeMode()

    override suspend fun themeMode(): ThemeMode = themePreferenceStore.themeMode()

    override suspend fun setThemeMode(mode: ThemeMode): ThemeSettingsResult {
        val denial = guard.denialReason(SettingsFeature.THEME)
        if (denial != null) {
            return ThemeSettingsResult.Denied(denial)
        }
        themePreferenceStore.setThemeMode(mode)
        return ThemeSettingsResult.Saved
    }
}