package com.assignmate.app.settings

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.settings.data.OcrConfigSaveResult
import com.assignmate.app.settings.data.SettingsOcrRepository
import com.assignmate.app.settings.data.SettingsOcrRepositoryImpl
import com.assignmate.app.settings.domain.SettingsDenialReason
import com.assignmate.app.settings.domain.SettingsRoleGuard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 配置读写仓库单测：**保存往返**、变更可观察、密钥不落日志，以及学生会话读写被拒（数据层保险）。
 *
 * 与会话红线相关的集中断言见 [SettingsOcrRoleFenceTest]，本测试侧重往返与日志安全。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsOcrRepositoryTest {

    private val parentSession = SessionState(role = Role.PARENT, parentId = 1L, studentId = null)
    private val studentSession = SessionState(role = Role.STUDENT, parentId = 1L, studentId = 2L)

    private val configured = OcrConfig(
        apiBaseUrl = "https://api.example.com/v1",
        modelName = "gpt-4o-mini",
        apiKey = "sk-secret-9f8e7d6c",
        enabled = true,
    )

    private class Env(
        val store: FakeOcrConfigStore,
        val logSink: RecordingOcrLogSink,
        val repository: SettingsOcrRepository,
        val auth: FakeSettingsAuthRepository,
    )

    private fun newEnv(
        session: SessionState = SessionState(role = Role.PARENT, parentId = 1L),
        initial: OcrConfig = OcrConfig(),
    ): Env {
        val store = FakeOcrConfigStore(initial)
        val logSink = RecordingOcrLogSink()
        val auth = FakeSettingsAuthRepository(session)
        val repository = SettingsOcrRepositoryImpl(store, auth, SettingsRoleGuard(auth), logSink)
        return Env(store, logSink, repository, auth)
    }

    // ---- 保存往返 ----

    @Test
    fun `家长保存配置后落盘并可读回`() = runTest {
        val env = newEnv()

        assertEquals(OcrConfigSaveResult.Saved, env.repository.save(configured))

        assertEquals(1, env.store.saveCount)
        assertEquals(configured, env.store.saved)
        assertEquals(configured, env.repository.observeConfig().first())
    }

    @Test
    fun `保存往返 新实例读到同一配置`() = runTest {
        val env = newEnv()
        env.repository.save(configured)

        val reopened = SettingsOcrRepositoryImpl(
            env.store,
            env.auth,
            SettingsRoleGuard(env.auth),
            RecordingOcrLogSink(),
        )

        assertEquals(configured, reopened.observeConfig().first())
    }

    @Test
    fun `关闭启用开关的配置同样可保存与读回`() = runTest {
        val env = newEnv()
        val disabled = configured.copy(enabled = false)

        assertEquals(OcrConfigSaveResult.Saved, env.repository.save(disabled))
        assertEquals(false, env.repository.observeConfig().first().enabled)
    }

    @Test
    fun `配置变更后观察流自动发射新值`() = runTest {
        val env = newEnv()
        env.repository.save(configured)
        val emitted = mutableListOf<String>()
        val job = launch { env.repository.observeConfig().collect { emitted += it.modelName } }
        advanceUntilIdle()
        assertEquals(listOf("gpt-4o-mini"), emitted)

        env.repository.save(configured.copy(modelName = "qwen-vl-max"))
        advanceUntilIdle()
        job.cancel()

        assertEquals(listOf("gpt-4o-mini", "qwen-vl-max"), emitted)
    }

    @Test
    fun `配置流当前值不触发额外保存`() = runTest {
        val env = newEnv(initial = configured)

        assertEquals(configured, env.repository.observeConfig().first())
        assertEquals(0, env.store.saveCount)
    }

    // ---- 密钥不落日志 ----

    @Test
    fun `保存成功不把密钥写入日志`() = runTest {
        val env = newEnv()

        env.repository.save(configured)

        assertTrue("保存应留下一条日志（便于排障）", env.logSink.lines.isNotEmpty())
        assertFalse(
            "日志不得包含密钥明文",
            env.logSink.joined().contains(configured.apiKey),
        )
        assertFalse(env.logSink.joined().contains("sk-secret"))
        // 允许记录「密钥是否存在」这一非敏感事实
        assertTrue(env.logSink.joined().contains("apiKeyConfigured=true"))
    }

    @Test
    fun `空密钥保存的日志只反映未配置状态`() = runTest {
        val env = newEnv()

        env.repository.save(configured.copy(apiKey = ""))

        assertTrue(env.logSink.joined().contains("apiKeyConfigured=false"))
    }

    @Test
    fun `拒绝保存的日志不含密钥明文`() = runTest {
        val env = newEnv(session = studentSession)

        env.repository.save(configured)

        assertFalse(env.logSink.joined().contains(configured.apiKey))
    }

    @Test
    fun `保存日志中的配置投影不含密钥字段`() = runTest {
        val env = newEnv()

        env.repository.save(configured)

        val line = env.logSink.joined()
        assertTrue(line.contains("apiBaseUrl=https://api.example.com/v1"))
        assertTrue(line.contains("modelName=gpt-4o-mini"))
        assertFalse("投影不得包含 apiKey 键值", line.contains("apiKey="))
    }

    // ---- 学生会话读写被拒（摘要） ----

    @Test
    fun `学生会话保存被拒且未触达存储`() = runTest {
        val env = newEnv(session = studentSession)

        val result = env.repository.save(configured)

        assertTrue(result is OcrConfigSaveResult.Denied)
        assertEquals(
            SettingsDenialReason.STUDENT_FORBIDDEN,
            (result as OcrConfigSaveResult.Denied).reason,
        )
        assertEquals("被拒绝时不得写入存储", 0, env.store.saveCount)
        assertEquals(OcrConfig(), env.store.saved)
    }

    @Test
    fun `学生会话读取不到任何配置`() = runTest {
        val env = newEnv(session = studentSession, initial = configured)
        val emitted = mutableListOf<OcrConfig>()
        val job = launch { env.repository.observeConfig().collect { emitted += it } }

        advanceUntilIdle()
        job.cancel()

        assertEquals("学生端不应读到任何配置（含密钥）", emptyList<OcrConfig>(), emitted)
    }

    @Test
    fun `未登录会话保存被拒且未触达存储`() = runTest {
        val env = newEnv(session = SessionState(role = null))

        val result = env.repository.save(configured)

        assertEquals(
            SettingsDenialReason.NOT_SIGNED_IN,
            (result as OcrConfigSaveResult.Denied).reason,
        )
        assertEquals(0, env.store.saveCount)
    }

    @Test
    fun `学生会话在订阅期间始终读不到配置`() = runTest {
        val env = newEnv(session = studentSession, initial = configured)
        val emitted = mutableListOf<OcrConfig>()
        val job = launch { env.repository.observeConfig().collect { emitted += it } }
        advanceUntilIdle()

        // 即使配置在后台被改写（家长端另开设置页保存），学生端也不应收到
        env.store.save(configured.copy(apiKey = "sk-should-not-leak"))
        advanceUntilIdle()
        job.cancel()

        assertEquals(emptyList<OcrConfig>(), emitted)
    }

    @Test
    fun `家长登出后学生无法继续读到配置`() = runTest {
        val env = newEnv(initial = configured)
        val emitted = mutableListOf<OcrConfig>()
        val job = launch { env.repository.observeConfig().collect { emitted += it } }
        advanceUntilIdle()
        assertEquals(listOf(configured), emitted)

        env.auth.setSession(studentSession)
        advanceUntilIdle()
        job.cancel()
        // 家长会话期间只应读到一次配置；登出后不再有新的发射
        assertEquals(listOf(configured), emitted)

        val afterLogout = mutableListOf<OcrConfig>()
        val job2 = launch { env.repository.observeConfig().collect { afterLogout += it } }
        advanceUntilIdle()
        job2.cancel()
        assertEquals(emptyList<OcrConfig>(), afterLogout)
    }
}