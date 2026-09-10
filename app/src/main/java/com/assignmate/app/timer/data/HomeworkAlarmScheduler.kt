package com.assignmate.app.timer.data

/**
 * 到点提醒的闹钟调度契约（基于系统 AlarmManager；UI/协调器只依赖本接口，测试可注入替身）。
 *
 * 语义：
 * - [schedule]：为一条作业设置到点提醒（同一作业重复调用会覆盖旧闹钟，不产生重复提醒）；
 * - [cancel]：取消作业的到点提醒（幂等，未设置时无副作用）；
 * - [canScheduleExactAlarms]：当前是否可设置**精确**闹钟（Android 12+ 需用户在系统设置授权
 *   「闹钟和提醒」权限）；未授权时不崩溃，由 [schedule] 降级为不精确闹钟并经返回值告知调用方。
 */
interface HomeworkAlarmScheduler {

    /** 是否可设置精确闹钟（Android 12 以下恒为 true） */
    fun canScheduleExactAlarms(): Boolean

    /**
     * 为作业设置到点提醒。
     *
     * @param homeworkId 作业 id（决定请求码，同一作业覆盖旧闹钟）
     * @param content 作业内容（写入通知文案）
     * @param triggerAtMillis 触发时刻（epoch 毫秒，见 TimerReminderRules.triggerAtMillis）
     */
    fun schedule(homeworkId: Long, content: String, triggerAtMillis: Long): AlarmScheduleResult

    /** 取消作业的到点提醒（幂等） */
    fun cancel(homeworkId: Long)
}

/** 闹钟调度结果（机器可读，UI 据此提示是否需要引导用户授权精确闹钟） */
sealed class AlarmScheduleResult {

    /** 已设置精确闹钟：由系统在触发时刻准点提醒 */
    data object ScheduledExact : AlarmScheduleResult()

    /**
     * 已设置非精确闹钟（降级）：系统会在触发时刻附近（可能延迟数分钟）提醒。
     * 出现该结果意味着用户未授予「闹钟和提醒」权限，UI 可提示手动开启以获得准点提醒。
     */
    data object ScheduledInexact : AlarmScheduleResult()

    /** 未设置（触发时刻已过期、作业无需提醒或系统闹钟服务不可用）；此时既有闹钟已被取消 */
    data object NotScheduled : AlarmScheduleResult()
}
