package com.assignmate.app.settings.domain

import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.settings.data.OcrCacheCleanResult
import com.assignmate.app.settings.data.OcrCacheCleaner
import com.assignmate.app.settings.data.OcrCacheRepository
import com.assignmate.app.settings.data.OcrCacheRepositoryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 数据清理用例（设置页「数据清理」面板的唯一编排点）。
 *
 * 职责边界：
 * - **统计**：经 [OcrCacheRepository] 观察待清理任务条数（覆盖 [PendingOcrStatus] 全部 4 种状态）；
 *   订阅的是 [OcrCacheRepositoryImpl.countUpdates]（由 core 的待识别任务流驱动重算），
 *   因此库内新增/删除/状态变化都会自动刷新面板计数；
 * - **确认文案**：由 [OcrCleanupSnapshot.confirmMessage] 统一生成（「将删除 N 条识别任务（含图片），不可恢复」）；
 * - **执行**：经 [OcrCacheCleaner] 清空记录**并删除图片文件**（图片文件删除由 core 实现保证，不得只删库）；
 * - **回报**：本次清理条数以 [cleanedCount] 单点回报（页面据此刷新计数并提示「已清理 N 条」）。
 *
 * 分权：无权限时 [clear] 返回 [DataCleanupResult.Denied] 且不触达数据层
 * （守卫位于 [OcrCacheCleaner] 实现内，本用例只做结果收敛）。
 *
 * 条数口径：清理前先取一次快照（[reload]），执行后按快照值回报——
 * 与二次确认弹窗展示的 N 严格同源，避免「提示删 3 条、实际回报按去重图片算成 2」。
 */
class DataCleanupService(
    private val cacheRepository: OcrCacheRepository,
    private val cacheObservation: OcrCacheRepositoryImpl,
    private val cleaner: OcrCacheCleaner,
) {

    private val _snapshot = MutableStateFlow(OcrCleanupSnapshot())
    private val _cleanedCount = MutableStateFlow(0)

    /** 待清理内容快照（驱动面板计数、按钮可用性与空态文案） */
    val snapshot: StateFlow<OcrCleanupSnapshot> = _snapshot.asStateFlow()

    /** 最近一次清理**实际删除**的任务条数（0 表示尚未清理或清理时本就为空） */
    val cleanedCount: StateFlow<Int> = _cleanedCount.asStateFlow()

    /**
     * 开始观察待清理条数：先主动重算一次（进入面板即有值），再订阅变更流持续刷新。
     *
     * 注意：本方法会**一直挂起**（订阅数据流直到调用方作用域取消），
     * 调用方应在自己的作用域里 `launch { start() }`。
     */
    suspend fun start() {
        reload()
        cacheObservation.countUpdates().collect { count ->
            _snapshot.value = OcrCleanupSnapshot(recordCount = count)
        }
    }

    /** 主动重算待清理条数（清理完成后调用，保证计数与库内一致） */
    suspend fun reload(): Int {
        val count = cacheRepository.reloadCount()
        _snapshot.value = OcrCleanupSnapshot(recordCount = count)
        return count
    }

    /**
     * 执行清理：先取快照条数，再委托清理（记录 + 图片），最后按快照回报并重算计数。
     *
     * 幂等：无内容可清时返回 [DataCleanupResult.Executed] 且 [cleanedCount] 归 0，不报错。
     */
    suspend fun clear(): DataCleanupResult {
        val countBefore = cacheRepository.reloadCount()
        return when (val result = cleaner.clear()) {
            is OcrCacheCleanResult.Denied -> {
                _cleanedCount.value = 0
                DataCleanupResult.Denied(result.reason)
            }

            is OcrCacheCleanResult.Cleaned -> {
                _cleanedCount.value = countBefore
                reload()
                DataCleanupResult.Executed
            }
        }
    }

    /** 取一次当前待清理条数（不改变快照；供一次性读取使用） */
    suspend fun currentRecordCount(): Int = cacheRepository.recordCount.first()
}