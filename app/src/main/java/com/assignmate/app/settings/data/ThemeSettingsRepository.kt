package com.assignmate.app.settings.data

import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.settings.domain.SettingsDenialReason
import kotlinx.coroutines.flow.Flow

/**
 * 护眼设置（三档主题）读写用例。
 *
 * 存储来源唯一：经 core 的 [com.assignmate.app.core.domain.prefs.ThemePreferenceStore]
 * （DataStore 偏好文件 assignmate_prefs），本模块不自建存储、不新增键。
 *
 * 分权：护眼设置**家长与学生均可访问**（学生端首页有「🌙 护眼设置」入口）；
 * 仅在无会话（未登录/已登出）时按未登录处理，返回 [ThemeSettingsResult.Denied] 且不落盘。
 */
interface ThemeSettingsRepository {

    /** 观察当前主题档位（缺失/非法存量值兜底「跟随系统」，由 core 实现保证） */
    fun observeThemeMode(): Flow<ThemeMode>

    /** 读取当前主题档位 */
    suspend fun themeMode(): ThemeMode

    /**
     * 写入主题档位（选中即时生效：写入后 core 的观察流会立即发射新值）。
     *
     * @return [ThemeSettingsResult.Saved] 表示已落盘；[ThemeSettingsResult.Denied] 表示无会话
     */
    suspend fun setThemeMode(mode: ThemeMode): ThemeSettingsResult
}

/** 主题档位写入结果 */
sealed interface ThemeSettingsResult {

    /** 已落盘（并已即时生效） */
    data object Saved : ThemeSettingsResult

    /** 分权拒绝：无有效会话（角色为 null） */
    data class Denied(val reason: SettingsDenialReason) : ThemeSettingsResult
}