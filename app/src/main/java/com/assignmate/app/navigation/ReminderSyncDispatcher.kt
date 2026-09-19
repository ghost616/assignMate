package com.assignmate.app.navigation

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
 * **本类覆盖四个提醒同步入口**（时效性；计数以本类实际入口为准，与实现逐条相符）：
 * 1. 删除作业 → [cancelHomeworkReminderSync]（作业行已删除、无法再读取，直接取消旧闹钟）；
 * 2. 重排时间保存 → [syncHomeworkReminder]（先按新时刻重设，过期/不合规则由协调器取消）；
 * 3. 保存成功（录入页新建与模板页新建/编辑**共用同一入口**）→ [syncHomeworkSavedReminder]
 *    （优先按保存成功事件回抛的作业 id 精确同步；事件与路由都拿不到 id 且家长会话时才退化为整份清单纠正）；
 * 4. 计时入口（既有）：由 timer 页面进入时直接调用协调器整份清单纠正，
 *    本类不改动该路径，仅以 [syncStudentReminders] 复用同一协调器入口兜底，
 *    保证「不等用户进计时页也能立刻生效」。
 *
 * 为什么把录入页与模板页记为**一个**入口（而非两个）：两条页面路径都要经 [syncHomeworkSavedReminder]
 * 的同一套分派逻辑（同一方法、同一分支），本类不新增机制也不新增入口；
 * 页面侧的四个动作与 [AssignMateNavHost] 注册块一一对应，见该宿主 KDoc。
 */
@Singleton
class ReminderSyncDispatcher @Inject constructor(
    private val coordinator: HomeworkReminderCoordinator,
    @ApplicationScope private val scope: CoroutineScope,
    private val logSink: ReminderSyncLogSink = AndroidReminderSyncLogSink,
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

    /**
     * 按某学生的当前清单纠正其全部提醒（幂等）：与「进入计时页」的既有纠正走同一协调器入口，
     * 供导航层拿不到「具体作业 id」时兜底使用。
     */
    fun syncStudentReminders(studentId: Long) {
        launchSafely("按清单纠正学生提醒失败 studentId=$studentId") {
            coordinator.syncStudentReminders(studentId)
        }
    }

    /**
     * 作业**保存成功**后的提醒同步（录入页新建作业、模板页新建/编辑作业共用同一入口）。
     *
     * 口径按「导航层拿到什么」分派，均为既有桥接入口，不新造机制：
     * - [savedHomeworkId] 为正数 → **按该作业 id 精确同步**（[syncHomeworkReminder]），与时间设定页保存口径
     *   完全一致：阶段作业按阶段覆盖区间逐日排定（请求码仍由协调器统一走
     *   `requestCodeOf(homeworkId, epochDay)`）、当天作业按绝对时刻设单次提醒、作业已不存在或不再满足
     *   提醒规则时取消；类型由阶段切回当天时协调器顺带清掉历史逐日闹钟。
     *   该 id 由 homework 侧保存成功事件回抛（编辑 = 被编辑作业，新建 = 新作业项的真实 id），
     *   故**新建作业也能按 id 精确同步**，不再只能靠「按学生整份清单纠正」兜底——
     *   学生端会话路由不携带学生（studentId = 0）时同样成立，因为按 id 同步不需要学生 id。
     * - [savedHomeworkId] 非正数（事件与路由都拿不到作业 id）且 [studentId] 为正数（家长会话）→
     *   退化为该学生「整份清单纠正」（[syncStudentReminders]），与进入计时页的纠正入口同一来源，幂等；
     * - 两者都不成立（拿不到 id + 学生端「未指定学生」）→ 不发起任何同步：框架侧不猜测、避免误伤他人提醒；
     *   该分支**不再是静默 no-op**——会经 [logSink] 输出一条 debug 日志（TAG = [TAG]，只含两个 id），
     *   使「提醒同步缺口」在开发侧可追溯（此前无线索、缺口可静默复现）。
     *   日志本身也走 [runCatching] 兜底（与失败留痕同一策略）：留痕失败绝不改变「不发起同步」这一语义，
     *   也不会在单元测试等 android.util.Log 未桩实现的环境里变成新的失败源。
     *
     * 三种情形互斥（按 id 同步 / 按学生兜底纠正 / 留痕 no-op），
     * 故不存在「既按 id 同步又整份纠正」造成的重复调度；
     * 结构上按「可执行分支各自提前 return → 末尾仅剩 no-op 留痕」书写，
     * 不引入无条件的 else 落空分支（避免歧义性的「静默兜底」写法）。
     *
     * @param studentId 路由上的学生 id（家长 = 被选学生；学生端 = 未指定哨兵 0，仅兜底分支使用）
     * @param savedHomeworkId 保存成功事件回抛的作业 id；非正数 = 拿不到 id（回退到兜底分支）
     */
    fun syncHomeworkSavedReminder(studentId: Long, savedHomeworkId: Long) {
        if (savedHomeworkId > 0L) {
            syncHomeworkReminder(savedHomeworkId)
            return
        }
        if (studentId > 0L) {
            syncStudentReminders(studentId)
            return
        }
        // 静默 no-op 分支必须留痕：id 全不可用时不做任何同步（不猜测、不误伤他人提醒），
        // 但要给开发侧一条可追溯线索，避免提醒同步缺口静默复现；留痕失败不影响本分支语义
        runCatching {
            logSink.debug(
                TAG,
                "跳过提醒同步：作业 id 与学生 id 均非正数 savedHomeworkId=$savedHomeworkId studentId=$studentId",
                null,
            )
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
        runCatching { logSink.debug(TAG, failureLog, e) }
    }

    private companion object {
        /** 日志 TAG（与工程约定一致：类名） */
        const val TAG = "ReminderSyncDispatcher"
    }
}