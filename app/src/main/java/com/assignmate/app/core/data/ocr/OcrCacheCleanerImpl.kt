package com.assignmate.app.core.data.ocr

import com.assignmate.app.core.domain.ocr.OcrCacheCleaner
import com.assignmate.app.core.domain.ocr.OcrImageFileCleaner
import com.assignmate.app.core.domain.ocr.PendingOcrRepository
import javax.inject.Inject

/**
 * [OcrCacheCleaner] 的默认实现：先清空待重试任务记录（拿到待删图片路径），再删除这些图片文件。
 *
 * 顺序不可颠倒：先删文件后删记录的话，中途失败会留下「记录指向不存在的图片」的脏任务；
 * 先删记录再删文件，最坏情况只是少量残留文件（可由同一入口再次触发清理回收）。
 *
 * @param pendingOcrRepository 待重试任务存储（core.data.db 的 Room 实现）
 * @param imageFileCleaner 图片文件删除抽象（默认平台实现执行真实删除，测试可注入替身）
 */
class OcrCacheCleanerImpl @Inject constructor(
    private val pendingOcrRepository: PendingOcrRepository,
    private val imageFileCleaner: OcrImageFileCleaner,
) : OcrCacheCleaner {

    override suspend fun clear(): Set<String> {
        val removedPaths = pendingOcrRepository.clearAll()
        if (removedPaths.isNotEmpty()) {
            imageFileCleaner.deleteAll(removedPaths)
        }
        return removedPaths
    }
}