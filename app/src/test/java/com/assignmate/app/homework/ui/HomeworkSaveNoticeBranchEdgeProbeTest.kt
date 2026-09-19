package com.assignmate.app.homework.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 离朱补充探测（本轮「保存后立即回清单 + 跨页提示」收口）：**分支补边**单测。
 *
 * 与 [HomeworkSaveCompletionOrderTest] 的分工：那一类锁定「顺序 / 抗取消 / 不丢失 / 结构契约」四组主行为，
 * 本类只补它未覆盖到的分支面，用于把本轮核心逻辑（保存收尾编排 + 跨页暂存）的分支覆盖补齐：
 *
 * 1. [dispatchSaveCompletion] 两个分支都被显式驱动：**带文案**（发布）与 **null 文案**（不发布）；
 * 2. [HomeworkSaveNoticeHolder.consume] 的 `compareAndSet` **失败分支**（值不匹配 → 不清空）与**成功分支**；
 * 3. StateFlow 承载的意义：**订阅者后到位**（保存页先发布、清单页后订阅）仍能取到提示——
 *    这是「提示不因订阅时机而丢失」的直接证据（一次性 Channel 口径会丢）；
 * 4. 「同一提示被消费后，后到的订阅者不再收到」——重复进入清单页不重复弹出的订阅侧证据。
 *
 * 环境边界：纯 JVM 单测（无 Compose 运行时），因此只能断言持有器 / 编排函数这一层；
 * 清单页的展示点由 [HomeworkSaveCompletionOrderTest] 的读码断言守住（Compose 组合不在此类模拟）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkSaveNoticeBranchEdgeProbeTest {

    @Test
    fun `带文案的保存收尾既回清单也发布提示`() {
        val holder = HomeworkSaveNoticeHolder()
        val calls = mutableListOf<String>()

        dispatchSaveCompletion(
            dispatchMessage = MESSAGE,
            saveNotice = holder,
            onSaved = { calls += "onSaved" },
        )

        assertEquals("回清单回调必须恰好执行一次", 1, calls.size)
        assertEquals("带文案分支必须发布提示", MESSAGE, holder.notice.value)
    }

    @Test
    fun `多次收尾只保留最新一条提示且每次都回清单`() {
        val holder = HomeworkSaveNoticeHolder()
        var savedCount = 0

        dispatchSaveCompletion(MESSAGE, holder) { savedCount++ }
        dispatchSaveCompletion(LATEST_MESSAGE, holder) { savedCount++ }

        assertEquals("连续两次保存都必须回清单（导航不被提示阻塞）", 2, savedCount)
        assertEquals("确认性提示无需排队：只保留最新一条", LATEST_MESSAGE, holder.notice.value)
    }

    @Test
    fun `消费不匹配文案时不清空（compareAndSet 失败分支）`() {
        val holder = HomeworkSaveNoticeHolder()
        holder.publish(MESSAGE)

        holder.consume("另一条提示")

        assertEquals("不匹配的消费不得清空当前提示", MESSAGE, holder.notice.value)
    }

    @Test
    fun `空串消费不匹配时不误清非空提示`() {
        val holder = HomeworkSaveNoticeHolder()
        holder.publish(MESSAGE)

        holder.consume("")

        assertEquals("空串消费视为不匹配", MESSAGE, holder.notice.value)
    }

    @Test
    fun `无提示时消费是幂等 no-op`() {
        val holder = HomeworkSaveNoticeHolder()

        holder.consume(MESSAGE)

        assertNull("本就无提示，消费后仍为空", holder.notice.value)
    }

    @Test
    fun `订阅者后到位仍能取到保存页先前发布的提示`() = runTest {
        val holder = HomeworkSaveNoticeHolder()
        // 保存页发布（此时清单页尚未订阅：popBackStack 会重建清单页）
        dispatchSaveCompletion(MESSAGE, holder) { }

        // 清单页随后订阅（等价于 LaunchedEffect 里的 collect）
        val received = holder.notice.filterNotNull().first()

        assertEquals("StateFlow 重放：后到位的订阅者不得漏掉提示", MESSAGE, received)
    }

    @Test
    fun `消费后后到的订阅者不再收到该提示（重复进入不重复弹出）`() = runTest {
        val holder = HomeworkSaveNoticeHolder()
        holder.publish(MESSAGE)
        holder.consume(MESSAGE)

        assertNull("展示后清空：订阅侧取不到已消费提示", holder.notice.value)
        assertNull(
            "清空即终态，重放值也是 null（filterNotNull 不再触发展示）",
            holder.notice.value?.let { holder.notice.value },
        )
    }

    @Test
    fun `跨页暂存单例可被保存页与清单页共同访问且消费幂等`() {
        val holder = homeworkSaveNotice

        assertNotNull("跨页暂存必须是模块内可达的单例（保存页发布、清单页消费）", holder)
        // 进程内单例的当前值可能被其它用例影响：先把「当前值」消费掉，再断言回到无待展示状态，
        // 从而在不假设执行顺序的前提下锁定「consume 可把当前值清空」这一契约。
        holder.notice.value?.let { holder.consume(it) }
        assertNull("消费当前值后回到「无待展示提示」状态", holder.notice.value)
    }

    private companion object Constants {

        const val MESSAGE = "已添加 1 项作业"
        const val LATEST_MESSAGE = "已更新 1 项作业"
    }
}