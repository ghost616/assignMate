package com.assignmate.app.settings

import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.settings.domain.EyeCareTheme
import com.assignmate.app.settings.ui.ThemeSettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 护眼三档「档位 -> 生效状态」映射单测：跟随时不覆盖系统深浅色，两档护眼固定深浅色。
 */
class EyeCareThemeTest {

    @Test
    fun `三档顺序与中文文案固定`() {
        assertEquals(
            listOf("跟随系统", "护眼浅色", "护眼夜间"),
            EyeCareTheme.MODES.map { it.label },
        )
        assertEquals(ThemeMode.entries.toList(), EyeCareTheme.MODES)
    }

    @Test
    fun `跟随系统不覆盖系统深浅色`() {
        assertNull(EyeCareTheme.darkThemeOverride(ThemeMode.SYSTEM))

        assertTrue(EyeCareTheme.resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(EyeCareTheme.resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
    }

    @Test
    fun `护眼浅色固定浅色且不受系统影响`() {
        assertEquals(false, EyeCareTheme.darkThemeOverride(ThemeMode.EYE_CARE_LIGHT))
        assertFalse(EyeCareTheme.resolveDarkTheme(ThemeMode.EYE_CARE_LIGHT, systemInDarkTheme = true))
        assertFalse(EyeCareTheme.resolveDarkTheme(ThemeMode.EYE_CARE_LIGHT, systemInDarkTheme = false))
    }

    @Test
    fun `护眼夜间固定夜间且不受系统影响`() {
        assertEquals(true, EyeCareTheme.darkThemeOverride(ThemeMode.EYE_CARE_DARK))
        assertTrue(EyeCareTheme.resolveDarkTheme(ThemeMode.EYE_CARE_DARK, systemInDarkTheme = true))
        assertTrue(EyeCareTheme.resolveDarkTheme(ThemeMode.EYE_CARE_DARK, systemInDarkTheme = false))
    }

    @Test
    fun `档位为 null 时按跟随系统处理`() {
        assertTrue(EyeCareTheme.resolveDarkTheme(null, systemInDarkTheme = true))
        assertFalse(EyeCareTheme.resolveDarkTheme(null, systemInDarkTheme = false))
    }

    @Test
    fun `页面状态暴露三档与当前档位文案`() {
        val state = ThemeSettingsUiState(loading = false, selectedMode = ThemeMode.EYE_CARE_DARK)

        assertEquals(3, state.modes.size)
        assertEquals("护眼夜间", state.currentModeText)
        assertEquals(true, state.darkThemeOverride)
        assertTrue(state.hasSelection)
    }
}