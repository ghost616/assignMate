package com.assignmate.app.stats.data

import com.assignmate.app.stats.domain.DaySummary
import com.assignmate.app.stats.domain.HistoryQuery
import com.assignmate.app.stats.domain.ItemDetail

/**
 * 统计仓库接口：作业完成情况的盘点、单项详情与历史查询（**纯读取聚合**）。
 *
 * 数据来源与组合方式（不新增数据库表，只读既有落库数据）：
 * - homework 的 [com.assignmate.app.homework.data.HomeworkRepository]：作业清单（内容/类型/优先级/阶段覆盖日
 *   与「当天作业归属日」所需的创建时刻、状态列）；
 * - core 的 [com.assignmate.app.core.domain.homework.HomeworkDailyRecordRepository]：**作业每天详情**
 *   （当天状态、当天预估/实际/暂停时长与暂停次数）——阶段作业「一条作业项 + 每天详情」改造后，
 *   一切「某一天」的数据口径都以每天详情为准，本模块不再读取计时会话、也不自行裁剪时段；
 * - auth 的 [com.assignmate.app.auth.data.AuthRepository]：当前会话（角色/家长归属/学生）用于越权校验。
 *
 * **学生维度入参**：本接口的查询方法一律显式接收 `studentId`（由 UI 按路由参数 + 会话解析后传入），
 * 仓库层再按会话可见范围兜底校验，避免「家长多看一个学生」这类越权（与 homework/timer 同源口径）。
 *
 * **当天应做口径（完成率分母）**：某学生某一天的应做作业 = 「当天作业（归属日即当天）+ 阶段作业
 * （今天落在其阶段覆盖范围内）」，由作业项推导（口径与 homework 清单页同源）；
 * 完成数 = 其中当天状态为已完成的数量（阶段作业按每天详情、当天作业按状态列）；
 * 分母为 0 时完成率为 0（不除零），页面展示儿童友好的空态。
 * 暂停次数/暂停总时长/暂停最久作业均取自这些应做作业**当天的每天详情**（跨天计时按开始日归属，
 * 与 timer 口径一致）；「当天」为**业务时区自然日**。
 *
 * 失败与权限收敛约定（不抛业务异常，成败统一为 [StatsResult]）：
 * - 无有效会话：[StatsFailure.NO_ACTIVE_SESSION]；
 * - 目标学生不在当前会话可见范围内：[StatsFailure.ACCESS_DENIED]；
 * - 作业不存在：[StatsFailure.HOMEWORK_NOT_FOUND]；
 * - 数据访问异常：[StatsFailure.READ_FAILED]（可重试）。
 */
interface StatsRepository {

    /**
     * 当日盘点：按学生维度聚合某一天的完成率、暂停次数、暂停总时长、暂停最久作业与阶段打卡进度。
     *
     * @param studentId 目标学生（家长会话需为该家长名下学生；学生会话仅可为本人）
     * @param epochDay 目标自然日（业务时区纪元日）；调用方按业务时区取「今天」
     */
    suspend fun summarizeDay(studentId: Long, epochDay: Long): StatsResult<DaySummary>

    /**
     * 单项作业详情（**指定某一天**）：当天预估时长 / 当天实际时长 / 当天暂停时长 / 当天状态
     * + 困难度侧面评估 + 阶段作业打卡进度。
     *
     * **数据维度**：以**作业归属学生**为准取数（作业的 `studentId` 决定查询维度）；
     * 仓库会校验「会话可见学生」与「作业归属学生」一致——学生会话查看不属于本人的作业、
     * 或家长会话在名下仅有一名学生时查看他人学生的作业，统一返回 [StatsFailure.ACCESS_DENIED]。
     *
     * 时长与暂停均为**当天口径**（阶段作业需按天分别查看，不再给整段合计）；
     * 当天没有详情时不返回错误：返回 [ItemDetail] 且 [ItemDetail.hasExecution] 为 false，
     * 界面据此展示「尚未开始 / 暂无数据」。
     *
     * @param homeworkId 目标作业
     * @param epochDay 要查看的自然日（业务时区纪元日；缺省语义由调用方按「今天」解析）
     */
    suspend fun itemDetail(homeworkId: Long, epochDay: Long): StatsResult<ItemDetail>

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
