package com.assignmate.app.timer.data

/**
 * 到点提醒的闹钟调度契约（基于系统 AlarmManager；UI/协调器只依赖本接口，测试可注入替身）。
 *
 * 语义：
 * - [schedule]：为一条作业设置**单次**到点提醒（同一作业重复调用会覆盖旧闹钟，不产生重复提醒）；
 * - [scheduleDaily] / [cancelDaily]：为**阶段作业的某一天**设置/取消到点提醒
 *   （阶段作业「1 条 + 每天详情」后，逐日提醒才是「每天到点」的正确表达）；
 * - [cancel]：取消作业的**单次**到点提醒（幂等，未设置时无副作用）；
 * - [canScheduleExactAlarms]：当前是否可设置**精确**闹钟（Android 12+ 需用户在系统设置授权
 *   「闹钟和提醒」权限）；未授权时不崩溃，由 [schedule] 降级为不精确闹钟并经返回值告知调用方。
 *
 * 为什么按天方法是**抽象**的（修复轮 #8「消除静默退化」）：它们此前带默认实现并退化为单次提醒——
 * 任何实现（含各模块测试替身）忘记覆写时，阶段作业的每日提醒会**静默变成只提醒一次**，
 * 既不报错也不易发现。现改为强制实现：漏实现即**编译期失败**，把「少提醒」这类用户可感知缺陷
 * 挡在编译阶段；若某实现确实只支持单次提醒，必须显式写出该退化语义并在其 KDoc 说明理由。
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

    /**
     * 为阶段作业的**某一天**设置到点提醒（请求码由「作业 + 自然日」派生，同一天重设即覆盖）。
     *
     * **必须实现**（无默认实现）：默认退化为单次提醒会让阶段作业的每日提醒静默少提醒，
     * 见接口 KDoc 的「为什么按天方法是抽象的」。
     */
    fun scheduleDaily(
        homeworkId: Long,
        epochDay: Long,
        content: String,
        triggerAtMillis: Long,
    ): AlarmScheduleResult

    /**
     * 取消阶段作业某一天的到点提醒（幂等）：只影响这一天，不影响该作业的其它天与单次提醒。
     *
     * **必须实现**（无默认实现）：理由同 [scheduleDaily]。
     */
    fun cancelDaily(homeworkId: Long, epochDay: Long)
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
