package com.assignmate.app.core.domain.util

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * TimeFormatters 纯逻辑单测：固定时刻 + UTC 时区，保证确定性。
 */
class TimeFormattersTest {

    private val utc: ZoneOffset = ZoneOffset.UTC

    private fun epochMillis(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `formatClockTime 输出 HH_mm 格式`() {
        assertEquals("08:05", TimeFormatters.formatClockTime(epochMillis(2025, 1, 2, 8, 5), utc))
        assertEquals("23:59", TimeFormatters.formatClockTime(epochMillis(2025, 6, 30, 23, 59), utc))
    }

    @Test
    fun `formatDate 输出 yyyy-MM-dd 格式`() {
        assertEquals("2025-01-02", TimeFormatters.formatDate(epochMillis(2025, 1, 2), utc))
    }

    @Test
    fun `formatDateTime 输出 yyyy-MM-dd HH_mm 格式`() {
        assertEquals(
            "2025-01-02 08:05",
            TimeFormatters.formatDateTime(epochMillis(2025, 1, 2, 8, 5), utc),
        )
    }

    @Test
    fun `formatDuration 分钟级输出 mm_ss`() {
        assertEquals("00:00", TimeFormatters.formatDuration(0))
        assertEquals("01:05", TimeFormatters.formatDuration(65))
        assertEquals("59:59", TimeFormatters.formatDuration(3599))
    }

    @Test
    fun `formatDuration 小时级输出 h_mm_ss`() {
        assertEquals("1:00:00", TimeFormatters.formatDuration(3600))
        assertEquals("2:03:04", TimeFormatters.formatDuration(2 * 3600 + 3 * 60 + 4))
    }

    @Test
    fun `formatDuration 拒绝负数输入`() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeFormatters.formatDuration(-1)
        }
    }
}
