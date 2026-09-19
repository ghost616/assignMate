package com.assignmate.app.timer.data

/**
 * 闹钟调度替身：记录每次「设置/取消」（单次与按天）的调用参数与精确闹钟可用性，
 * 用于断言「触发时刻与作业绑定」「重设同一作业」「取消命中」「阶段作业逐日调度」等调度语义。
 *
 * 声明为 `open`：其它模块（如 navigation 的提醒同步接线）可在本替身之上派生
 * 「先记录再抛异常」的调度器，验证接线层失败静默降级，而无需重复实现记录逻辑。
 */
open class FakeAlarmScheduler(
    /** 精确闹钟是否可用（用例可改为 false 覆盖降级路径） */
    var exactAlarmsAvailable: Boolean = true,
) : HomeworkAlarmScheduler {

    /** 一次设置调用（作业 id + 内容 + 触发时刻） */
    data class ScheduledAlarm(
        val homeworkId: Long,
        val content: String,
        val triggerAtMillis: Long,
    )

    /** 一次**按天**设置调用（作业 id + 业务自然日 + 内容 + 触发时刻） */
    data class ScheduledDailyAlarm(
        val homeworkId: Long,
        val epochDay: Long,
        val content: String,
        val triggerAtMillis: Long,
    )

    /** 设置调用记录（按调用顺序） */
    val scheduled = mutableListOf<ScheduledAlarm>()

    /** 按天设置调用记录（按调用顺序，供阶段作业逐日提醒断言） */
    val scheduledDaily = mutableListOf<ScheduledDailyAlarm>()

    /** 取消调用记录（按调用顺序，可能重复） */
    val cancelled = mutableListOf<Long>()

    /** 按天取消调用记录（作业 id to 业务自然日，按调用顺序） */
    val cancelledDaily = mutableListOf<Pair<Long, Long>>()

    override fun canScheduleExactAlarms(): Boolean = exactAlarmsAvailable

    override fun schedule(
        homeworkId: Long,
        content: String,
        triggerAtMillis: Long,
    ): AlarmScheduleResult {
        scheduled += ScheduledAlarm(homeworkId, content, triggerAtMillis)
        return if (exactAlarmsAvailable) {
            AlarmScheduleResult.ScheduledExact
        } else {
            AlarmScheduleResult.ScheduledInexact
        }
    }

    override fun cancel(homeworkId: Long) {
        cancelled += homeworkId
    }

    override fun scheduleDaily(
        homeworkId: Long,
        epochDay: Long,
        content: String,
        triggerAtMillis: Long,
    ): AlarmScheduleResult {
        scheduledDaily += ScheduledDailyAlarm(homeworkId, epochDay, content, triggerAtMillis)
        return if (exactAlarmsAvailable) {
            AlarmScheduleResult.ScheduledExact
        } else {
            AlarmScheduleResult.ScheduledInexact
        }
    }

    override fun cancelDaily(homeworkId: Long, epochDay: Long) {
        cancelledDaily += homeworkId to epochDay
    }

    /** 最近一次设置的闹钟（未设置过返回 null） */
    fun lastScheduled(): ScheduledAlarm? = scheduled.lastOrNull()

    /** 某作业最近一次设置的闹钟（未设置过返回 null） */
    fun lastScheduledFor(homeworkId: Long): ScheduledAlarm? =
        scheduled.lastOrNull { it.homeworkId == homeworkId }

    /** 某作业已按天设置的自然日（按调用顺序去重） */
    fun scheduledDaysOf(homeworkId: Long): List<Long> =
        scheduledDaily.filter { it.homeworkId == homeworkId }.map { it.epochDay }.distinct()

    /** 某作业已按天取消的自然日（按调用顺序去重） */
    fun cancelledDaysOf(homeworkId: Long): List<Long> =
        cancelledDaily.filter { it.first == homeworkId }.map { it.second }.distinct()
}
