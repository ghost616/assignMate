package com.assignmate.app.homework.data

import com.assignmate.app.core.domain.ocr.OcrImage
import java.io.File

/**
 * 作业录入图片/音频的本地文件存储抽象：拍照临时文件、相册读入、待重试图片落盘统一经此接口。
 *
 * 抽象目的：
 * - 让录入链路中的「图片落盘 + 读回为 OcrImage」可被单测替换（Fake 实现基于临时目录即可）；
 * - Android 实现（[HomeworkFileStoreImpl]）负责目录选择、压缩与失败兜底，业务层不直接碰 Context/File API。
 *
 * 文件生命周期约定（由录入链路负责调用，避免 cacheDir 无界增长）：
 * - 拍照临时文件：识别成功或不可重试失败后删除；可重试失败时经 [promoteCaptureFile] 转正为待重试图片；
 * - 待重试图片：识别成功后删除；重试次数超上限或图片失效时由重试链路清理；
 * - 启动时可调用 [cleanupOrphanFiles] 清理长时间无任务引用的残留文件。
 */
interface HomeworkFileStore {

    /** 创建一个拍摄用临时图片文件（相机的输出目标），返回其绝对路径 */
    fun createCaptureFile(): String

    /**
     * 读取本地图片为 [OcrImage]（Android 实现会先做尺寸/质量压缩），
     * 便于上层区分「文件不存在」与「文件过大不可上传」并给出不同提示。
     */
    fun loadImage(path: String): ImageLoadResult

    /** 将图片字节落盘（待重试登记用），返回绝对路径；失败返回 null */
    fun savePendingImage(bytes: ByteArray, mimeType: String): String?

    /**
     * 把拍照临时文件「转正」为待重试图片（同目录重命名，不重复复制字节，避免整图二次落盘）。
     *
     * @return 转正后的路径；重命名失败返回 null（调用方回退为 [savePendingImage] + 删除原文件）
     */
    fun promoteCaptureFile(path: String, mimeType: String): String?

    /** 删除本地文件（清理待重试任务图片） */
    fun delete(path: String)

    /** 本地文件是否存在且可读 */
    fun exists(path: String): Boolean

    /**
     * 清理孤儿文件：删除创建时间早于 [olderThanMillis] 且不在 [referencedPaths] 中的文件。
     * 供录入页启动时调用，回收识别完成后未及时清理的残留图片。
     *
     * @return 实际删除的文件数
     */
    fun cleanupOrphanFiles(referencedPaths: Set<String>, olderThanMillis: Long = 0L): Int
}

/** 图片读取结果：区分成功、文件缺失与超出上传上限三类，供上层给出不同提示 */
sealed interface ImageLoadResult {

    /** 读取成功（已按需压缩） */
    data class Loaded(val image: OcrImage) : ImageLoadResult

    /** 文件不存在或不可读 */
    data object Missing : ImageLoadResult

    /** 文件存在但压缩后仍超过上传上限（提示更换更小的图片） */
    data object TooLarge : ImageLoadResult
}

/**
 * 从本地图片文件路径读取字节与 MIME（Android 侧实现细节；JVM 单测用 [FileBytesReader] 替身）。
 */
fun interface FileBytesReader {

    /** 读取文件全部字节（失败返回 null） */
    fun readBytes(path: String): ByteArray?
}

/** 依据文件扩展名推断 MIME 类型（默认 JPEG，兼容相册选出的 png/webp） */
fun mimeTypeOf(path: String): String = when (File(path).extension.lowercase()) {
    "png" -> OcrImage.MIME_TYPE_PNG
    "webp" -> "image/webp"
    else -> OcrImage.MIME_TYPE_JPEG
}

/** MIME → 文件扩展名（落盘命名用） */
fun extensionOf(mimeType: String): String = when (mimeType) {
    OcrImage.MIME_TYPE_PNG -> ".png"
    "image/webp" -> ".webp"
    else -> ".jpg"
}