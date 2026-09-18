package com.assignmate.app.navigation

import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.settings.domain.EyeCareTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题档位 -> 深浅色映射单测（framework：宿主主题偏好生效口径）。
 *
 * 宿主 [com.assignmate.app.MainActivity] 订阅 core 的主题偏好后，按
 * [EyeCareTheme.resolveDarkTheme] 决定 `AssignMateTheme(darkTheme = ...)`，
 * 本类覆盖三档（跟随系统 / 护眼浅色 / 护眼夜间）的映射结果与回落口径：
 *
 * 1. 三档映射：护眼浅色 -> false（浅色）、护眼夜间 -> true（深色）、跟随系统 -> null（交由系统）；
 * 2. 跟随系统回落：档位为 SYSTEM 时取系统深浅色（系统深 -> 深、系统浅 -> 浅）；
 * 3. 缺失/非法回落：档位为 null 时按跟随系统处理，绝不崩溃也不硬编码某一档；
 * 4. 全枚举穷尽：每个 [ThemeMode] 都有确定映射，新增档位不会静默落到错误分支；
 * 5. 宿主接线：MainActivity 以偏好观察流 + DEFAULT 初值决定 darkTheme，切换档位即时生效（无重启依赖）。
 */
class ThemeDarknessMappingTest {

    // ---- 1. 三档映射 ----

    @Test
    fun `护眼浅色映射为浅色`() {
        assertEquals(false, EyeCareTheme.darkThemeOverride(ThemeMode.EYE_CARE_LIGHT))
        assertEquals(false, EyeCareTheme.resolveDarkTheme(ThemeMode.EYE_CARE_LIGHT, systemInDarkTheme = false))
        assertEquals(
            "护眼浅色应固定浅色、不受系统深浅色影响",
            false,
            EyeCareTheme.resolveDarkTheme(ThemeMode.EYE_CARE_LIGHT, systemInDarkTheme = true),
        )
    }

    @Test
    fun `护眼夜间映射为深色`() {
        assertEquals(true, EyeCareTheme.darkThemeOverride(ThemeMode.EYE_CARE_DARK))
        assertEquals(true, EyeCareTheme.resolveDarkTheme(ThemeMode.EYE_CARE_DARK, systemInDarkTheme = false))
        assertEquals(
            "护眼夜间应固定深色、不受系统深浅色影响",
            true,
            EyeCareTheme.resolveDarkTheme(ThemeMode.EYE_CARE_DARK, systemInDarkTheme = true),
        )
    }

    @Test
    fun `跟随系统档位不覆盖深浅色`() {
        assertNull(EyeCareTheme.darkThemeOverride(ThemeMode.SYSTEM))
        assertFalse(EyeCareTheme.resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
        assertTrue(EyeCareTheme.resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
    }

    // ---- 2. 跟随系统回落与非法值回落 ----

    @Test
    fun `档位缺失时回落到跟随系统`() {
        assertFalse(EyeCareTheme.resolveDarkTheme(null, systemInDarkTheme = false))
        assertTrue(EyeCareTheme.resolveDarkTheme(null, systemInDarkTheme = true))
    }

    @Test
    fun `非法存量值回落到跟随系统而非崩溃`() {
        // 缺失值（DataStore 键不存在）
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue(null))
        // 空串与空白
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue(" "))
        // 已是旧枚举名的小写/驼峰形态（历史脏数据）
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue("eye_care_dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue("EyeCareDark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue("DARK"))
        // 完全无关的值
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue("42"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromRawValue("{\"mode\":\"dark\"}"))

        // 兜底档位经映射后仍为「跟随系统」：系统深 -> 深、系统浅 -> 浅
        assertTrue(
            EyeCareTheme.resolveDarkTheme(ThemeMode.fromRawValue("nonsense"), systemInDarkTheme = true),
        )
        assertFalse(
            EyeCareTheme.resolveDarkTheme(ThemeMode.fromRawValue(null), systemInDarkTheme = false),
        )
    }

    @Test
    fun `合法枚举名可往返解析`() {
        ThemeMode.entries.forEach { mode ->
            assertEquals("枚举名 ${mode.name} 应可原样解析回该档位", mode, ThemeMode.fromRawValue(mode.name))
        }
    }

    // ---- 3. 穷尽性与默认档位 ----

    @Test
    fun `全枚举档位均有确定映射且默认档位为跟随系统`() {
        assertEquals("默认档位应为跟随系统", ThemeMode.SYSTEM, ThemeMode.DEFAULT)

        val overrides = ThemeMode.entries.map { EyeCareTheme.darkThemeOverride(it) }
        assertEquals("三档应各有一个映射结果", ThemeMode.entries.size, overrides.size)
        assertEquals(
            "映射结果应为 null（跟随系统）/ false（固定浅色）/ true（固定深色）各一次",
            listOf(null, false, true),
            overrides,
        )
        assertEquals("三档顺序应与页面渲染顺序一致", ThemeMode.entries.toList(), EyeCareTheme.MODES)
        assertEquals(
            "护眼浅色 -> 浅色、护眼夜间 -> 深色、跟随系统 -> null",
            ThemeMode.entries.size,
            overrides.count { it != null } + 1,
        )
    }

    // ---- 4. 宿主接线：即时生效，无重启依赖 ----

    @Test
    fun `宿主订阅主题偏好并按其决定深浅色`() {
        val source = readSource("src/main/java/com/assignmate/app/MainActivity.kt")

        assertTrue(
            "宿主应订阅 core 的主题偏好观察流",
            source.contains("themePreferenceStore.observeThemeMode()"),
        )
        assertTrue(
            "偏好应经 collectAsStateWithLifecycle 呈现为 Compose 状态（写库后即时重组、无需重启）",
            source.contains("collectAsStateWithLifecycle(initialValue = ThemeMode.DEFAULT)"),
        )
        assertTrue(
            "深浅色应经 EyeCareTheme.resolveDarkTheme 口径决定（与护眼设置页同一来源）",
            source.contains("EyeCareTheme.resolveDarkTheme(themeMode, isSystemInDarkTheme())"),
        )
        assertTrue(
            "根主题应把解析结果传给 AssignMateTheme(darkTheme = ...)",
            source.contains("AssignMateTheme(") && source.contains("darkTheme = "),
        )
        assertTrue(
            "仍为单 Activity + Compose 导航宿主结构（主题包裹 AssignMateNavHost）",
            source.contains("class MainActivity : ComponentActivity()") &&
                source.contains("AssignMateNavHost()"),
        )
        assertFalse(
            "宿主不应硬编码某一护眼档位（口径只来自偏好流）",
            source.contains("ThemeMode.EYE_CARE_"),
        )
    }

    @Test
    fun `主题偏好存储契约以枚举名落盘并统一兜底`() {
        val storeSource = readSource("src/main/java/com/assignmate/app/core/domain/prefs/ThemePreferenceStore.kt")
        assertTrue(
            "偏好键名应只有一处来源（宿主/设置页均不硬编码键名）",
            storeSource.contains("const val KEY_THEME_MODE = \"theme_mode\""),
        )

        val dataStoreSource = readSource(
            "src/main/java/com/assignmate/app/core/data/prefs/DataStoreThemePreferenceStore.kt",
        )
        assertTrue(
            "读取链路应统一经 ThemeMode.fromRawValue 兜底（缺失/非法值 -> 跟随系统）",
            dataStoreSource.contains("ThemeMode.fromRawValue"),
        )
    }

    // ---- 源码解析工具（与既有契约测试同一约定：Gradle 单测工作目录为 app/，兼容仓库根运行） ----

    private fun readSource(relative: String): String {
        val candidates = listOf(File(relative), File("app/" + relative), File("../app/" + relative))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到源文件 $relative，尝试路径：${candidates.map { it.absolutePath }}", file)
        return file!!.readText()
    }
}
