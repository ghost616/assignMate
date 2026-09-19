package com.assignmate.app.timer.data

import com.assignmate.app.core.domain.homework.HomeworkDayStatus
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkRepository
import com.assignmate.app.homework.domain.HomeworkItem
import com.assignmate.app.homework.domain.HomeworkStatus
import com.assignmate.app.homework.domain.HomeworkValidators
import com.assignmate.app.timer.domain.TimerReminderRules
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 作业 ↔ 到点提醒闹钟的同步封装（timer 模块对外提供的提醒维护入口）。
 *
 * 使用场景（重排时间/删除作业/完成打卡后保持闹钟一致）：
 * - 进入计时相关页面时调用 [syncStudentReminders]，按当前清单纠正该学生的全部提醒
 *   （应设的设、不该有的取消），可在不改动 homework 模块的前提下保证闹钟与数据一致；
 * - 单条作业状态变化（完成、撤销完成、改类型、改阶段范围等）后调用 [syncHomeworkReminder]；
 * - 删除作业后调用 [cancelHomeworkReminder]（作业已不存在，无法再读取）。
 *
 * 两类提醒语义（规则集中在 [TimerReminderRules]，纯函数可单测）：
 * - **当天作业（TODAY）**：沿用既有「绝对日期 + 时刻」单次提醒——仅「待完成」且触发时刻未过期太久才设闹钟；
 * - **阶段作业（STAGE）**：「1 条作业项 + 每天详情」后按**每日截止时刻**在阶段范围内的每一天各设一个闹钟
 *   （[syncStageDailyReminders]）。阶段未整体结束即按天提醒；**当天详情已完成**的那一天不再提醒；
 *   阶段范围变更（改范围/改每日时刻）时超出新范围的历史闹钟会被取消，删除作业时逐日闹钟全部取消。
 *
 * 实现细节：
 * - [alarmScheduler] 只做「下发/取消」（同一目标稳定请求码，重设即覆盖）；
 * - 逐日闹钟的「已设集合」落在 [scheduleStore]（持久化），使**取消**在作业数据已不可读（删除）时
 *   仍能精确枚举要取消哪几天，从而不遗留无效闹钟；
 * - 时钟与业务时区均可注入，测试可确定性构造「今天/阶段范围/每日截止时刻」。
 *
 * 兼容构造：另有 4 参构造（不带逐日登记表，[scheduleStore] 视为未接线），仅供不接线逐日提醒的
 * 既有调用方（其它模块的测试替身装配）使用；该形态下逐日闹钟不产生也不取消，TODAY 语义完全不变。
 *
 * 业务时区（[zoneId]）**必须由调用方显式给出**（兼容构造亦然）：其唯一来源是 core 的
 * [com.assignmate.app.core.di.DailyRecordModule.provideBusinessZoneId]（无限定 [ZoneId] 绑定）；
 * **业务模块（含 timer）不得自建业务时区绑定**，timer 内也不得再以 `ZoneId.systemDefault()` 兜底——
 * 重复绑定会是 Hilt 的 DuplicateBindings 编译错误，兜底还会让跨零点时「今天」口径与 homework / core 漂移。
 */
@Singleton
class HomeworkReminderCoordinator @Inject constructor(
    private val homeworkRepository: HomeworkRepository,
    private val alarmScheduler: HomeworkAlarmScheduler,
    private val clock: Clock,
    /** 业务时区：来源为 core 的唯一业务时区绑定，用于「今天」与「某天到点时刻」的折算 */
    private val zoneId: ZoneId,
    /** 逐日提醒的已设登记表；为 null 表示不接线逐日提醒（见类注释的兼容构造） */
    private val scheduleStore: TimerReminderScheduleStore?,
) {

    /**
     * 兼容构造：只带 TODAY 单次提醒所需依赖（逐日登记表未接线）。
     *
     * [zoneId] 仍须调用方显式给出（不提供系统时区默认值）：生产装配注入 core 的唯一绑定，
     * 测试用具传固定时区（如 `TimerTestEnv.ZONE`）以保证确定性。
     */
    constructor(
        homeworkRepository: HomeworkRepository,
        alarmScheduler: HomeworkAlarmScheduler,
        clock: Clock,
        zoneId: ZoneId,
    ) : this(
        homeworkRepository = homeworkRepository,
        alarmScheduler = alarmScheduler,
        clock = clock,
        zoneId = zoneId,
        scheduleStore = null,
    )

    /**
     * 按某学生的当前清单纠正全部到点提醒（幂等，可重复调用）：
     * 当天作业按绝对时刻设置/取消；阶段作业按每日截止时刻逐日设置/取消。
     */
    suspend fun syncStudentReminders(studentId: Long) {
        val now = clock.currentTimeMillis()
        homeworkRepository.listHomework(studentId).forEach { item -> sync(item, now) }
    }

    /**
     * 同步单条作业的提醒：作业不存在或不再满足提醒条件时取消既有闹钟并返回
     * [AlarmScheduleResult.NotScheduled]；否则按规则设置并返回实际采用的方式。
     *
     * 阶段作业返回**逐日闹钟的汇总结果**（任一天降级即报降级，全部失败才报未设置）。
     */
    suspend fun syncHomeworkReminder(homeworkId: Long): AlarmScheduleResult {
        val item = homeworkRepository.getHomework(homeworkId)
        if (item == null) {
            cancelHomeworkReminder(homeworkId)
            return AlarmScheduleResult.NotScheduled
        }
        return sync(item, clock.currentTimeMillis())
    }

    /**
     * 取消某作业的全部提醒：先取消登记在册的逐日闹钟（阶段作业），再取消单次闹钟，最后清空登记。
     *
     * 作业已删除时只剩 homeworkId，登记表让我们仍能精确枚举要取消的自然日；
     * 对「类型由 STAGE 改回 TODAY」的作业，同样能清掉历史逐日闹钟而不留残留。
     */
    suspend fun cancelHomeworkReminder(homeworkId: Long) {
        registeredDays(homeworkId).forEach { epochDay -> alarmScheduler.cancelDaily(homeworkId, epochDay) }
        rememberDays(homeworkId, emptySet())
        alarmScheduler.cancel(homeworkId)
    }

    // ---- 内部分派 ----

    private suspend fun sync(item: HomeworkItem, nowMillis: Long): AlarmScheduleResult =
        if (item.isStage) {
            syncStageDailyReminders(item, nowMillis)
        } else {
            syncSingleReminder(item, nowMillis)
        }

    /**
     * 当天作业（TODAY）单次提醒：语义与既有版本完全一致（绝对日期 + 时刻、仅待完成、过期宽限），
     * 并清掉可能残留的逐日闹钟（作业类型由阶段改为当天时）。
     */
    private suspend fun syncSingleReminder(item: HomeworkItem, nowMillis: Long): AlarmScheduleResult {
        cancelStaleDays(item.id, desiredDays = emptySet())
        val trigger = TimerReminderRules.triggerAtMillis(item)
        if (trigger == null || !TimerReminderRules.canSchedule(item, nowMillis)) {
            alarmScheduler.cancel(item.id)
            return AlarmScheduleResult.NotScheduled
        }
        return alarmScheduler.schedule(item.id, item.content, trigger)
    }

    /**
     * 阶段作业逐日提醒：按每日截止时刻在阶段范围内逐日设置，并取消超出新范围/当天已完成的旧闹钟。
     *
     * 同时取消该作业的单次闹钟（作业类型由当天改为阶段时可能残留），幂等无副作用。
     */
    private suspend fun syncStageDailyReminders(item: HomeworkItem, nowMillis: Long): AlarmScheduleResult {
        val triggers = desiredStageTriggers(item, nowMillis)
        val desiredDays = triggers.map { it.epochDay }.toSet()
        cancelStaleDays(item.id, desiredDays)
        alarmScheduler.cancel(item.id)
        rememberDays(item.id, desiredDays)
        if (triggers.isEmpty()) {
            return AlarmScheduleResult.NotScheduled
        }
        val results = triggers.map { trigger ->
            alarmScheduler.scheduleDaily(item.id, trigger.epochDay, item.content, trigger.triggerAtMillis)
        }
        return results.firstOrNull { it == AlarmScheduleResult.ScheduledInexact }
            ?: results.firstOrNull { it == AlarmScheduleResult.ScheduledExact }
            ?: AlarmScheduleResult.NotScheduled
    }

    /**
     * 阶段作业应设置提醒的自然日与触发时刻：
     * - 按 [TimerReminderRules.stageDailyTriggers] 给出「阶段覆盖区间 ∩ 今天起」的每一天
     *   （每日截止时刻到点提醒；阶段整体结束后区间自然为空，不再提醒）；
     * - **当天详情已完成的那一天不再提醒**（「已完成当天不再提醒」，与到点投递侧同一口径）。
     *
     * 为什么不看作业自身的状态列：homework 对阶段作业的「是否完成」唯一口径是**每天详情**
     * （见 StageDayRecords），作业状态在阶段过程中会因「当天完成」被置为已完成，
     * 若据此取消，第二天起就再也不提醒了——阶段未走完即应继续按天提醒。
     */
    private suspend fun desiredStageTriggers(
        item: HomeworkItem,
        nowMillis: Long,
    ): List<TimerReminderRules.StageDailyTrigger> {
        val completedDays = homeworkRepository.dailyRecords(item.id)
            .filter { it.status == HomeworkDayStatus.COMPLETED }
            .map { it.epochDay }
            .toSet()
        return TimerReminderRules
            .stageDailyTriggers(
                item = item,
                todayEpochDay = epochDayOf(nowMillis),
                nowMillis = nowMillis,
                zoneId = zoneId,
            )
            .filterNot { it.epochDay in completedDays }
    }

    /**
     * 取消登记在册但**不在**目标集合里的逐日闹钟，并把登记表收敛为 [desiredDays]
     * （阶段范围变更、当天已完成、类型切回 TODAY、删除作业等场景）。
     *
     * **必须同时收敛登记表**（修复轮 #5 的残留缺口）：取消闹钟只动了系统调度，若不改登记，
     * 那些天仍留在「已设集合」里——于「类型切回 TODAY」这类不再走逐日路径的场景下，
     * 登记永远清不掉（这类场景只调用本方法，不会再 `rememberDays(目标集合)`），
     * 既让登记表残留无效天，也让后续取消继续按已失效的旧天枚举。
     * 无登记表（兼容构造）时 `save` 为空操作，语义不变。
     */
    private suspend fun cancelStaleDays(homeworkId: Long, desiredDays: Set<Long>) {
        val registered = registeredDays(homeworkId)
        val staleDays = registered.filterNot { it in desiredDays }
        if (staleDays.isEmpty()) {
            return
        }
        staleDays.forEach { epochDay -> alarmScheduler.cancelDaily(homeworkId, epochDay) }
        rememberDays(homeworkId, desiredDays)
    }

    /** 业务自然日折算（口径与 homework / core 的每日详情一致，禁止 UTC 毫秒折算） */
    private fun epochDayOf(millis: Long): Long = HomeworkValidators.epochDayOf(millis, zoneId)

    /** 读取登记表（未接线逐日提醒时视为「无登记」） */
    private suspend fun registeredDays(homeworkId: Long): Set<Long> =
        scheduleStore?.load(homeworkId).orEmpty()

    /** 覆盖登记表（未接线逐日提醒时不落盘）；空集合等价于清除登记 */
    private suspend fun rememberDays(homeworkId: Long, epochDays: Set<Long>) {
        scheduleStore?.save(homeworkId, epochDays)
    }
}
