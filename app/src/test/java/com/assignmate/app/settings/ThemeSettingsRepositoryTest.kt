package com.assignmate.app.settings

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.settings.data.ThemeSettingsRepository
import com.assignmate.app.settings.data.ThemeSettingsRepositoryImpl
import com.assignmate.app.settings.data.ThemeSettingsResult
import com.assignmate.app.settings.domain.SettingsDenialReason
import com.assignmate.app.settings.domain.SettingsRoleGuard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 护眼设置读写单测：**三档映射与持久化往返**、学生可写、未登录写入被拒且不落盘。
 */
class ThemeSettingsRepositoryTest {

    private val parentSession = SessionState(role = Role.PARENT, parentId = 1L, studentId = null)
    private val studentSession = SessionState(role = Role.STUDENT, parentId = 1L, studentId = 2L)
    private val noSession = SessionState(role = null)

    private fun newRepository(
        store: FakeThemePreferenceStore,
        session: SessionState,
    ): Pair<ThemeSettingsRepository, FakeSettingsAuthRepository> {
        val auth = FakeSettingsAuthRepository(session)
        return ThemeSettingsRepositoryImpl(store, SettingsRoleGuard(auth)) to auth
    }

    @Test
    fun `默认档位为跟随系统`() = runTest {
        val (repository, _) = newRepository(FakeThemePreferenceStore(), parentSession)

        assertEquals(ThemeMode.DEFAULT, repository.themeMode())
        assertEquals(ThemeMode.SYSTEM, repository.themeMode())
        assertEquals(ThemeMode.SYSTEM, repository.observeThemeMode().first())
    }

    @Test
    fun `三档写入后读回与观察一致且落盘为枚举名`() = runTest {
        val store = FakeThemePreferenceStore()
        val (repository, _) = newRepository(store, parentSession)

        ThemeMode.entries.forEach { mode ->
            val result = repository.setThemeMode(mode)

            assertEquals(ThemeSettingsResult.Saved, result)
            assertEquals(mode, store.stored)
            assertEquals(mode, repository.themeMode())
            assertEquals(mode, repository.observeThemeMode().first())
        }
        assertEquals(3, store.setCount)
    }

    @Test
    fun `持久化往返 新实例可读到同一档位`() = runTest {
        val store = FakeThemePreferenceStore()
        val (first, _) = newRepository(store, studentSession)
        first.setThemeMode(ThemeMode.EYE_CARE_LIGHT)

        val (second, _) = newRepository(store, parentSession)

        assertEquals(ThemeMode.EYE_CARE_LIGHT, second.themeMode())
    }

    @Test
    fun `学生会话可写护眼设置`() = runTest {
        val store = FakeThemePreferenceStore()
        val (repository, _) = newRepository(store, studentSession)

        val result = repository.setThemeMode(ThemeMode.EYE_CARE_DARK)

        assertEquals(ThemeSettingsResult.Saved, result)
        assertEquals(ThemeMode.EYE_CARE_DARK, store.stored)
    }

    @Test
    fun `未登录会话写入被拒且不落盘`() = runTest {
        val store = FakeThemePreferenceStore(initial = ThemeMode.SYSTEM)
        val (repository, _) = newRepository(store, noSession)

        val result = repository.setThemeMode(ThemeMode.EYE_CARE_DARK)

        assertTrue(result is ThemeSettingsResult.Denied)
        assertEquals(
            SettingsDenialReason.NOT_SIGNED_IN,
            (result as ThemeSettingsResult.Denied).reason,
        )
        assertEquals(0, store.setCount)
        assertEquals(ThemeMode.SYSTEM, store.stored)
    }

    @Test
    fun `读路径不设会话门槛便于启动恢复`() = runTest {
        val store = FakeThemePreferenceStore(initial = ThemeMode.EYE_CARE_DARK)
        val (repository, _) = newRepository(store, noSession)

        assertEquals(ThemeMode.EYE_CARE_DARK, repository.themeMode())
        assertEquals(ThemeMode.EYE_CARE_DARK, repository.observeThemeMode().first())
    }
}