package com.assignmate.app.timer.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState

/**
 * timer 模块自有路由常量与路径参数协议（framework 的 AssignMateNavHost 负责注册接线，
 * 本模块不直接改 NavHost，与 homework 的 [com.assignmate.app.homework.ui.HomeworkDestination] 约定一致）。
 *
 * 路由清单与接线契约：
 * - EXECUTION：作业执行页，`timer/execution/{studentId}/{homeworkId}`；
 *   「有事走开」（暂停）/「我回来啦」（恢复）/「完成作业」都在本页，完成后经回调进入休息页；
 * - REST：休息页，`timer/rest/{studentId}/{homeworkId}`；完成一项后的 10 分钟倒计时，
 *   结束（或「跳过休息」）后进入下一项提示页；
 * - NEXT_ITEM：下一项提示页，`timer/next/{studentId}`；按清单顺序展示下一条待完成项与「现在开始」，
 *   清单已全部完成时引导进入完成反馈页；
 * - COMPLETION：完成反馈页，`timer/completion/{studentId}`；随机表扬语 + 清单完成情况概览。
 *
 * 传参与解析约定：页面入参一律走本对象的 [studentIdOf] / [homeworkIdOf]，
 * 学生端可传 [ARG_STUDENT_ID_NONE]（0 = 未指定），由页面按当前学生会话解析本人 id（防越权）。
 */
object TimerDestination {

    /** 作业执行页路由模板 */
    const val EXECUTION = "timer/execution/{studentId}/{homeworkId}"

    /** 休息倒计时页路由模板 */
    const val REST = "timer/rest/{studentId}/{homeworkId}"

    /** 下一项提示页路由模板 */
    const val NEXT_ITEM = "timer/next/{studentId}"

    /** 完成反馈页路由模板 */
    const val COMPLETION = "timer/completion/{studentId}"

    // ---- 路径参数名（framework 注册 navArgument 时使用） ----
    const val ARG_STUDENT_ID = "studentId"
    const val ARG_HOMEWORK_ID = "homeworkId"

    /** studentId 缺省值：0 表示未指定（学生会话取本人，家长会话需显式指定） */
    const val ARG_STUDENT_ID_NONE = 0L

    /** homeworkId 缺省值：表示未指定作业（执行页据此走「作业不存在」提示） */
    const val ARG_HOMEWORK_ID_NONE = -1L

    /** 拼装作业执行页实际路由 */
    fun executionRoute(studentId: Long, homeworkId: Long): String =
        "timer/execution/$studentId/$homeworkId"

    /** 拼装休息页实际路由 */
    fun restRoute(studentId: Long, homeworkId: Long): String =
        "timer/rest/$studentId/$homeworkId"

    /** 拼装下一项提示页实际路由 */
    fun nextItemRoute(studentId: Long): String = "timer/next/$studentId"

    /** 拼装完成反馈页实际路由 */
    fun completionRoute(studentId: Long): String = "timer/completion/$studentId"

    /** 解析路径参数 studentId（缺失/非法返回 0，页面据此按会话解析本人或提示未选定学生） */
    fun studentIdOf(raw: String?): Long = raw?.toLongOrNull() ?: ARG_STUDENT_ID_NONE

    /** 解析路径参数 homeworkId（缺失/非法返回 [ARG_HOMEWORK_ID_NONE]） */
    fun homeworkIdOf(raw: String?): Long = raw?.toLongOrNull() ?: ARG_HOMEWORK_ID_NONE
}

/**
 * 路由参数 + 当前会话 -> 目标学生 id（timer 各页面共用，口径与 homework 清单页保持一致）：
 * - 学生会话：固定取本人 id（忽略路由参数，防止越权操作他人作业）；
 * - 家长会话：取路由参数指定的学生（需 > 0）；
 * - 无会话：返回 null，页面据此提示「登录状态已失效 / 请先选择学生」。
 */
internal fun resolveTimerStudentId(
    session: SessionState,
    routeStudentId: Long,
): Long? = when {
    session.isStudent -> session.studentId
    session.role == Role.PARENT -> routeStudentId.takeIf { it > 0L }
    else -> null
}
