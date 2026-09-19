package com.assignmate.app.timer.di

import com.assignmate.app.timer.data.AndroidHomeworkAlarmScheduler
import com.assignmate.app.timer.data.AndroidTimerPermissionChecker
import com.assignmate.app.timer.data.AndroidTimerTickerController
import com.assignmate.app.timer.data.DataStoreTimerOverduePromptStore
import com.assignmate.app.timer.data.DataStoreTimerReminderScheduleStore
import com.assignmate.app.timer.data.DataStoreTimerRestStartStore
import com.assignmate.app.timer.data.DataStoreTimerVoiceSettings
import com.assignmate.app.timer.data.HomeworkAlarmScheduler
import com.assignmate.app.timer.data.RoomTimerTransactionRunner
import com.assignmate.app.timer.data.TimerOverduePromptStore
import com.assignmate.app.timer.data.TimerPermissionChecker
import com.assignmate.app.timer.data.TimerReminderScheduleStore
import com.assignmate.app.timer.data.TimerRepository
import com.assignmate.app.timer.data.TimerRepositoryImpl
import com.assignmate.app.timer.data.TimerRestStartStore
import com.assignmate.app.timer.data.TimerTickerController
import com.assignmate.app.timer.data.TimerTransactionRunner
import com.assignmate.app.timer.data.TimerVoiceGuide
import com.assignmate.app.timer.data.TimerVoiceSettings
import com.assignmate.app.timer.data.TtsTimerVoiceGuide
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * timer 模块 Hilt 绑定模块。
 *
 * - [TimerRepository] -> 默认实现 [TimerRepositoryImpl]
 *   （基于 core 的 TimerSessionDao/PauseRecordDao + homework 状态流转入口 + 可注入 Clock + auth 会话）；
 * - [TimerTickerController] -> Android 实现 [AndroidTimerTickerController]
 *   （走秒前台服务的启停门面，UI 只依赖接口，测试可注入空实现替身）；
 * - [HomeworkAlarmScheduler] -> [AndroidHomeworkAlarmScheduler]（到点提醒闹钟，含精确闹钟不可用时的降级）；
 * - [TimerVoiceGuide] -> [TtsTimerVoiceGuide]（语音播报门面，包装 core 的 TextToSpeechPlayer 并静默降级）；
 * - [TimerVoiceSettings] -> [DataStoreTimerVoiceSettings]（播报开关，经 core KeyValueStore 落盘）；
 * - [TimerOverduePromptStore] -> [DataStoreTimerOverduePromptStore]（超时鼓励去重记录）；
 * - [TimerPermissionChecker] -> [AndroidTimerPermissionChecker]（通知/精确闹钟权限状态，供 UI 引导）；
 * - [TimerTransactionRunner] -> [RoomTimerTransactionRunner]（写操作事务边界，复用 core 的 AppDatabase）；
 * - [TimerRestStartStore] -> [DataStoreTimerRestStartStore]（休息起点持久化，支撑休息页幂等重建）；
 * - [TimerReminderScheduleStore] -> [DataStoreTimerReminderScheduleStore]（阶段作业逐日闹钟的已设登记表，
 *   使删除作业/范围变更时能精确取消、不遗留无效闹钟）。
 *
 * 说明：业务时区 [java.time.ZoneId] 的**全应用唯一来源**是 core 的
 * [com.assignmate.app.core.di.DailyRecordModule.provideBusinessZoneId]（无限定 [java.time.ZoneId] 绑定，
 * 默认系统时区），本模块直接注入复用。
 * **业务模块（含 timer）不得自建业务时区 @Provides/@Binds，也不得再以 `ZoneId.systemDefault()` 兜底**：
 * 重复绑定既是 Hilt 的 DuplicateBindings 编译错误，也会造成「覆写一处只对一半模块生效」的口径漂移
 * （跨零点时作业归属日 / 计时归属日 / 统计「今天」不一致）；需要替换时区时只覆写 core 那一处。
 * 通知渠道由 [com.assignmate.app.timer.service.TimerNotifications] 在服务启动/闹钟到点时幂等创建，
 * 无需 Hilt 提供（渠道是 Context 级系统资源，非可注入对象）。
 * 到点提醒的接收器与状态核对**刻意不走 Hilt**（闹钟可能唤起冷启动进程）：见
 * [com.assignmate.app.timer.service.HomeworkAlarmReceiver] / [com.assignmate.app.timer.service.HomeworkReminderVerifier]。
 *
 * 如需替换实现（如未来远端计时同步服务、静音版语音实现），仅调整本处 @Binds 即可，UI 与 ViewModel 零改动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TimerModule {

    @Binds
    @Singleton
    abstract fun bindTimerRepository(impl: TimerRepositoryImpl): TimerRepository

    @Binds
    @Singleton
    abstract fun bindTimerTickerController(impl: AndroidTimerTickerController): TimerTickerController

    @Binds
    @Singleton
    abstract fun bindHomeworkAlarmScheduler(impl: AndroidHomeworkAlarmScheduler): HomeworkAlarmScheduler

    @Binds
    @Singleton
    abstract fun bindTimerVoiceGuide(impl: TtsTimerVoiceGuide): TimerVoiceGuide

    @Binds
    @Singleton
    abstract fun bindTimerVoiceSettings(impl: DataStoreTimerVoiceSettings): TimerVoiceSettings

    @Binds
    @Singleton
    abstract fun bindTimerOverduePromptStore(
        impl: DataStoreTimerOverduePromptStore,
    ): TimerOverduePromptStore

    @Binds
    @Singleton
    abstract fun bindTimerPermissionChecker(
        impl: AndroidTimerPermissionChecker,
    ): TimerPermissionChecker

    @Binds
    @Singleton
    abstract fun bindTimerTransactionRunner(impl: RoomTimerTransactionRunner): TimerTransactionRunner

    @Binds
    @Singleton
    abstract fun bindTimerRestStartStore(impl: DataStoreTimerRestStartStore): TimerRestStartStore

    @Binds
    @Singleton
    abstract fun bindTimerReminderScheduleStore(
        impl: DataStoreTimerReminderScheduleStore,
    ): TimerReminderScheduleStore
}
