package com.assignmate.app.timer.data

import com.assignmate.app.auth.data.FakeKeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「阶段作业按天提醒」登记表的落盘与容错单测（真实实现 + 内存 KeyValueStore）。
 *
 * 登记表存在的意义：删除作业/阶段范围变更时要精确取消已设的逐日闹钟，
 * 而作业数据此时可能已不可读，故必须能从登记表枚举出「设过哪几天」。
 */
class TimerReminderScheduleStoreTest {

    @Test
    fun `写入后可读回同一集合且按升序去重落盘`() = runTest {
        val keyValueStore = FakeKeyValueStore()
        val store = DataStoreTimerReminderScheduleStore(keyValueStore)

        store.save(7L, setOf(100L, 98L, 99L, 98L))

        assertEquals(setOf(98L, 99L, 100L), store.load(7L))
        assertEquals(
            "落盘文本稳定（升序、逗号分隔）",
            "98,99,100",
            keyValueStore.getString("${DataStoreTimerReminderScheduleStore.KEY_PREFIX}7"),
        )
    }

    @Test
    fun `不同作业的登记互不影响`() = runTest {
        val store = DataStoreTimerReminderScheduleStore(FakeKeyValueStore())

        store.save(1L, setOf(10L))
        store.save(2L, setOf(20L, 21L))

        assertEquals(setOf(10L), store.load(1L))
        assertEquals(setOf(20L, 21L), store.load(2L))
        assertTrue("未登记过的作业返回空集", store.load(3L).isEmpty())
    }

    @Test
    fun `空集合等价于清除登记`() = runTest {
        val keyValueStore = FakeKeyValueStore()
        val store = DataStoreTimerReminderScheduleStore(keyValueStore)
        store.save(5L, setOf(1L, 2L))

        store.save(5L, emptySet())

        assertTrue(store.load(5L).isEmpty())
        assertEquals(
            "键被移除而不是留一个空串",
            null,
            keyValueStore.getString("${DataStoreTimerReminderScheduleStore.KEY_PREFIX}5"),
        )
    }

    @Test
    fun `显式清除登记`() = runTest {
        val store = DataStoreTimerReminderScheduleStore(FakeKeyValueStore())
        store.save(6L, setOf(3L))

        store.clear(6L)

        assertTrue(store.load(6L).isEmpty())
    }

    @Test
    fun `脏值与空白片段被忽略不抛异常`() = runTest {
        val keyValueStore = FakeKeyValueStore()
        val store = DataStoreTimerReminderScheduleStore(keyValueStore)
        keyValueStore.putString(
            "${DataStoreTimerReminderScheduleStore.KEY_PREFIX}8",
            " , 100 ,abc,,101,  ",
        )

        assertEquals(setOf(100L, 101L), store.load(8L))
    }
}
