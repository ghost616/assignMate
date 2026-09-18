package com.assignmate.app.settings

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.settings.data.OcrConfigSaveResult
import com.assignmate.app.settings.data.SettingsOcrRepositoryImpl
import com.assignmate.app.settings.domain.SettingsDenialReason
import com.assignmate.app.settings.domain.SettingsFeature
import com.assignmate.app.settings.domain.SettingsRoleGuard
import com.assignmate.app.settings.ui.SettingsUiState
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分权红线自动化断言（需求「必须」项）：**学生会话访问/写入 OCR 配置一律被拒绝**，双保险各自可验证。
 *
 * 三层断言：
 * 1. 仓库层（用例/数据）：学生 `save` 返回 Denied 且不触达存储；`observeConfig` 不发射任何配置；
 * 2. 页面层：OCR 入口可见性判定为 false（入口不渲染）、页面状态可表达「整页拒绝」；
 * 3. 静态守卫：settings 模块源码中**不存在**打印 `apiKey` 的日志调用（密钥不落日志的源码级保险）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsOcrRoleFenceTest {

    private val studentSession = SessionState(role = Role.STUDENT, parentId = 1L, studentId = 2L)
    private val parentSession = SessionState(role = Role.PARENT, parentId = 1L, studentId = null)

    private val secretConfig = OcrConfig(
        apiBaseUrl = "https://api.example.com/v1",
        modelName = "gpt-4o-mini",
        apiKey = "sk-student-must-not-read-9f8e7d6c",
        enabled = true,
    )

    // ---- 1. 仓库层 ----

    @Test
    fun `学生会话写入 OCR 配置被仓库拒绝且不落盘`() = runTest {
        val store = FakeOcrConfigStore(OcrConfig())
        val logSink = RecordingOcrLogSink()
        val auth = FakeSettingsAuthRepository(studentSession)
        val repository = SettingsOcrRepositoryImpl(store, auth, SettingsRoleGuard(auth), logSink)

        val result = repository.save(secretConfig)

        assertEquals(
            SettingsDenialReason.STUDENT_FORBIDDEN,
            (result as OcrConfigSaveResult.Denied).reason,
        )
        assertEquals(0, store.saveCount)
        assertEquals(OcrConfig(), store.saved)
    }

    @Test
    fun `学生会话读取 OCR 配置被仓库拒绝且不发射内容`() = runTest {
        val store = FakeOcrConfigStore(secretConfig)
        val logSink = RecordingOcrLogSink()
        val auth = FakeSettingsAuthRepository(studentSession)
        val repository = SettingsOcrRepositoryImpl(store, auth, SettingsRoleGuard(auth), logSink)

        val emitted = withTimeoutOrNull(1_000L) { repository.observeConfig().first() }

        assertEquals(null, emitted)
    }

    // ---- 2. 页面层 ----

    @Test
    fun `学生会话看不到 OCR 配置入口`() {
        assertFalse(SettingsRoleGuard.canShowOcrEntry(studentSession))
        assertFalse(SettingsRoleGuard.canShowOcrEntry(SessionState(role = null)))
        assertTrue(SettingsRoleGuard.canShowOcrEntry(parentSession))
    }

    @Test
    fun `学生会话对 OCR 配置的拒绝原因明确`() = runTest {
        val auth = FakeSettingsAuthRepository(studentSession)
        val guard = SettingsRoleGuard(auth)

        assertEquals(
            SettingsDenialReason.STUDENT_FORBIDDEN,
            guard.denialReason(SettingsFeature.OCR_CONFIG),
        )
        // 护眼设置仍可访问（学生端专属入口）
        assertEquals(null, guard.denialReason(SettingsFeature.THEME))
    }

    @Test
    fun `未登录会话对 OCR 配置按未登录处理而非放行`() = runTest {
        val auth = FakeSettingsAuthRepository(SessionState(role = null))
        val guard = SettingsRoleGuard(auth)

        assertEquals(
            SettingsDenialReason.NOT_SIGNED_IN,
            guard.denialReason(SettingsFeature.OCR_CONFIG),
        )
    }

    @Test
    fun `设置主页状态在拒绝态下不提供 OCR 入口与清理能力`() {
        val deniedState = SettingsUiState(
            role = Role.STUDENT,
            canAccessOcrConfig = false,
            accessDenied = true,
            pendingRecordCount = 3,
        )

        assertFalse(deniedState.canAccessOcrConfig)
        assertTrue(deniedState.accessDenied)
        assertFalse("拒绝态不应允许清理", deniedState.canCleanup)
    }

    // ---- 3. 源码级保险：不得把密钥写进日志 ----

    @Test
    fun `settings 源码中不存在打印密钥的日志调用`() {
        val root = locateSettingsSourceDir()
        assertTrue("settings 源码目录应存在（实际解析为 ${root.absolutePath}）", root.isDirectory)

        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("settings 源码应包含 Kotlin 文件", files.isNotEmpty())

        val sourceTokens = files
            .flatMap { file -> LOG_CALL_KEYS_TOKEN.findAll(file.readText()).map { it.groupValues[1] } }
            .toList()

        assertTrue(
            "settings 模块不得把 apiKey 传入任何日志调用（发现：${sourceTokens.distinct()}）",
            sourceTokens.none { it.contains("apiKey") },
        )
    }

    /**
     * 定位 settings 源码目录：单测工作目录可能是模块目录（`app`）或项目根目录，
     * 故自工作目录逐级向上查找，找不到时以模块目录为基准回退（断言会给出生动失败信息）。
     */
    private fun locateSettingsSourceDir(): File {
        val relative = "src/main/java/com/assignmate/app/settings"
        var base: File? = File("").absoluteFile
        while (base != null) {
            File(base, relative).takeIf { it.isDirectory }?.let { return it }
            File(base, "app/$relative").takeIf { it.isDirectory }?.let { return it }
            base = base.parentFile
        }
        return File("app/$relative")
    }

    private companion object {

        /**
         * 匹配日志调用的第一个实参表达式（`Log.d("tag", <expr>)` / `logSink.log("tag", <expr>)`）。
         *
         * 只做「源码里是否把 apiKey 传给日志」这一层保险，日志内容断言见 [SettingsOcrRepositoryTest]。
         */
        val LOG_CALL_KEYS_TOKEN = Regex("""(?:Log\.[a-z]|logSink\.log)\s*\([^,]*,\s*([^)\n]+)""")
    }
}