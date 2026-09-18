package com.assignmate.app.core.data.ocr

import com.assignmate.app.core.domain.ocr.OcrImageFileCleaner
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [OcrImageFileCleaner] 的默认平台实现：在 IO 线程上做真实的文件删除。
 *
 * 行为约定（与接口一致）：
 * - 仅删除真实存在的普通文件（目录、符号链接目标等一律不动，避免误删）；
 * - 文件不存在、路径非法或删除失败**静默容错**，不抛异常、不上抛错误码，
 *   识别缓存清理属尽力而为，失败最多留下少量残留文件（可由清理流程再次触发）。
 *
 * 注意：本实现只依赖 java.io，不接触 Android Context，也不直接持有业务模块的文件存储，
 * 因此可注入替身（接口）用于 JVM 单测，生产默认绑定见 core.di.OcrModule。
 */
class AndroidOcrImageFileCleaner @Inject constructor() : OcrImageFileCleaner {

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            // runCatching 兜住一切 IO 异常（权限/竞态删除等），保证清理链路不中断
            runCatching {
                val file = File(path)
                if (file.isFile) {
                    file.delete()
                }
            }
        }
    }
}