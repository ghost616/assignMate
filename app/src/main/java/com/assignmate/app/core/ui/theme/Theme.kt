package com.assignmate.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * core 公共主题入口：Material3 + 儿童低蓝光配色（昼夜自动切换）+ 儿童友好排版。
 *
 * 供各 feature 模块（auth/homework/timer/stats/settings）统一引用；
 * framework 占位主题（app.ui.theme.AssignMateTheme）收敛后改由此代理，避免配色双源漂移。
 */
@Composable
fun AssignMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AssignMateDarkColorScheme else AssignMateLightColorScheme,
        typography = AssignMateTypography,
        content = content,
    )
}
