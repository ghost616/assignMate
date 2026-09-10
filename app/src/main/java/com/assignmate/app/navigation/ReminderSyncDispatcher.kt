package com.assignmate.app.navigation

import android.util.Log
import com.assignmate.app.di.ApplicationScope
import com.assignmate.app.timer.data.HomeworkReminderCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 到点提醒同步的接线层（framework）：把「作业数据已变化」的收尾动作接到 timer 的
 * [HomeworkReminderCoordinator]，且自身不感知 homework/timer 的任何 UI 细节。
 *
 * 为什么单独成类（而不是直接在导航宿主里 launch）：
 * - **不能挂在页面/ViewModel 作用域**：删除作业、重排时间成功后页面会立刻 popBackStack，
 *   此时页面composition 与 ViewModel 都会被销毁，挂在其上的协程会被取消，
 *   闹钟同步会静默丢失（正是本次要修的缺口）；
 * - 故统一注入进程级 [CoroutineScope]（[@ApplicationScope]）：任务短小、幂等、失败可忽略；
 * - 失败静默降级：仅记录 debug 日志，绝不影响删除/保存主流程，也绝不抛给调用方。
 *
 * 覆盖三个提醒同步入口（时效性）：
 * 1. 删除作业 → [cancelHomeworkReminderSync]（作业已不存在，无法再读取，直接取消旧闹钟）；
 * 2. 重排时间保存 → [syncHomeworkReminder]（同一条作业先按新时刻重设，过期/不合规则取消）；
 * 3. 计时入口（既有）：由 timer 页面进入时 `syncStudentReminders` 按整份清单纠正，
 *    本类不改动该路径，仅保证「不等用户进计时页也能立刻生效」。
 */
@Singleton
class ReminderSyncDispatcher @Inject constructor(
    private val coordinator: HomeworkReminderCoordinator,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * 删除作业后取消其提醒：作业行已删除，按 id 取消即幂等（无闹钟时为无操作）。
     */
    fun cancelHomeworkReminderSync(homeworkId: Long) {
        launchSafely("取消作业提醒失败 homeworkId=$homeworkId") {
            coordinator.cancelHomeworkReminder(homeworkId)
        }
    }

    /**
     * 重排时间（或其它单条作业变化）后同步其提醒：
     * 作业已删除、无开始时间或触发时刻已过期时，协调器内部会取消既有闹钟。
     */
    fun syncHomeworkReminder(homeworkId: Long) {
        launchSafely("同步作业提醒失败 homeworkId=$homeworkId") {
            coordinator.syncHomeworkReminder(homeworkId)
        }
    }

    /** 在进程级作用域内执行提醒同步：不阻塞调用线程（主线程），异常不外抛 */
    private fun launchSafely(failureLog: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e // 作用域取消属正常收尾，不作为失败吞掉
            } catch (e: Exception) {
                logFailure(failureLog, e)
            }
        }
    }

    /**
     * 失败只记录，绝不外抛：连日志本身也包在 [runCatching] 内——
     * 保证「静默降级」这条路径在任何情况下都不会变成新的失败源
     * （例如单元测试环境 android.util.Log 未 mock）。
     */
    private fun logFailure(failureLog: String, e: Exception) {
        runCatching { Log.d(TAG, failureLog, e) }
    }

    private companion object {
        /** 日志 TAG（与工程约定一致：类名） */
        const val TAG = "ReminderSyncDispatcher"
    }
}