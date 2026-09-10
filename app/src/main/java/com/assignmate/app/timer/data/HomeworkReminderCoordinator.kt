package com.assignmate.app.timer.data

import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.timer.domain.TimerReminderRules
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 作业 ↔ 到点提醒闹钟的同步封装（timer 模块对外提供的提醒维护入口）。
 *
 * 使用场景（重排时间或删除作业后保持闹钟一致）：
 * - 进入计时相关页面时调用 [syncStudentReminders]，按当前清单纠正该学生的全部提醒
 *   （应设的设、不该有的取消），可在不改动 homework 模块的前提下保证闹钟与数据一致；
 * - 单条作业状态变化（完成、撤销排定等）后调用 [syncHomeworkReminder]；
 * - 删除作业后调用 [cancelHomeworkReminder]（作业已不存在，无法再读取）。
 *
 * 规则集中在 [TimerReminderRules]（纯函数）：仅「待完成且触发时刻未过期」的作业会设提醒。
 */
@Singleton
class HomeworkReminderCoordinator @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val alarmScheduler: HomeworkAlarmScheduler,
    private val clock: Clock,
) {

    /**
     * 按某学生的当前清单纠正全部到点提醒：
     * 应对每条「待完成且触发时刻未过期」的作业设置提醒，其余一律取消（幂等，可重复调用）。
     */
    suspend fun syncStudentReminders(studentId: Long) {
        val now = clock.currentTimeMillis()
        homeworkRepository.listHomework(studentId).forEach { item ->
            val trigger = TimerReminderRules.triggerAtMillis(item)
            if (trigger != null && TimerReminderRules.canSchedule(item, now)) {
                alarmScheduler.schedule(item.id, item.content, trigger)
            } else {
                alarmScheduler.cancel(item.id)
            }
        }
    }

    /**
     * 同步单条作业的提醒：作业不存在或不再满足提醒条件时取消既有闹钟并返回
     * [AlarmScheduleResult.NotScheduled]；否则按规则设置并返回实际采用的方式。
     */
    suspend fun syncHomeworkReminder(homeworkId: Long): AlarmScheduleResult {
        val item = homeworkRepository.getHomework(homeworkId)
        if (item == null) {
            alarmScheduler.cancel(homeworkId)
            return AlarmScheduleResult.NotScheduled
        }
        val trigger = TimerReminderRules.triggerAtMillis(item)
        if (trigger == null || !TimerReminderRules.canSchedule(item, clock.currentTimeMillis())) {
            alarmScheduler.cancel(homeworkId)
            return AlarmScheduleResult.NotScheduled
        }
        return alarmScheduler.schedule(homeworkId, item.content, trigger)
    }

    /** 取消单条作业的提醒（作业删除后调用） */
    fun cancelHomeworkReminder(homeworkId: Long) {
        alarmScheduler.cancel(homeworkId)
    }
}
