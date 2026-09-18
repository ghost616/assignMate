package com.assignmate.app.settings.data

import com.assignmate.app.core.domain.ocr.PendingOcrRepository
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * [OcrCacheRepository] 默认实现：4 种状态条数合计 + 变更后自动重算。
 *
 * 变更触发：观察待识别/识别中任务的 core 数据流（任一任务的增删改都会触发一次重算），
 * 重算再按 4 种状态全量取数——比只统计「待识别 + 识别中」更准确：
 * SUCCEEDED / FAILED 状态变化同样会推送一次，因此面板计数最终与库内一致。
 *
 * @param pendingOcrRepository core 的待重试任务存储接口（Room 实现）
 */
class OcrCacheRepositoryImpl(
    private val pendingOcrRepository: PendingOcrRepository,
) : OcrCacheRepository {

    private val _recordCount = MutableStateFlow(0)

    override val recordCount: Flow<Int> = _recordCount.asStateFlow()

    override suspend fun reloadCount(): Int {
        val count = countAllStatuses()
        _recordCount.value = count
        return count
    }

    /** 触发重算的内部流：core 的待识别任务变更 -> 每次变更加载一次全量条数 */
    fun countUpdates(): Flow<Int> =
        pendingOcrRepository.observePending()
            .map { countAllStatuses() }
            .onStart { emit(countAllStatuses()) }

    private suspend fun countAllStatuses(): Int =
        PendingOcrStatus.entries.sumOf { status ->
            pendingOcrRepository.loadByStatus(status).size
        }
}