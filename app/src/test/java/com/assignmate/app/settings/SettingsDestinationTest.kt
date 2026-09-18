package com.assignmate.app.settings

import com.assignmate.app.settings.ui.SettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * settings 路由常量契约单测：framework 的 NavHost 接线依赖这些字面量，
 * 改动即破坏导航，故用测试钉住（与 TimerDestinationTest / AuthDestination 同口径）。
 */
class SettingsDestinationTest {

    @Test
    fun `三条路由常量保持接线契约`() {
        assertEquals("settings/home", SettingsDestination.HOME)
        assertEquals("settings/ocr_config", SettingsDestination.OCR_CONFIG)
        assertEquals("settings/theme", SettingsDestination.THEME)
    }

    @Test
    fun `路由互不重复且统一 settings 前缀`() {
        val routes = listOf(
            SettingsDestination.HOME,
            SettingsDestination.OCR_CONFIG,
            SettingsDestination.THEME,
        )

        assertEquals(routes.size, routes.toSet().size)
        assertEquals(3, routes.size)
        routes.forEach { route ->
            assertEquals("路由 $route 应以 settings/ 为前缀", true, route.startsWith("settings/"))
            assertNotEquals("", route)
        }
    }
}