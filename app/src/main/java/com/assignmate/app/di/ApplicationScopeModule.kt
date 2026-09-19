package com.assignmate.app.di

import com.assignmate.app.navigation.AndroidReminderSyncLogSink
import com.assignmate.app.navigation.ReminderSyncLogSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级协程作用域限定符：标记「与进程同生命周期」的 [CoroutineScope]。
 *
 * 使用场景：必须跑完、且不能随页面/ViewModel 销毁而被取消的收尾动作——
 * 如删除作业后取消闹钟、重排时间后同步闹钟（见
 * [com.assignmate.app.navigation.ReminderSyncDispatcher]）。
 * 这类动作发生在页面退出（popBackStack）的同时，若挂在页面/ViewModel 作用域上
 * 会被取消而静默丢失，因此统一注入本作用域执行。
 *
 * 取用约定：仅用于「短小、幂等、失败可忽略」的收尾任务；常规 UI/业务协程仍走
 * viewModelScope，避免后台任务泄漏。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * framework 提供的进程级协程作用域（Hilt 单例）+ 框架层日志出口绑定。
 *
 * 采用 [SupervisorJob]：单个收尾任务失败不影响其它任务；调度器用 [Dispatchers.Default]
 * （任务本质是系统调用/IO，Android 上 Default 与 IO 均可用，统一走 Default 便于测试替换）。
 */
@Module
@InstallIn(SingletonComponent::class)
object ApplicationScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 提醒同步接线层的日志出口（生产实现走 android.util.Log.d）。
     *
     * 单测注入替身出口断言「静默分支确实留痕」；生产由本绑定提供，
     * 保证「静默 no-op 不再无痕」这条可观测性契约不会因漏配绑定而被绕过。
     */
    @Provides
    @Singleton
    fun provideReminderSyncLogSink(): ReminderSyncLogSink = AndroidReminderSyncLogSink
}