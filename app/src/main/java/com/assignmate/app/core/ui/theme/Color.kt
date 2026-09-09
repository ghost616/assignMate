package com.assignmate.app.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * core 公共儿童主题配色（低蓝光暖色调）：色值与 res/values(-night)/colors.xml 完全一致，
 * 与 framework 占位主题（app.ui.theme.Color.kt）同源，公共主题收敛后以本文件为唯一来源。
 */

// ---- 品牌/主色（明亮友好蓝） ----
private val Primary = Color(0xFF3B82F6)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryContainer = Color(0xFFDBEAFE)
private val OnPrimaryContainer = Color(0xFF1E3A8A)

// ---- 辅助/强调色（温暖琥珀黄） ----
private val Secondary = Color(0xFFF59E0B)
private val OnSecondary = Color(0xFFFFFFFF)
private val SecondaryContainer = Color(0xFFFEF3C7)
private val OnSecondaryContainer = Color(0xFF78350F)

// ---- 背景（低蓝光暖色调）/错误 ----
private val Background = Color(0xFFFFF8EF)
private val Surface = Color(0xFFFFFDF8)
private val OnBackground = Color(0xFF201B12)
private val Error = Color(0xFFB3261E)

// ---- 夜间低蓝光变体（暖棕黑基调） ----
private val PrimaryDark = Color(0xFF93C5FD)
private val OnPrimaryDark = Color(0xFF1E3A8A)
private val PrimaryContainerDark = Color(0xFF1E3A8A)
private val OnPrimaryContainerDark = Color(0xFFDBEAFE)
private val SecondaryDark = Color(0xFFFCD34D)
private val OnSecondaryDark = Color(0xFF78350F)
private val SecondaryContainerDark = Color(0xFF78350F)
private val OnSecondaryContainerDark = Color(0xFFFEF3C7)
private val BackgroundDark = Color(0xFF171310)
private val SurfaceDark = Color(0xFF1F1A14)
private val OnBackgroundDark = Color(0xFFEDE6DA)

/** 浅色（白天）儿童低蓝光配色 */
internal val AssignMateLightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnBackground,
    error = Error,
)

/** 暗色（夜间低蓝光变体）儿童配色 */
internal val AssignMateDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnBackgroundDark,
    error = Error,
)
