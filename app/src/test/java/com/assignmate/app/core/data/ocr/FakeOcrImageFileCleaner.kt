package com.assignmate.app.core.data.ocr

import com.assignmate.app.core.domain.ocr.OcrImageFileCleaner

/**
 * 图片文件删除替身（JVM）：记录被请求删除的路径，用于断言「清空识别缓存确实请求删了这些图片」。
 *
 * 不覆写 `deleteAll`，让接口默认方法的批量委托逻辑一并被执行。
 */
class FakeOcrImageFileCleaner : OcrImageFileCleaner {

    /** 按调用顺序记录的待删路径 */
    val deletedPaths = mutableListOf<String>()

    override suspend fun delete(path: String) {
        deletedPaths += path
    }
}