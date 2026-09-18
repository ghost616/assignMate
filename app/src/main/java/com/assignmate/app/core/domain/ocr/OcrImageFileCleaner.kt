package com.assignmate.app.core.domain.ocr

/**
 * OCR 图片文件删除抽象：只负责「删除本地图片文件」这一件事。
 *
 * 为什么要抽成接口：
 * - core 不得依赖业务模块的文件存储（如 homework 的 HomeworkFileStore），否则形成反向依赖；
 * - 清理动作若在实现里直接 `File(path).delete()`，则 JVM 单测无法替换/断言；
 *   抽成接口后可注入替身，使「清空识别缓存时确实删了这些图片」可被单测验证。
 *
 * 默认实现见 core.data.ocr 的平台实现（真实文件删除 + 静默容错），Hilt 绑定见 core.di.OcrModule。
 */
interface OcrImageFileCleaner {

    /**
     * 删除指定本地图片文件。
     *
     * 约定：文件不存在、路径为空或删除失败一律**静默容错**，不抛异常——清理属尽力而为，
     * 不应因单个文件异常中断调用方（如设置页的「清空识别缓存」）流程。
     */
    suspend fun delete(path: String)

    /**
     * 批量删除（默认逐个委托 [delete]，同样静默容错）。
     *
     * @param paths 待删除的图片本地路径集合，通常来自 [PendingOcrRepository.clearAll] 的返回值
     */
    suspend fun deleteAll(paths: Collection<String>) {
        paths.forEach { delete(it) }
    }
}