package com.assignmate.app.homework.ui

/**
 * homework 模块自有路由常量与路径参数协议（framework 的 AssignMateNavHost 负责注册接线，
 * 本模块不直接改 NavHost，与 auth 的 [com.assignmate.app.auth.ui.AuthDestination] 约定一致）。
 *
 * 路由清单与接线契约：
 * - LIST：作业清单页，`homework/list/{studentId}`；[HomeworkListRoute] 的 `onBack/onAddHomework/
 *   onEditTime/onEditTemplate/onStartHomework` 回调即导航意图
 *   （`onStartHomework(homeworkId)` 为「开始作业」意图，待接计时页；默认空实现，未接线不影响清单功能）；
 * - ENTRY：录入入口页（手动录入 + 四方式入口框架，OCR/语音由下一计划落地），
 *   `homework/entry/{studentId}`；
 * - TEMPLATE：录入/编辑模板页，`homework/template/{studentId}?homeworkId={id}`；
 *   homeworkId 默认 [ARG_HOMEWORK_ID_NONE]（新建），否则为编辑既有作业；
 * - TIME_SET：时间设定页，`homework/time_set/{studentId}/{homeworkId}`。
 *
 * 解析约定：页面入参一律走本对象的 [studentIdOf] / [homeworkIdOf]，
 * 避免各页面重复写 `toLongOrNull() ?: 0L` 造成口径漂移。
 */
object HomeworkDestination {

    /** 作业清单页路由模板（需以 studentId 填充） */
    const val LIST = "homework/list/{studentId}"

    /** 录入入口页路由模板（手动录入 + 四方式入口框架） */
    const val ENTRY = "homework/entry/{studentId}"

    /** 录入/编辑模板页路由模板 */
    const val TEMPLATE = "homework/template/{studentId}?homeworkId={homeworkId}"

    /** 时间设定页路由模板 */
    const val TIME_SET = "homework/time_set/{studentId}/{homeworkId}"

    // ---- 路径/查询参数名（framework 注册 navArgument 时使用） ----
    const val ARG_STUDENT_ID = "studentId"
    const val ARG_HOMEWORK_ID = "homeworkId"

    /** homeworkId 缺省值：表示「新建作业」而非编辑既有项 */
    const val ARG_HOMEWORK_ID_NONE = -1L

    /** 拼装清单页实际路由 */
    fun listRoute(studentId: Long): String = "homework/list/$studentId"

    /** 拼装录入入口页实际路由 */
    fun entryRoute(studentId: Long): String = "homework/entry/$studentId"

    /**
     * 拼装录入/编辑模板页实际路由。
     *
     * @param homeworkId 传 [ARG_HOMEWORK_ID_NONE] 表示新建
     */
    fun templateRoute(studentId: Long, homeworkId: Long = ARG_HOMEWORK_ID_NONE): String =
        "homework/template/$studentId?$ARG_HOMEWORK_ID=$homeworkId"

    /** 拼装时间设定页实际路由 */
    fun timeSetRoute(studentId: Long, homeworkId: Long): String =
        "homework/time_set/$studentId/$homeworkId"

    /** 解析路径参数 studentId（缺失/非法返回 0L，页面据此走缺失会话提示） */
    fun studentIdOf(raw: String?): Long = raw?.toLongOrNull() ?: 0L

    /** 解析路径参数 homeworkId（缺失/非法返回 [ARG_HOMEWORK_ID_NONE]，即新建语义） */
    fun homeworkIdOf(raw: String?): Long = raw?.toLongOrNull() ?: ARG_HOMEWORK_ID_NONE
}