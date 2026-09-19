package com.assignmate.app.core.domain.homework

/**
 * 作业每日状态（库内以枚举名 String 存储，见 core.data.db.entity.HomeworkDailyRecordEntity）。
 *
 * 取值与语义：
 * - [NOT_STARTED] 未开始：当天尚无执行；
 * - [IN_PROGRESS] 进行中：已开始计时但未结束；
 * - [COMPLETED] 已完成：当天完成；
 * - [MISSED] 未完成（缺卡）：当天已过且未完成。
 */
enum class HomeworkDayStatus {
    /** 未开始 */
    NOT_STARTED,

    /** 进行中 */
    IN_PROGRESS,

    /** 已完成 */
    COMPLETED,

    /** 未完成（缺卡） */
    MISSED;

    companion object {

        /**
         * 解析库内落盘值：null、空白与未知枚举名一律兜底 [NOT_STARTED]，
         * 避免历史脏数据击穿状态流转（不抛异常）。
         */
        fun fromRawValue(raw: String?): HomeworkDayStatus =
            entries.firstOrNull { it.name == raw } ?: NOT_STARTED
    }
}

/**
 * 「作业每天详情」领域模型（供 homework / timer / stats 消费，业务模块不感知表结构）。
 *
 * 时间口径：
 * - [epochDay] 为**业务自然日**（业务时区下的 LocalDate.toEpochDay()），
 *   禁止用「毫秒 / 86400000」这类 UTC 折算；
 * - 其余时刻字段均为 epoch 毫秒（Instant 语义），由调用方按需格式化。
 *
 * @param id 记录主键；0 表示尚未落库（新增时由数据库自增）
 * @param estimatedMinutes 当日预估时长（分钟，可空）
 * @param actualMinutes 当日实际时长（分钟，可空）
 */
data class HomeworkDailyRecord(
    val id: Long = 0L,
    val homeworkId: Long,
    val studentId: Long,
    val epochDay: Long,
    val status: HomeworkDayStatus = HomeworkDayStatus.NOT_STARTED,
    val startedAtMillis: Long? = null,
    val estimatedMinutes: Int? = null,
    val actualMinutes: Int? = null,
    val pauseCount: Int = 0,
    val pausedTotalMinutes: Int = 0,
    val finishedAtMillis: Long? = null,
    val createdAtMillis: Long,
)
