package com.assignmate.app.settings.domain

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.assignmate.app.core.domain.prefs.ThemeMode

/**
 * 护眼设置（三档主题）的档位 -> 生效状态映射（纯逻辑，便于单测）。
 *
 * 三档语义：
 * - [ThemeMode.SYSTEM]（跟随系统）：深浅色交由系统决定，不覆盖；
 * - [ThemeMode.EYE_CARE_LIGHT]（护眼浅色）：固定浅色低蓝光配色；
 * - [ThemeMode.EYE_CARE_DARK]（护眼夜间）：固定夜间低蓝光暖棕配色。
 */
object EyeCareTheme {

    /** 页面展示用的三档顺序（与 core [ThemeMode] 声明顺序一致） */
    val MODES: List<ThemeMode> = ThemeMode.entries.toList()

    /**
     * 档位映射为「深浅色决策」：null = 跟随系统（由调用方传系统值），
     * true = 固定夜间，false = 固定浅色。
     */
    fun darkThemeOverride(mode: ThemeMode): Boolean? = when (mode) {
        ThemeMode.SYSTEM -> null
        ThemeMode.EYE_CARE_LIGHT -> false
        ThemeMode.EYE_CARE_DARK -> true
    }

    /** 结合系统深浅色，计算某档位下的实际深浅色（null 档位按跟随系统处理） */
    fun resolveDarkTheme(mode: ThemeMode?, systemInDarkTheme: Boolean): Boolean =
        mode?.let { darkThemeOverride(it) } ?: systemInDarkTheme
}

/**
 * 组合函数便捷入口：以当前三档偏好 + 系统深浅色得出 `AssignMateTheme(darkTheme = ...)` 的入参。
 *
 * 供 framework 在应用根节点接线（settings 模块只提供口径，不改宿主）：
 * `val dark = resolveEyeCareDarkTheme(mode)`。
 */
@Composable
@ReadOnlyComposable
fun resolveEyeCareDarkTheme(mode: ThemeMode?): Boolean =
    EyeCareTheme.resolveDarkTheme(mode, isSystemInDarkTheme())