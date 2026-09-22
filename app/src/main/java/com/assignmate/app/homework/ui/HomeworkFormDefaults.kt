package com.assignmate.app.homework.ui

import com.assignmate.app.auth.domain.Role
import com.assignmate.app.homework.domain.HomeworkDailyDeadlineCodec
import com.assignmate.app.homework.domain.HomeworkType
import com.assignmate.app.homework.domain.StageRange

/**
 * 录入/编辑表单的**角色口径**（两个录入页共用同一份定义，避免各写一套而漂移）：
 *
 * 1) 可选作业类型 [homeworkTypeOptions]：家长会话只能选择「阶段作业」（需求：去掉家长的当天作业添加），
 *    学生会话保持「当天作业 / 阶段作业」两项；
 * 2) 角色默认表单值 [homeworkFormDefaults]：家长进入表单即预填「阶段作业 + 一周 + 21:00」，
 *    降低必填项操作成本；学生维持既有默认（当天作业、无阶段范围、无每日时刻）。
 *
 * 为什么收敛到一处：[HomeworkEntryViewModel]（添加页）与 [HomeworkTemplateViewModel]（编辑页）
 * 必须同口径——家长在添加页看不到「当天作业」，编辑页同样不该出现该选项。
 *
 * 时区口径：默认每日截止时刻是**钟面值**（time-of-day），与业务时区无关，故这里只产出 `HH:mm`
 * 文本——经 [HomeworkDailyDeadlineCodec.formatTime] 从唯一常量
 * [HomeworkDailyDeadlineCodec.DEFAULT_DEADLINE_TIME] 取值，不新增第二份「21:00」字面量，
 * 也不做任何 UTC 毫秒折算。
 */

/** 家长会话唯一可选（且新建默认）的作业类型：阶段作业 */
internal val PARENT_HOMEWORK_TYPE: HomeworkType = HomeworkType.STAGE

/** 家长阶段作业表单预填的阶段范围：一周 */
internal val DEFAULT_STAGE_RANGE: StageRange = StageRange.ONE_WEEK

/** 家长阶段作业表单预填的每日截止时刻文本（HH:mm） */
internal val DEFAULT_DAILY_DEADLINE_TEXT: String =
    HomeworkDailyDeadlineCodec.formatTime(HomeworkDailyDeadlineCodec.DEFAULT_DEADLINE_TIME)

/**
 * 当前会话**可选**的作业类型（UI 只渲染这些选项，ViewModel 亦据此拒绝越界选择）：
 * - 家长：[PARENT_HOMEWORK_TYPE] 一项（家长只能创建阶段作业）；
 * - 学生：两项，默认当天作业（学生端行为不得改变）；
 * - `role` 为 null（会话尚未建立/已失效）：按学生口径给两项——此时表单尚未渲染，
 *   不抢先收敛，避免在拿不到角色时误把选项砍掉。
 */
internal fun homeworkTypeOptions(role: Role?): List<HomeworkType> =
    if (role == Role.PARENT) listOf(PARENT_HOMEWORK_TYPE) else HomeworkType.entries.toList()

/** 某角色进入表单时的默认类型 / 阶段范围 / 每日截止时刻文本 */
internal data class HomeworkFormDefaults(
    val type: HomeworkType,
    val stageRange: StageRange?,
    val deadlineTime: String,
)

/**
 * 角色默认表单值：
 * - 家长：阶段作业 + 一周 + 21:00（可直接保存的最小操作成本）；
 * - 学生 / 会话未建立：当天作业 + 无阶段范围 + 无每日时刻（**与既有行为逐字一致**——
 *   [HomeworkTemplateUiState] / [HomeworkEntryUiState] 的初始值即此口径，
 *   故对学生套用本默认值是空操作）。
 */
internal fun homeworkFormDefaults(role: Role?): HomeworkFormDefaults =
    if (role == Role.PARENT) {
        HomeworkFormDefaults(
            type = PARENT_HOMEWORK_TYPE,
            stageRange = DEFAULT_STAGE_RANGE,
            deadlineTime = DEFAULT_DAILY_DEADLINE_TEXT,
        )
    } else {
        HomeworkFormDefaults(type = HomeworkType.TODAY, stageRange = null, deadlineTime = "")
    }

/**
 * 用户是否已**显式改过**某个表单字段。
 *
 * 存在意义：会话角色在 `start()` 中异步解析（表单在拿到角色前不渲染），默认值收敛发生在角色
 * 到位之后；若不加区分地把默认值写回，就会覆盖用户已经改过的选择。故每个页面各持一份本标记，
 * 收敛时逐字段判断——收敛因此**幂等**（再次收敛结果不变）且**不覆盖用户选择**。
 */
internal data class HomeworkFormTouched(
    val type: Boolean = false,
    val stageRange: Boolean = false,
    val deadlineTime: Boolean = false,
)

/**
 * 把本角色默认值**收敛应用**到当前表单取值上：仅对用户尚未改过的字段套用默认值。
 *
 * @param type 收敛前的类型取值（用户改过则原样保留）
 * @param stageRange 收敛前的阶段范围取值（用户改过则原样保留）
 * @param deadlineTime 收敛前的每日截止时刻文本（用户改过则原样保留）
 * @param touched 用户已显式改过的字段集合（见 [HomeworkFormTouched]）
 */
internal fun HomeworkFormDefaults.appliedTo(
    type: HomeworkType,
    stageRange: StageRange?,
    deadlineTime: String,
    touched: HomeworkFormTouched,
): HomeworkFormDefaults = HomeworkFormDefaults(
    type = if (touched.type) type else this.type,
    stageRange = if (touched.stageRange) stageRange else this.stageRange,
    deadlineTime = if (touched.deadlineTime) deadlineTime else this.deadlineTime,
)