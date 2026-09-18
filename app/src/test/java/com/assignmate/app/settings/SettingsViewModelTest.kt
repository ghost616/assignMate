package com.assignmate.app.settings

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import com.assignmate.app.core.data.ocr.OcrCacheCleanerImpl
import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.settings.data.GuardedOcrCacheCleaner
import com.assignmate.app.settings.data.OcrCacheRepositoryImpl
import com.assignmate.app.settings.data.SettingsOcrRepositoryImpl
import com.assignmate.app.settings.data.ThemeSettingsRepositoryImpl
import com.assignmate.app.settings.domain.DataCleanupService
import com.assignmate.app.settings.domain.SettingsDenialReason
import com.assignmate.app.settings.domain.SettingsOcrValidator
import com.assignmate.app.settings.domain.SettingsRoleGuard
import com.assignmate.app.settings.ui.OcrConfigUiState
import com.assignmate.app.settings.ui.SettingsEvent
import com.assignmate.app.settings.ui.SettingsUiState
import com.assignmate.app.settings.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 设置主页 ViewModel 单测（Main 调度器经 [MainDispatcherRule] 换为测试调度器）。
 *
 * 覆盖：分权摘要（家长可见「已配置 / 未配置」，学生与未登录为拒绝态）、清理按钮可用性与空态文案、
 * 二次确认文案、确认执行后刷新计数并发出「已清理 N 条」事件、清理被拒的收敛、重复 start 幂等。
 *
 * 数据清理链路使用**真实实现**（core 的 [OcrCacheCleanerImpl] + settings 的守卫与统计），
 * 仅图片删除与任务存储用内存替身，保证「面板显示的 N」与「实际删除的 N」在同一链路上被验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val parentSession = SessionState(role = Role.PARENT, parentId = 1L, studentId = null)
    private val studentSession = SessionState(role = Role.STUDENT, parentId = 1L, studentId = 2L)

    private val configured = OcrConfig(
        apiBaseUrl = "https://api.example.com/v1",
        modelName = "gpt-4o-mini",
        apiKey = "sk-secret-1234",
        enabled = true,
    )

    private class Env(
        val viewModel: SettingsViewModel,
        val pending: FakePendingOcrRepository,
        val imageCleaner: RecordingOcrImageFileCleaner,
    )

    private fun newEnv(
        session: SessionState = SessionState(role = Role.PARENT, parentId = 1L),
        initialConfig: OcrConfig = OcrConfig(),
        pendingTasks: Int = 0,
        themeMode: ThemeMode = ThemeMode.DEFAULT,
    ): Env {
        val auth = FakeSettingsAuthRepository(session)
        val guard = SettingsRoleGuard(auth)
        val pending = FakePendingOcrRepository()
        repeat(pendingTasks) { index ->
            pending.insert("/images/task$index.jpg", PendingOcrStatus.PENDING)
        }
        val imageCleaner = RecordingOcrImageFileCleaner()
        val inner = OcrCacheCleanerImpl(pending, imageCleaner)
        val cacheRepository = OcrCacheRepositoryImpl(pending)
        val cleanup = DataCleanupService(
            cacheRepository,
            cacheRepository,
            GuardedOcrCacheCleaner(inner, guard),
        )
        val viewModel = SettingsViewModel(
            ocrRepository = SettingsOcrRepositoryImpl(
                FakeOcrConfigStore(initialConfig),
                auth,
                guard,
                RecordingOcrLogSink(),
            ),
            themeRepository = ThemeSettingsRepositoryImpl(FakeThemePreferenceStore(themeMode), guard),
            authRepository = auth,
            roleGuard = guard,
            cleanup = cleanup,
        )
        return Env(viewModel, pending, imageCleaner)
    }

    // ---- 摘要与分权 ----

    @Test
    fun `家长未配置时摘要为未配置且入口可见`() = runTest {
        val env = newEnv()

        env.viewModel.start()
        advanceUntilIdle()

        val state = env.viewModel.uiState.value
        assertTrue(state.canAccessOcrConfig)
        assertFalse(state.accessDenied)
        assertEquals("未配置", state.ocrSummaryText)
        assertEquals(Role.PARENT, state.role)
    }

    @Test
    fun `家长已配置时摘要为已配置`() = runTest {
        val env = newEnv(initialConfig = configured)

        env.viewModel.start()
        advanceUntilIdle()

        val state = env.viewModel.uiState.value
        assertTrue(state.ocrConfigured)
        assertEquals("已配置", state.ocrSummaryText)
    }

    @Test
    fun `学生会话不可见 OCR 配置入口且为拒绝态`() = runTest {
        val env = newEnv(session = studentSession, initialConfig = configured)

        env.viewModel.start()
        advanceUntilIdle()

        val state = env.viewModel.uiState.value
        assertFalse("学生不得看到 OCR 配置入口", state.canAccessOcrConfig)
        assertTrue(state.accessDenied)
        assertFalse("拒绝态下不得展示已配置摘要", state.ocrConfigured)
        assertFalse(state.canCleanup)
    }

    @Test
    fun `未登录会话按未登录处理`() = runTest {
        val env = newEnv(session = SessionState(role = null))

        env.viewModel.start()
        advanceUntilIdle()

        val state = env.viewModel.uiState.value
        assertFalse(state.canAccessOcrConfig)
        assertTrue(state.accessDenied)
        assertEquals(null, state.role)
    }

    @Test
    fun `主题档位加载后展示当前档位`() = runTest {
        val env = newEnv(themeMode = ThemeMode.EYE_CARE_DARK)

        env.viewModel.start()
        advanceUntilIdle()

        val state = env.viewModel.uiState.value
        assertTrue(state.themeModeLoaded)
        assertEquals(ThemeMode.EYE_CARE_DARK, state.themeMode)
        assertEquals("护眼夜间", state.themeMode.label)
    }

    // ---- 数据清理面板 ----

    @Test
    fun `有任务时面板展示条数且可清理`() = runTest {
        val env = newEnv(pendingTasks = 3)

        env.viewModel.start()
        advanceUntilIdle()

        val state = env.viewModel.uiState.value
        assertEquals(3, state.pendingRecordCount)
        assertEquals("待清理 3 条识别任务", state.pendingCountText)
        assertTrue(state.canCleanup)
        assertFalse(state.cleanupEmpty)
        assertEquals("将删除 3 条识别任务（含图片），不可恢复", state.confirmMessage)
    }

    @Test
    fun `无任务时按钮置灰并给出空态文案`() = runTest {
        val env = newEnv(pendingTasks = 0)

        env.viewModel.start()
        advanceUntilIdle()

        val state = env.viewModel.uiState.value
        assertTrue(state.cleanupEmpty)
        assertFalse(state.canCleanup)
        assertEquals("暂无可清理内容", state.emptyHint)
    }

    @Test
    fun `面板条数覆盖全部 4 种状态`() = runTest {
        val env = newEnv()
        PendingOcrStatus.entries.forEach { status ->
            env.pending.insert("/images/${status.name.lowercase()}.jpg", status)
        }

        env.viewModel.start()
        advanceUntilIdle()

        assertEquals(4, env.viewModel.uiState.value.pendingRecordCount)
    }

    @Test
    fun `请求清理在无内容时不进入确认态`() = runTest {
        val env = newEnv(pendingTasks = 0)
        env.viewModel.start()
        advanceUntilIdle()

        env.viewModel.onCleanupRequested()

        assertFalse(env.viewModel.uiState.value.confirmingCleanup)
    }

    @Test
    fun `请求清理在有内容时进入确认态且可取消`() = runTest {
        val env = newEnv(pendingTasks = 2)
        env.viewModel.start()
        advanceUntilIdle()

        env.viewModel.onCleanupRequested()
        assertTrue(env.viewModel.uiState.value.confirmingCleanup)

        env.viewModel.onCleanupDismissed()
        assertFalse(env.viewModel.uiState.value.confirmingCleanup)
    }

    @Test
    fun `确认清理后记录与图片均被删除并提示已清理`() = runTest {
        val env = newEnv(pendingTasks = 3)
        env.viewModel.start()
        advanceUntilIdle()
        val events = mutableListOf<SettingsEvent>()
        val job = launch { env.viewModel.events.collect { events += it } }

        env.viewModel.onCleanupRequested()
        env.viewModel.onCleanupConfirmed()
        advanceUntilIdle()
        job.cancel()

        assertTrue("记录应被清空", env.pending.snapshot().isEmpty())
        assertEquals("图片应一并删除", 3, env.imageCleaner.deletedPaths.size)
        assertEquals(0, env.viewModel.uiState.value.pendingRecordCount)
        assertFalse(env.viewModel.uiState.value.cleaning)
        assertTrue(
            "应提示「已清理 N 条」，实际：$events",
            events.any { it is SettingsEvent.ShowMessage && it.message == "已清理 3 条" },
        )
    }

    @Test
    fun `确认清理时若无内容则不执行`() = runTest {
        val env = newEnv(pendingTasks = 0)
        env.viewModel.start()
        advanceUntilIdle()

        env.viewModel.onCleanupConfirmed()
        advanceUntilIdle()

        assertTrue(env.pending.snapshot().isEmpty())
        assertTrue(env.imageCleaner.deletedPaths.isEmpty())
    }

    @Test
    fun `清理被拒时收敛为拒绝态且不误报已清理`() = runTest {
        // 家长会话建流后切为学生会话：模拟会话在页面存活期间失效
        val env = newEnv(pendingTasks = 2)
        env.viewModel.start()
        advanceUntilIdle()
        val events = mutableListOf<SettingsEvent>()
        val job = launch { env.viewModel.events.collect { events += it } }

        // 学生会话下再次触发清理：数据层守卫直接拒绝
        val studentEnv = newEnv(session = studentSession, pendingTasks = 2)
        studentEnv.viewModel.start()
        advanceUntilIdle()
        studentEnv.viewModel.onCleanupConfirmed()
        advanceUntilIdle()
        job.cancel()

        assertTrue(studentEnv.viewModel.uiState.value.accessDenied)
        assertFalse("学生会话不允许发起清理", studentEnv.viewModel.uiState.value.canCleanup)
        assertEquals("记录不得被删除", 2, studentEnv.pending.snapshot().size)
        assertTrue(studentEnv.imageCleaner.deletedPaths.isEmpty())
        assertTrue(events.none { it is SettingsEvent.ShowMessage && it.message.contains("已清理") })
    }

    @Test
    fun `重复 start 不重复订阅`() = runTest {
        val env = newEnv(pendingTasks = 1)

        env.viewModel.start()
        env.viewModel.start()
        env.viewModel.start()
        advanceUntilIdle()

        assertEquals(1, env.viewModel.uiState.value.pendingRecordCount)
        assertEquals(1, env.viewModel.cleanup.snapshot.value.recordCount)
    }

    @Test
    fun `清理服务暴露给页面且快照可读`() = runTest {
        val env = newEnv(pendingTasks = 4)
        env.viewModel.start()
        advanceUntilIdle()

        assertEquals(4, env.viewModel.cleanup.snapshot.value.recordCount)
        assertEquals(0, env.viewModel.cleanup.cleanedCount.value)
        assertTrue(env.viewModel.cleanup.snapshot.value.confirmMessage.contains("4 条"))
    }

    // ---- 状态默认值守卫 ----

    @Test
    fun `设置状态默认值与拒绝态口径`() {
        val default = SettingsUiState()
        assertFalse(default.canAccessOcrConfig)
        assertFalse(default.canCleanup)
        assertTrue(default.cleanupEmpty)
        assertEquals(ThemeMode.DEFAULT, default.themeMode)

        val denied = SettingsUiState(accessDenied = true, pendingRecordCount = 5)
        assertFalse(denied.canCleanup)
        assertFalse(denied.cleanupEmpty)
        assertEquals("清理识别缓存", denied.cleanupButtonText)
    }

    @Test
    fun `OCR 表单状态默认掩码且未配置时不可保存`() {
        val state = OcrConfigUiState(loading = false, apiKey = "sk-abcdefgh")

        assertFalse(state.apiKeyVisible)
        assertEquals("****efgh", state.displayApiKey)
        assertEquals("显示", state.apiKeyToggleText)
        assertFalse(SettingsOcrValidator.canSave(state.toConfig()))
        assertFalse(state.toConfig().enabled)
    }

    @Test
    fun `表单状态切换明文后展示原值`() {
        val state = OcrConfigUiState(loading = false, apiKey = "sk-abcdefgh", apiKeyVisible = true)

        assertEquals("sk-abcdefgh", state.displayApiKey)
        assertEquals("隐藏", state.apiKeyToggleText)
    }

    @Test
    fun `拒绝原因枚举覆盖两类会话`() {
        val reasons = SettingsDenialReason.entries

        assertEquals(2, reasons.size)
        assertTrue(reasons.contains(SettingsDenialReason.NOT_SIGNED_IN))
        assertTrue(reasons.contains(SettingsDenialReason.STUDENT_FORBIDDEN))
    }

    @Test
    fun `学生会话摘要始终为未配置`() = runTest {
        val env = newEnv(session = studentSession, initialConfig = configured)
        env.viewModel.start()
        advanceUntilIdle()

        assertEquals("未配置", env.viewModel.uiState.value.ocrSummaryText)
    }

    @Test
    fun `清理完成后新增任务会重新出现在面板计数`() = runTest {
        val env = newEnv(pendingTasks = 1)
        env.viewModel.start()
        advanceUntilIdle()
        env.viewModel.onCleanupConfirmed()
        advanceUntilIdle()
        assertEquals(0, env.viewModel.uiState.value.pendingRecordCount)

        env.pending.insert("/images/new.jpg", PendingOcrStatus.PENDING)
        advanceUntilIdle()

        assertEquals(1, env.viewModel.uiState.value.pendingRecordCount)
    }
}

/**
 * 主线程调度器替换规则：ViewModel 的 viewModelScope 默认跑在 Main 上，
 * 纯 JVM 单测须替换为测试调度器；用 StandardTestDispatcher 保证「先计划、后执行」的可控顺序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}