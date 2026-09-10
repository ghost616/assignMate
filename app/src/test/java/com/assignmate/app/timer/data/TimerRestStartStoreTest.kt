package com.assignmate.app.timer.data

import com.assignmate.app.auth.data.FakeKeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 休息起点持久化存储的契约单测（真实实现 + 内存 KeyValueStore）：
 * 两维度隔离（学生 + 作业）、覆盖式写入、清除后读回为空、非法存量值的安全读回、键名约定。
 *
 * 覆盖测试说明第 7 项中此前无用例的两个点：**起点按学生 + 作业隔离互不覆盖**（存储层）与
 * **清除后读回为空**（页面层只验证了「过期起点被覆盖」，未验证显式清除）。
 */
class TimerRestStartStoreTest {

    @Test
    fun `初始没有任何起点`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())

        assertNull(store.restStartedAtMillis(studentId = 1L, homeworkId = 2L))
    }

    @Test
    fun `写入起点后可读回同一时刻`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())

        store.markRestStarted(studentId = 1L, homeworkId = 2L, atMillis = BASE)

        assertEquals(BASE, store.restStartedAtMillis(studentId = 1L, homeworkId = 2L))
    }

    @Test
    fun `重复写入覆盖为最近一次起点`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())

        store.markRestStarted(studentId = 1L, homeworkId = 2L, atMillis = BASE)
        store.markRestStarted(studentId = 1L, homeworkId = 2L, atMillis = BASE + MINUTE)

        assertEquals(BASE + MINUTE, store.restStartedAtMillis(studentId = 1L, homeworkId = 2L))
    }

    @Test
    fun `学生与作业两个维度互相隔离`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())

        store.markRestStarted(studentId = 1L, homeworkId = 10L, atMillis = BASE)
        store.markRestStarted(studentId = 1L, homeworkId = 20L, atMillis = BASE + MINUTE)
        store.markRestStarted(studentId = 2L, homeworkId = 10L, atMillis = BASE + 2 * MINUTE)

        assertEquals(BASE, store.restStartedAtMillis(1L, 10L))
        assertEquals(BASE + MINUTE, store.restStartedAtMillis(1L, 20L))
        assertEquals(BASE + 2 * MINUTE, store.restStartedAtMillis(2L, 10L))
        assertNull("未写入的组合应为空", store.restStartedAtMillis(2L, 20L))
    }

    @Test
    fun `清除后读回为空且不影响其它维度`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())
        store.markRestStarted(studentId = 1L, homeworkId = 10L, atMillis = BASE)
        store.markRestStarted(studentId = 1L, homeworkId = 20L, atMillis = BASE + MINUTE)

        store.clear(studentId = 1L, homeworkId = 10L)

        assertNull("清除后应读回为空", store.restStartedAtMillis(1L, 10L))
        assertEquals("其它作业的起点不受影响", BASE + MINUTE, store.restStartedAtMillis(1L, 20L))
    }

    @Test
    fun `清除不存在或重复清除均无副作用`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())

        store.clear(studentId = 9L, homeworkId = 9L)
        store.clear(studentId = 9L, homeworkId = 9L)

        assertNull(store.restStartedAtMillis(9L, 9L))
    }

    @Test
    fun `零与负数起点可正常读写`() = runTest {
        val store = DataStoreTimerRestStartStore(FakeKeyValueStore())

        store.markRestStarted(studentId = 1L, homeworkId = 1L, atMillis = 0L)
        store.markRestStarted(studentId = 1L, homeworkId = 2L, atMillis = -1_000L)

        assertEquals(0L, store.restStartedAtMillis(1L, 1L))
        assertEquals(-1_000L, store.restStartedAtMillis(1L, 2L))
    }

    @Test
    fun `非法存量值读回为空而不崩溃`() = runTest {
        val keyValueStore = FakeKeyValueStore()
        keyValueStore.putString(DataStoreTimerRestStartStore.keyOf(1L, 2L), "not-a-number")
        val store = DataStoreTimerRestStartStore(keyValueStore)

        assertNull(store.restStartedAtMillis(1L, 2L))
    }

    @Test
    fun `存储键同时包含学生与作业维度并带统一前缀`() {
        val key = DataStoreTimerRestStartStore.keyOf(studentId = 3L, homeworkId = 7L)

        assertTrue(key.startsWith(DataStoreTimerRestStartStore.KEY_PREFIX))
        assertTrue(key.endsWith("3_7"))
        assertNotEquals(key, DataStoreTimerRestStartStore.keyOf(3L, 8L))
        assertNotEquals(key, DataStoreTimerRestStartStore.keyOf(4L, 7L))
    }

    private companion object {

        const val BASE = TimerTestEnv.FIXED_MILLIS
        const val MINUTE = 60_000L
    }
}
