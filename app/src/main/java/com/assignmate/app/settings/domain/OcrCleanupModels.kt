package com.assignmate.app.settings.domain

/**
 * 识别缓存清理模型（设置页「数据清理」面板）。
 */

/**
 * 清理快照：待清理任务**条数**（覆盖 [com.assignmate.app.core.domain.ocr.PendingOcrStatus] 的全部 4 种状态）
 * 与去重后的图片张数。
 *
 * 为什么两个数分开：同一条识别任务可能被多次登记（同一张图对应多条记录），
 * 因此「N 条任务」与「N 张图片」是不同口径；二次确认弹窗与结果提示统一以**条数**为准
 * （见 [DataCleanupService.cleanedCount]），图片张数仅作辅助说明。
 */
data class OcrCleanupSnapshot(
    /** 待清理任务条数（4 种状态合计） */
    val recordCount: Int = 0,
    /** 待清理任务引用的图片本地路径去重张数 */
    val imageCount: Int = 0,
) {

    /** 是否无可清理内容（页面据此置灰按钮并展示空态文案） */
    val isEmpty: Boolean get() = recordCount == 0

    /** 二次确认弹窗文案：「将删除 N 条识别任务（含图片），不可恢复」 */
    val confirmMessage: String
        get() = "将删除 $recordCount 条识别任务（含图片），不可恢复"

    /** 空态文案 */
    val emptyHint: String get() = EMPTY_HINT

    companion object {

        /** 空态文案（唯一来源，页面与测试共用） */
        const val EMPTY_HINT: String = "暂无可清理内容"
    }
}

/**
 * 清理结果：**只表示「清理动作是否被允许并已执行」**，被清理条数不在本对象内，
 * 而是经 [com.assignmate.app.settings.domain.DataCleanupService.cleanedCount] 以数据流单点回报，
 * 避免「返回值」与「页面计数」两处口径各自维护。
 */
sealed interface DataCleanupResult {

    /** 执行完毕（含本来就没有内容可清的幂等情形） */
    data object Executed : DataCleanupResult

    /**
     * 分权拒绝：学生会话/未登录会话调用清理一律不触达数据层，
     * 学生端由此在数据层被挡（页面层另有一层不可达保险，见 [SettingsRoleGuard]）。
     */
    data class Denied(val reason: SettingsDenialReason) : DataCleanupResult
}