package com.assignmate.app.timer.data

import com.assignmate.app.auth.data.FakeKeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计时相关偏好持久化单测（真实实现 + 内存 KeyValueStore）：
 * 语音开关默认开启与读写、超时提醒记录按作业隔离、休息起点按学生 + 作业隔离，
 * 以及各存储键的前缀与隔离约定。
 */
class TimerReminderPrefsTest {

    // ---- 语音开关 ----

    @Test
    fun `语音开关默认开启`() = runTest {
        val settings = DataStoreTimerVoiceSettings(FakeKeyValueStore())

        assertTrue(settings.isEnabled())
        assertTrue(settings.observeEnabled().first())
    }

    @Test
    fun `关闭语音后读回为关闭且可观察`() = runTest {
        val settings = DataStoreTimerVoiceSettings(FakeKeyValueStore())

        settings.setEnabled(false)

        assertFalse(settings.isEnabled())
        assertFalse(settings.observeEnabled().first())
    }

    @Test
    fun `语音开关落盘后可被新实例读取`() = runTest {
        val store = FakeKeyValueStore()
        DataStoreTimerVoiceSettings(store).setEnabled(false)

        assertFalse(DataStoreTimerVoiceSettings(store).isEnabled())
        // 落盘的原始键值同样为关闭（键名约定稳定，便于后续设置页复用）
        assertFalse(store.getBoolean(DataStoreTimerVoiceSettings.KEY_VOICE_ENABLED, true))
    }

    // ---- 超时提醒记录 ----

    @Test
    fun `超时提醒记录初始为空`() = runTest {
        val store = DataStoreTimerOverduePromptStore(FakeKeyValueStore())

        assertNull(store.lastPromptedAtMillis(homeworkId = 1L))
    }

    @Test
    fun `记录超时提醒后可读回同一时刻`() = runTest {
        val store = DataStoreTimerOverduePromptStore(FakeKeyValueStore())

        store.markPrompted(homeworkId = 1L, atMillis = 1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, store.lastPromptedAtMillis(homeworkId = 1L))
    }

    @Test
    fun `超时提醒记录按作业隔离`() = runTest {
        val store = DataStoreTimerOverduePromptStore(FakeKeyValueStore())

        store.markPrompted(homeworkId = 1L, atMillis = 100L)

        assertNull(store.lastPromptedAtMillis(homeworkId = 2L))
        assertEquals(100L, store.lastPromptedAtMillis(homeworkId = 1L))
    }

    @Test
    fun `重复记录覆盖为最近一次时刻`() = runTest {
        val store = DataStoreTimerOverduePromptStore(FakeKeyValueStore())

        store.markPrompted(homeworkId = 1L, atMillis = 100L)
        store.markPrompted(homeworkId = 1L, atMillis = 200L)

        assertEquals(200L, store.lastPromptedAtMillis(homeworkId = 1L))
    }

    @Test
    fun `存储键按作业 id 隔离且带统一前缀`() {
        val keyOne = DataStoreTimerOverduePromptStore.keyOf(1L)

        assertTrue(keyOne.startsWith(DataStoreTimerOverduePromptStore.KEY_PREFIX))
        assertTrue(keyOne.endsWith("1"))
        assertNotEquals(keyOne, DataStoreTimerOverduePromptStore.keyOf(2L))
    }

    // ---- 休息起点持久化（评审修复项：进程回收后休息页可幂等重建） ----

    @Test
    fun `休息起点初始为空`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())

        assertNull(store.restStartedAtMillis(studentId = 1L, homeworkId = 2L))
    }

    @Test
    fun `记录休息起点后可读回`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())

        store.markRestStarted(studentId = 1L, homeworkId = 2L, atMillis = 1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, store.restStartedAtMillis(studentId = 1L, homeworkId = 2L))
    }

    @Test
    fun `休息起点按学生与作业两个维度隔离`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())

        store.markRestStarted(studentId = 1L, homeworkId = 2L, atMillis = 100L)

        assertNull("换学生不应读到", store.restStartedAtMillis(studentId = 9L, homeworkId = 2L))
        assertNull("换作业不应读到", store.restStartedAtMillis(studentId = 1L, homeworkId = 3L))
        assertEquals(100L, store.restStartedAtMillis(studentId = 1L, homeworkId = 2L))
    }

    @Test
    fun `清除休息起点后读回为空`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())
        store.markRestStarted(studentId = 1L, homeworkId = 2L, atMillis = 100L)

        store.clear(studentId = 1L, homeworkId = 2L)

        assertNull(store.restStartedAtMillis(studentId = 1L, homeworkId = 2L))
    }

    @Test
    fun `休息起点键按学生与作业组合且带统一前缀`() {
        val key = DataStoreTimerRestStartStore.keyOf(studentId = 1L, homeworkId = 2L)

        assertTrue(key.startsWith(DataStoreTimerRestStartStore.KEY_PREFIX))
        assertTrue(key.contains("1_2"))
        assertNotEquals(key, DataStoreTimerRestStartStore.keyOf(studentId = 2L, homeworkId = 1L))
    }
}
