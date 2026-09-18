package com.assignmate.app.core.domain.ocr

/**
 * 识别缓存清理契约：把「清空待重试任务记录」与「删除对应图片文件」合成一次调用，
 * 供设置页等上层直接使用，避免调用方自己编排两个 core 能力，也不必接触 File API。
 *
 * 实现见 core.data.ocr 的默认实现（组合 [PendingOcrRepository] 与 [OcrImageFileCleaner]）。
 */
interface OcrCacheCleaner {

    /**
     * 清空识别缓存：删除全部待重试任务记录（覆盖 [PendingOcrStatus] 的 4 种状态：
     * PENDING / PROCESSING / SUCCEEDED / FAILED）并删除其引用的图片文件。
     *
     * @return 本次被清理的图片本地路径集合（供上层提示「已释放 N 张图片」等）；
     *         缓存为空时返回空集合且不报错（幂等），图片文件缺失同样静默跳过
     */
    suspend fun clear(): Set<String>
}