package com.assignmate.app.homework.domain

/**
 * homework 模块业务规则常量集中收敛，禁止在实现层散落魔法值。
 * 规则同源：HomeworkValidators / 仓库实现 / UI 提示共用本组常量。
 */
object HomeworkConstants {

    // ---- 作业内容 ----
    /** 作业内容最大长度（按字符计，含标点） */
    const val MAX_CONTENT_LENGTH = 200

    // ---- 优先级 ----
    /**
     * 优先级最小值。约定：priority 数值越小越靠前（越紧急）；
     * 新增作业默认追加到清单末尾（priority = 当前最大值 + [PRIORITY_STEP]，空清单取 [MIN_PRIORITY]），
     * 上移/下移通过与相邻项交换 priority 实现，故数值间隔不影响排序语义。
     */
    const val MIN_PRIORITY = 0

    /** 新增作业相对当前最大优先级的步长（保证追加到末尾且预留插入空间） */
    const val PRIORITY_STEP = 1

    // ---- 开始时间与预估时长 ----
    /** 预估时长下限（分钟） */
    const val MIN_ESTIMATED_MINUTES = 1

    /** 预估时长上限（分钟）：10 小时，作为录入防误输阈值 */
    const val MAX_ESTIMATED_MINUTES = 600

    // ---- 阶段范围 ----
    /** 「一个月」阶段范围的折算天数（固定折算，避免跨月天数歧义） */
    const val DAYS_PER_MONTH = 30

    // ---- OCR 待重试 ----
    /** 待重试识别任务的最大重试次数：超过后自动清理，避免失败任务永久滞留 */
    const val MAX_OCR_RETRY_COUNT = 5

    /** 残留图片最长保留时长（毫秒）：超过且无任务引用即视为孤儿文件清理（24 小时） */
    const val ORPHAN_IMAGE_TTL_MILLIS = 24 * 60 * 60 * 1000L

    // ---- 主键 ----
    /** 尚未落库的作业项占位主键（真实 id 由 Room 自增分配）；UI 亦用其表示「新建」语义 */
    const val INVALID_ID = 0L
}