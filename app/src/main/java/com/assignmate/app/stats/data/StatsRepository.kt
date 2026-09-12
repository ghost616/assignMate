package com.assignmate.app.stats.data

import com.assignmate.app.stats.domain.DaySummary
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.ItemDetail

/**
 * 统计仓库接口：作业完成情况的盘点、单项详情与历史查询（**纯读取聚合**）。
 *
 * 数据来源与组合方式（不新增数据库表，只读既有落库数据）：
 * - homework 的 [com.assignmate.app.homework.data.HomeworkRepository]：作业清单与状态、预估时长、优先级；
 * - timer 的 [com.assignmate.app.timer.data.TimerRepository]：执行会话（loadSessionsByStudent）
 *   与暂停明细（loadPausesByHomework）；
 * - auth 的 [com.assignmate.app.auth.data.AuthRepository]：当前会话（角色/家长归属/学生）用于越权校验。
 *
 * **学生维度入参**：本接口的查询方法一律显式接收 `studentId`（由 UI 按路由参数 + 会话解析后传入），
 * 仓库层再按会话可见范围兜底校验，避免「家长多看一个学生」这类越权（与 homework/timer 同源口径）。
 *
 * **当日盘点口径（完成率分母）**：某作业纳入当日盘点的条件为三者之一——
 * 1. 当日产生了执行时长：会话时间段与当日窗口相交且裁剪后净耗时 > 0，
 *    含「前一日开始、跨零点仍在进行」的会话（按当日窗口裁剪计时，
 *    跨天暂停同样按窗口裁剪，不重复计入相邻两天）；
 * 2. 当日有仍未结束的会话（如「刚点开始计时」的作业）：会话起点落在当日窗口内且未结束，
 *    此时净耗时可能尚为 0，但它确实在当天动过，不应从盘点里消失；
 * 3. 当日完成：已完成会话的结束时刻落在当日（覆盖「不经过计时直接在清单里标记完成」的情形）。
 * 分母 = 满足上述条件的作业数，分子 = 其中状态为已完成的作业数；两者为 0 时完成率为 0。
 * 「当日」为**业务时区自然日**（时区由 Hilt 注入，默认系统时区），
 * 时间取值一律经可注入 [com.assignmate.app.core.domain.time.Clock]，便于测试注入固定时钟。
 *
 * 失败与权限收敛约定（不抛业务异常，成败统一为 [StatsResult]）：
 * - 无有效会话：[StatsFailure.NO_ACTIVE_SESSION]；
 * - 目标学生不在当前会话可见范围内：[StatsFailure.ACCESS_DENIED]；
 * - 作业不存在：[StatsFailure.HOMEWORK_NOT_FOUND]；
 * - 数据访问异常：[StatsFailure.READ_FAILED]（可重试）。
 */
interface StatsRepository {

    /**
     * 当日盘点：按学生维度聚合某一天的完成率、暂停次数、暂停总时长与暂停最久作业。
     *
     * @param studentId 目标学生（家长会话需为该家长名下学生；学生会话仅可为本人）
     * @param epochDay 目标自然日（UTC 纪元日）；调用方按业务时区取「今天」
     */
    suspend fun summarizeDay(studentId: Long, epochDay: Long): StatsResult<DaySummary>

    /**
     * 单项作业详情：预估时长 / 实际时长（会话已用时长合计）/ 暂停时长 / 执行会话数 + 困难度侧面评估。
     *
     * **数据维度**：以**作业归属学生**为准取数（作业的 `studentId` 决定查询维度）；
     * 仓库会校验「会话可见学生」与「作业归属学生」一致——学生会话查看不属于本人的作业、
     * 或家长会话在名下仅有一名学生时查看他人学生的作业，统一返回 [StatsFailure.ACCESS_DENIED]。
     *
     * 时长口径为**累计**（全部历史执行会话，已扣除暂停），与当日盘点的「当日窗口」口径可能不同。
     * 无执行记录时不返回错误：返回 [ItemDetail] 且 [ItemDetail.hasExecution] 为 false，
     * 界面据此展示「尚未开始 / 暂无数据」。
     */
    suspend fun itemDetail(homeworkId: Long): StatsResult<ItemDetail>

    /**
     * 历史查询：按日期（或日期范围）返回逐日盘点，按日期倒序（最近的一天在前）。
     *
     * 无权限/无会话时返回 [StatsFailure]（不抛异常）；跨度超过
     * [com.assignmate.app.stats.domain.StatsConstants.MAX_HISTORY_DAYS] 时自动收敛结束日。
     */
    suspend fun history(studentId: Long, query: HistoryQuery): StatsResult<List<DaySummary>>
}

/** 统计查询结果（失败收敛为可读原因，不抛业务异常） */
sealed interface StatsResult<out T> {

    /** 查询成功 */
    data class Success<T>(val data: T) : StatsResult<T>

    /** 查询失败：数据不存在或读取异常（用户文案由 UI 层映射） */
    data class Failure(val reason: StatsFailure) : StatsResult<Nothing>
}

/** 统计查询失败原因（机器可读；用户文案见 stats.ui.StatsErrorMessages） */
enum class StatsFailure {

    /** 目标作业不存在（可能已被删除） */
    HOMEWORK_NOT_FOUND,

    /** 无有效会话（未登录/已登出） */
    NO_ACTIVE_SESSION,

    /** 目标学生不在当前会话可见范围内（越权访问） */
    ACCESS_DENIED,

    /** 查询入参非法（结束日早于开始日） */
    INVALID_QUERY,

    /** 数据读取失败（本地库异常等，可重试） */
    READ_FAILED,
}