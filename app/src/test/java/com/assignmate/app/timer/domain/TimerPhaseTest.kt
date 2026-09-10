package com.assignmate.app.timer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计时阶段状态机单测：流转合法性（含自身幂等）、阶段语义（是否未收尾/是否终态）、
 * 可落库取值域、字符串解析与脏值兜底。
 */
class TimerPhaseTest {

    @Test
    fun `未开始只能流转到进行中`() {
        assertTrue(TimerPhase.IDLE.canTransitionTo(TimerPhase.RUNNING))
        assertFalse(TimerPhase.IDLE.canTransitionTo(TimerPhase.PAUSED))
        assertFalse(TimerPhase.IDLE.canTransitionTo(TimerPhase.FINISHED))
        assertFalse(TimerPhase.IDLE.canTransitionTo(TimerPhase.RESTING))
    }

    @Test
    fun `进行中可暂停或完成但不能回到未开始`() {
        assertTrue(TimerPhase.RUNNING.canTransitionTo(TimerPhase.PAUSED))
        assertTrue(TimerPhase.RUNNING.canTransitionTo(TimerPhase.FINISHED))
        assertFalse(TimerPhase.RUNNING.canTransitionTo(TimerPhase.IDLE))
        assertFalse(TimerPhase.RUNNING.canTransitionTo(TimerPhase.RESTING))
    }

    @Test
    fun `暂停中可恢复或直接完成`() {
        assertTrue(TimerPhase.PAUSED.canTransitionTo(TimerPhase.RUNNING))
        assertTrue(TimerPhase.PAUSED.canTransitionTo(TimerPhase.FINISHED))
        assertFalse(TimerPhase.PAUSED.canTransitionTo(TimerPhase.IDLE))
        assertFalse(TimerPhase.PAUSED.canTransitionTo(TimerPhase.RESTING))
    }

    @Test
    fun `已完成可进入休息或回到未开始`() {
        assertTrue(TimerPhase.FINISHED.canTransitionTo(TimerPhase.RESTING))
        assertTrue(TimerPhase.FINISHED.canTransitionTo(TimerPhase.IDLE))
        assertFalse(TimerPhase.FINISHED.canTransitionTo(TimerPhase.RUNNING))
        assertFalse(TimerPhase.FINISHED.canTransitionTo(TimerPhase.PAUSED))
    }

    @Test
    fun `休息中可回到未开始或直接开始下一项`() {
        assertTrue(TimerPhase.RESTING.canTransitionTo(TimerPhase.IDLE))
        assertTrue(TimerPhase.RESTING.canTransitionTo(TimerPhase.RUNNING))
        assertFalse(TimerPhase.RESTING.canTransitionTo(TimerPhase.PAUSED))
        assertFalse(TimerPhase.RESTING.canTransitionTo(TimerPhase.FINISHED))
    }

    @Test
    fun `流转到自身一律合法以便幂等更新`() {
        TimerPhase.entries.forEach { phase ->
            assertTrue("$phase 应允许流转到自身", phase.canTransitionTo(phase))
        }
    }

    @Test
    fun `任意阶段的合法流转集合都不包含自身`() {
        TimerPhase.entries.forEach { phase ->
            assertFalse(phase.allowedTransitions.contains(phase))
        }
    }

    @Test
    fun `未收尾判定仅进行中与暂停中为真`() {
        assertTrue(TimerPhase.RUNNING.isActive)
        assertTrue(TimerPhase.PAUSED.isActive)
        assertFalse(TimerPhase.IDLE.isActive)
        assertFalse(TimerPhase.FINISHED.isActive)
        assertFalse(TimerPhase.RESTING.isActive)
    }

    @Test
    fun `终态判定仅已完成为真`() {
        assertTrue(TimerPhase.FINISHED.isTerminal)
        assertFalse(TimerPhase.RUNNING.isTerminal)
        assertFalse(TimerPhase.PAUSED.isTerminal)
        assertFalse(TimerPhase.IDLE.isTerminal)
        assertFalse(TimerPhase.RESTING.isTerminal)
    }

    @Test
    fun `每个阶段都有中文可读标签`() {
        assertEquals("未开始", TimerPhase.IDLE.label)
        assertEquals("进行中", TimerPhase.RUNNING.label)
        assertEquals("已暂停", TimerPhase.PAUSED.label)
        assertEquals("已完成", TimerPhase.FINISHED.label)
        assertEquals("休息中", TimerPhase.RESTING.label)
    }

    @Test
    fun `可落库状态为进行中暂停中与已完成`() {
        assertEquals(
            setOf(TimerPhase.RUNNING, TimerPhase.PAUSED, TimerPhase.FINISHED),
            TimerPhase.PERSISTED,
        )
    }

    @Test
    fun `字符串解析对未知与空值返回 null`() {
        assertNull(TimerPhase.fromName(null))
        assertNull(TimerPhase.fromName(""))
        assertNull(TimerPhase.fromName("WORKING"))
        assertEquals(TimerPhase.RUNNING, TimerPhase.fromName("RUNNING"))
        assertEquals(TimerPhase.IDLE, TimerPhase.fromName("IDLE"))
    }

    @Test
    fun `会话状态脏值统一按已完成兜底`() {
        assertEquals(TimerPhase.FINISHED, TimerPhase.fromSessionStatus(null))
        assertEquals(TimerPhase.FINISHED, TimerPhase.fromSessionStatus("BROKEN"))
        assertEquals(TimerPhase.PAUSED, TimerPhase.fromSessionStatus("PAUSED"))
    }

    @Test
    fun `初始阶段为未开始`() {
        assertEquals(TimerPhase.IDLE, TimerPhase.INITIAL)
    }

    @Test
    fun `可落库阶段提供与枚举名一致的落库名`() {
        assertEquals("RUNNING", TimerPhase.RUNNING.persistedName)
        assertEquals("PAUSED", TimerPhase.PAUSED.persistedName)
        assertEquals("FINISHED", TimerPhase.FINISHED.persistedName)
    }

    @Test
    fun `页面级阶段不可落库写入即抛异常`() {
        listOf(TimerPhase.IDLE, TimerPhase.RESTING).forEach { phase ->
            val error = assertThrows(IllegalArgumentException::class.java) { phase.persistedName }

            assertTrue(
                "异常信息应指明是哪个阶段：${error.message}",
                error.message.orEmpty().contains(phase.name),
            )
        }
    }

    @Test
    fun `落库名取值域与可落库集合完全一致`() {
        val persistable = TimerPhase.entries.filter {
            runCatching { it.persistedName }.isSuccess
        }

        assertEquals(TimerPhase.PERSISTED, persistable.toSet())
    }
}
