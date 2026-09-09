package com.assignmate.app.core.domain.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时间/日期格式化工具（纯函数、线程安全）。
 *
 * 入参统一为 epoch 毫秒或秒数；展示时区默认取系统时区，测试可显式传入 [ZoneId]。
 * 业务层（作业截止时间、统计页、计时器）统一复用，避免各处重复实现导致格式漂移。
 */
object TimeFormatters {

    /** 输出 "HH:mm"（如 08:05） */
    fun formatClockTime(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(CLOCK_TIME)

    /** 输出 "yyyy-MM-dd"（如 2025-01-02） */
    fun formatDate(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(DATE_ONLY)

    /** 输出 "yyyy-MM-dd HH:mm"（如 2025-01-02 08:05） */
    fun formatDateTime(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zoneId).format(DATE_TIME)

    /**
     * 时长展示：不足 1 小时输出 "mm:ss"（如 01:05），达到 1 小时输出 "h:mm:ss"（如 1:02:03）。
     * 用于专注计时/休息倒计时等场景。
     */
    fun formatDuration(totalSeconds: Long): String {
        require(totalSeconds >= 0) { "时长不能为负数: $totalSeconds" }
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    private val CLOCK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
