package com.assignmate.app.settings

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.settings.domain.SettingsDenialReason
import com.assignmate.app.settings.domain.SettingsFeature
import com.assignmate.app.settings.domain.SettingsRoleGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分权守卫单测（纯判定 + 会话来源注入两条路径）：
 *
 * 红线口径——学生会话访问 OCR 配置必须被拒绝；学生可访问护眼设置；
 * role 为 null（未登录/已登出）一律按未登录处理，**不得**因其「不是学生」而放行 OCR 配置。
 */
class SettingsRoleGuardTest {

    private val parentSession = SessionState(role = Role.PARENT, parentId = 1L, studentId = null)
    private val studentSession = SessionState(role = Role.STUDENT, parentId = 1L, studentId = 2L)
    private val noSession = SessionState(role = null)

    @Test
    fun `家长会话两项设置均放行`() {
        assertNull(
            SettingsRoleGuard.denialReasonOf(parentSession, SettingsFeature.OCR_CONFIG),
        )
        assertNull(
            SettingsRoleGuard.denialReasonOf(parentSession, SettingsFeature.THEME),
        )
    }

    @Test
    fun `学生会话访问 OCR 配置被拒绝`() {
        assertEquals(
            SettingsDenialReason.STUDENT_FORBIDDEN,
            SettingsRoleGuard.denialReasonOf(studentSession, SettingsFeature.OCR_CONFIG),
        )
    }

    @Test
    fun `学生会话可访问护眼设置`() {
        assertNull(SettingsRoleGuard.denialReasonOf(studentSession, SettingsFeature.THEME))
    }

    @Test
    fun `role 为 null 按未登录处理且不放行任何项`() {
        assertFalse(noSession.isActive)
        SettingsFeature.entries.forEach { feature ->
            assertEquals(
                "无会话访问 $feature 应视为未登录",
                SettingsDenialReason.NOT_SIGNED_IN,
                SettingsRoleGuard.denialReasonOf(noSession, feature),
            )
        }
    }

    @Test
    fun `学生角色但不完整会话仍拒绝 OCR 配置`() {
        // 学生会话即使缺少 parentId/studentId（异常态），也不得因「不是完整学生」而放行
        val incomplete = SessionState(role = Role.STUDENT, parentId = null, studentId = null)

        assertEquals(
            SettingsDenialReason.STUDENT_FORBIDDEN,
            SettingsRoleGuard.denialReasonOf(incomplete, SettingsFeature.OCR_CONFIG),
        )
    }

    @Test
    fun `OCR 入口可见性与访问判定同源`() {
        assertTrue(SettingsRoleGuard.canShowOcrEntry(parentSession))
        assertFalse(SettingsRoleGuard.canShowOcrEntry(studentSession))
        assertFalse(SettingsRoleGuard.canShowOcrEntry(noSession))
    }

    @Test
    fun `守卫实例按当前会话判定并随会话切换变化`() = runTest {
        val authRepository = FakeSettingsAuthRepository(studentSession)
        val guard = SettingsRoleGuard(authRepository)

        assertEquals(
            SettingsDenialReason.STUDENT_FORBIDDEN,
            guard.denialReason(SettingsFeature.OCR_CONFIG),
        )
        assertTrue(guard.canAccess(SettingsFeature.THEME))

        authRepository.setSession(parentSession)
        assertNull(guard.denialReason(SettingsFeature.OCR_CONFIG))

        authRepository.setSession(noSession)
        assertEquals(
            SettingsDenialReason.NOT_SIGNED_IN,
            guard.denialReason(SettingsFeature.OCR_CONFIG),
        )
    }
}