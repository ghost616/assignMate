package com.assignmate.app.homework.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「保存成功」提示的**跨页一次性暂存**（保存页 → 清单页）。
 *
 * 为什么需要它：录入 / 模板 / 时间设定三个页面在保存成功后都要回清单（framework 的 `onSaved` 即
 * `popBackStack()`）。修复前三个页面都是「先 `showSnackbar(...)` 再 `onSaved(...)`」，由此产生两类缺陷：
 * 1) `showSnackbar` 是**挂起**调用（Long 约 10 秒），用户必须等提示条消失才回得到清单；
 * 2) 提示期间页面离开组合 → 协程被取消，其后的 `onSaved`（含「按 id 精确同步提醒」）**完全不会执行**。
 *
 * 现改为「**先回清单、再非挂起派发提示**」：保存页只把该告知用户的文案放进本暂存器
 * （[publish] 是普通状态写入，无挂起点，因此不会被组件销毁带来的协程取消吞掉），
 * 由**导航后的目标页（清单页）**展示，见 `HomeworkListRoute` 的消费点。
 *
 * 实现取舍（由力牧定，供审查与后续维护参考）：
 * - 用 [MutableStateFlow] 承载而非一次性 Channel：`popBackStack` 会**重建**清单页，提示可能先发布、
 *   订阅者后到位；StateFlow 会把当前值重放给新订阅者，故提示不会因订阅时机而丢失；
 * - 只保留**最新一条**：连续保存时旧提示让位于新提示（「保存成功」是确认性提示，无需排队）；
 * - 清单页以 `SnackbarDuration.Short` 展示：确认性提示不需要 Long 的 10 秒驻留，也不至于与清单页
 *   其它操作提示长时间排队（提示文案本身不缩短、不丢字）；
 * - 纯 Kotlin、不依赖 Compose：可被单测直接驱动（见 `HomeworkSaveCompletionOrderTest`）。
 */
class HomeworkSaveNoticeHolder {

    private val _notice = MutableStateFlow<String?>(null)

    /** 当前待展示的提示文案；null 表示无待展示提示（清单页展示后经 [consume] 清空） */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * 发布一条待展示提示。
     *
     * **非挂起**：可在 `onSaved` 触发导航之后安全调用——没有挂起点，就不会被组件销毁导致的
     * 协程取消中途打断（这正是修复「提示与 onSaved 一起被吞掉」的关键）。
     */
    fun publish(message: String) {
        _notice.value = message
    }

    /**
     * 消费掉与 [message] 相同的那条提示（清单页展示完毕后调用）。
     *
     * 用 `compareAndSet` 而非直接置空：展示期间可能已有**新**提示发布，直接置空会把它清掉。
     */
    fun consume(message: String) {
        _notice.compareAndSet(message, null)
    }
}

/**
 * 进程内共享实例：三个保存页（录入 / 模板 / 时间设定）发布，清单页消费展示。
 *
 * 为什么是模块级单例而不是注入依赖：保存页与清单页是两个独立的 Compose 目的地（各自 `hiltViewModel()`），
 * 提示必须跨目的地留存；为此给四个 ViewModel 增加构造参数会波及大量既有测试替身，收益低于成本。
 * 本对象只承载一条提示字符串，语义边界清晰、可单测（测试用独立实例即可）。
 */
internal val homeworkSaveNotice: HomeworkSaveNoticeHolder = HomeworkSaveNoticeHolder()

/**
 * 保存成功的收尾编排（**非挂起**纯逻辑，可单测时序与「不阻塞 / 不丢失」两条性质）：
 * 1) 先调 [onSaved] —— framework 借它同步提醒并回清单，**不得**先等提示条，
 *    否则用户要等约 10 秒才回得到清单（且提示期间离开组合会连 [onSaved] 一起被取消）；
 * 2) 再把 [dispatchMessage] 写入 [saveNotice] —— 非挂起写入，因此即便上一步已触发导航、
 *    本页组合随之销毁，提示也不会丢失，随后由清单页在导航后展示。
 *
 * @param dispatchMessage 保存成功文案；null 表示本次保存无需额外提示（如模板页的编辑保存，
 *   与修复前行为一致：编辑保存本就只有 `Saved` 事件、没有提示条）
 */
internal fun dispatchSaveCompletion(
    dispatchMessage: String?,
    saveNotice: HomeworkSaveNoticeHolder,
    onSaved: () -> Unit,
) {
    onSaved()
    if (dispatchMessage != null) {
        saveNotice.publish(dispatchMessage)
    }
}