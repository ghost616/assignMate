package com.assignmate.app.stats.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HistoryQuery] 单测：单日/范围语义、非法范围判定与最大跨度收敛。
 */
class HistoryQueryTest {

    @Test
    fun `缺省结束日等于开始日即查单日`() {
        val query = HistoryQuery(startEpochDay = 100L)
        assertEquals(100L, query.endEpochDay)
        assertEquals(1L, query.spanDays)
        assertTrue(query.isValid)
    }

    @Test
    fun `结束日早于开始日为非法入参`() {
        val query = HistoryQuery(startEpochDay = 100L, endEpochDay = 99L)
        assertFalse(query.isValid)
        assertEquals(0L, query.spanDays)
    }

    @Test
    fun `跨度按含首尾计算`() {
        assertEquals(3L, HistoryQuery(100L, 102L).spanDays)
    }

    @Test
    fun `跨度超过上限时判定过长`() {
        val query = HistoryQuery(0L, StatsConstants.MAX_HISTORY_DAYS)
        assertTrue(query.isTooLong)
        assertEquals(StatsConstants.MAX_HISTORY_DAYS + 1L, query.spanDays)
    }

    @Test
    fun `收敛后结束日截断到上限且仍为合法入参`() {
        val normalized = HistoryQuery(0L, 1_000L).normalized()
        assertEquals(StatsConstants.MAX_HISTORY_DAYS - 1L, normalized.endEpochDay)
        assertFalse(normalized.isTooLong)
        assertTrue(normalized.isValid)
    }

    @Test
    fun `未超上限的查询收敛后保持不变`() {
        val query = HistoryQuery(10L, 20L)
        assertEquals(query, query.normalized())
    }

    @Test
    fun `非法查询收敛后原样返回`() {
        val invalid = HistoryQuery(10L, 5L)
        assertEquals(invalid, invalid.normalized())
    }

    @Test
    fun `单日工厂构造单日查询`() {
        assertEquals(HistoryQuery(42L, 42L), HistoryQuery.ofDay(42L))
    }
}