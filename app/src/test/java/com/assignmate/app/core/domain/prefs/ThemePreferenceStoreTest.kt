package com.assignmate.app.core.domain.prefs

import com.assignmate.app.core.data.prefs.DataStoreThemePreferenceStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三档主题偏好契约单测（真实实现 + 内存 KeyValueStore）：
 * 默认档位、三档读写往返、可观察、落盘键值为枚举名、非法/缺失存量值一律兜底「跟随系统」、中文档位文案。
 */
class ThemePreferenceStoreTest {

    private fun newStore(keyValueStore: KeyValueStore = FakeKeyValueStore()): ThemePreferenceStore =
        DataStoreThemePreferenceStore(keyValueStore)

    @Test
    fun `三档档位与中文文案固定`() {
        assertEquals(
            listOf("跟随系统", "护眼浅色", "护眼夜间"),
            ThemeMode.entries.map { it.label },
        )
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DEFAULT)
    }

    @Test
    fun `未设置时默认跟随系统`() = runTest {
        val store = newStore()

        assertEquals(ThemeMode.SYSTEM, store.themeMode())
        assertEquals(ThemeMode.SYSTEM, store.observeThemeMode().first())
    }

    @Test
    fun `三档写入后读回与观察一致`() = runTest {
        val keyValueStore = FakeKeyValueStore()
        val store = newStore(keyValueStore)

        ThemeMode.entries.forEach { mode ->
            store.setThemeMode(mode)

            assertEquals(mode, store.themeMode())
            assertEquals(mode, store.observeThemeMode().first())
            assertEquals(
                "落盘值应为枚举名（稳定标识，不随文案变化）",
                mode.name,
                keyValueStore.getString(ThemePreferenceStore.KEY_THEME_MODE),
            )
        }
    }

    @Test
    fun `落盘后新实例可读到同一档位`() = runTest {
        val keyValueStore = FakeKeyValueStore()
        newStore(keyValueStore).setThemeMode(ThemeMode.EYE_CARE_DARK)

        assertEquals(ThemeMode.EYE_CARE_DARK, newStore(keyValueStore).themeMode())
    }

    @Test
    fun `非法或缺失的存量值一律兜底跟随系统`() = runTest {
        val keyValueStore = FakeKeyValueStore()
        val store = newStore(keyValueStore)

        // 缺失（键从未写入）
        assertEquals(ThemeMode.SYSTEM, store.themeMode())
        assertEquals(ThemeMode.SYSTEM, store.observeThemeMode().first())

        listOf("NIGHT", "eye_care_dark", "EYE_CARE", "bogus", "", " ").forEach { raw ->
            keyValueStore.putString(ThemePreferenceStore.KEY_THEME_MODE, raw)

            assertEquals("存量值 [$raw] 应兜底为跟随系统", ThemeMode.SYSTEM, store.themeMode())
            assertEquals("存量值 [$raw] 应兜底为跟随系统", ThemeMode.SYSTEM, store.observeThemeMode().first())
        }
    }

    @Test
    fun `非法存量值可通过重新写入恢复为合法档位`() = runTest {
        val keyValueStore = FakeKeyValueStore()
        val store = newStore(keyValueStore)
        keyValueStore.putString(ThemePreferenceStore.KEY_THEME_MODE, "NIGHT")

        store.setThemeMode(ThemeMode.EYE_CARE_LIGHT)

        assertEquals(ThemeMode.EYE_CARE_LIGHT, store.themeMode())
        assertEquals(ThemeMode.EYE_CARE_LIGHT, store.observeThemeMode().first())
    }

    @Test
    fun `落盘值解析函数对已知档位与非法值分别处理`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromRawValue(mode.name))
        }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue("EYE_CARE_NIGHT"))
    }
}