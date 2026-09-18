package com.assignmate.app.settings.data

import com.assignmate.app.settings.domain.SettingsDenialReason
import kotlinx.coroutines.flow.Flow

/**
 * 识别缓存（待重试 OCR 任务）的**只读统计**入口，供设置页「数据清理」面板展示待清理条数。
 *
 * 数据来源：core 的 [com.assignmate.app.core.domain.ocr.PendingOcrRepository]（Room 表 ocr_retry_task），
 * 本模块不新增表、不新增存储，只做统计与分权。
 *
 * 口径：覆盖 [com.assignmate.app.core.domain.ocr.PendingOcrStatus] 的**全部 4 种状态**
 * （PENDING / PROCESSING / SUCCEEDED / FAILED），而非仅未完成的 PENDING/PROCESSING——
 * 与 [GuardedOcrCacheCleaner] 的清理范围严格一致，避免「面板显示 2 条、清理却删了 4 条」。
 */
interface OcrCacheRepository {

    /** 待清理任务条数流（任务新增/删除/状态变化后自动重算并发射） */
    val recordCount: Flow<Int>

    /** 主动重算一次条数（清理完成后调用；返回重算后的条数） */
    suspend fun reloadCount(): Int
}

/**
 * 识别缓存清理用例（含分权）：
 *
 * - 学生/未登录会话返回 [OcrCacheCleanResult.Denied]，**不触达** core 清理实现（数据层拒绝）；
 * - 允许时委托 core 的 [com.assignmate.app.core.domain.ocr.OcrCacheCleaner]，
 *   由其在同一调用内完成「清空任务记录（4 种状态）+ 删除对应图片文件」。
 */
interface OcrCacheCleaner {

    /**
     * 清空识别缓存。
     *
     * @return [OcrCacheCleanResult.Cleaned] 携带**本次实际被删除的任务条数**（清理前快照口径）；
     *         [OcrCacheCleanResult.Denied] 表示分权拒绝；
     *         无内容可清时同样返回 [OcrCacheCleanResult.Cleaned]（count = 0，幂等）。
     */
    suspend fun clear(): OcrCacheCleanResult
}

/** 识别缓存清理结果 */
sealed interface OcrCacheCleanResult {

    /** 清理已执行（含空表幂等），[count] 为本次删除的任务条数 */
    data class Cleaned(val count: Int) : OcrCacheCleanResult

    /** 分权拒绝：学生会话/未登录会话不允许清理 */
    data class Denied(val reason: SettingsDenialReason) : OcrCacheCleanResult
}