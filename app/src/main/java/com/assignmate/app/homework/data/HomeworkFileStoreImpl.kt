package com.assignmate.app.homework.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.assignmate.app.core.domain.ocr.OcrImage
import com.assignmate.app.core.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HomeworkFileStore] 的 Android 实现：目录固定在应用私有缓存下的 `homework/` 子目录。
 *
 * 存放位置取舍（为何用 cacheDir 而非 filesDir）：
 * 识别用图片属于「一次性中间产物」，识别成功即删除；仅待重试图片需短暂留存，
 * 放 cacheDir 可让系统在存储紧张时回收，避免占用用户可感知的存储配额。
 * 代价是极端情况下（系统清缓存）待重试图片可能丢失，此时重试链路会清理对应任务并提示重新拍照。
 *
 * 上限策略：单张图片压缩后超过 [MAX_UPLOAD_BYTES] 即返回 [ImageLoadResult.TooLarge]（提示换图）；
 * 残留文件由 [cleanupOrphanFiles] 按 TTL 回收。
 *
 * 命名：capture_/pending_ + UUID，避免同一毫秒内多次创建发生命名冲突（进程重启也不会撞名）。
 *
 * 注：FileProvider 与临时目录的清单/资源声明见 app/src/main/AndroidManifest.xml 与 res/xml/file_paths.xml。
 */
@Singleton
class HomeworkFileStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : HomeworkFileStore {

    private val root: File
        get() = File(context.cacheDir, DIRECTORY_NAME).apply { if (!exists()) mkdirs() }

    override fun createCaptureFile(): String {
        val file = File(root, "capture_${UUID.randomUUID()}$EXTENSION_JPEG")
        return file.absolutePath
    }

    override fun loadImage(path: String): ImageLoadResult {
        val file = File(path)
        if (!file.isFile) {
            return ImageLoadResult.Missing
        }
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return ImageLoadResult.Missing
        if (raw.isEmpty()) {
            return ImageLoadResult.Missing
        }
        val compressed = compress(raw) ?: return ImageLoadResult.Missing
        if (compressed.size > MAX_UPLOAD_BYTES) {
            return ImageLoadResult.TooLarge
        }
        // 压缩统一输出 JPEG（原图 png/webp 亦转为 JPEG），故 MIME 与命名口径一致
        return ImageLoadResult.Loaded(
            OcrImage(bytes = compressed, mimeType = OcrImage.MIME_TYPE_JPEG, name = file.name),
        )
    }

    override fun savePendingImage(bytes: ByteArray, mimeType: String): String? {
        if (bytes.isEmpty()) {
            return null
        }
        val file = File(root, "pending_${UUID.randomUUID()}${extensionOf(mimeType)}")
        return runCatching {
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    override fun promoteCaptureFile(path: String, mimeType: String): String? {
        val source = File(path)
        if (!source.isFile) {
            return null
        }
        val target = File(root, "pending_${UUID.randomUUID()}${extensionOf(mimeType)}")
        return runCatching {
            if (source.renameTo(target)) {
                target.absolutePath
            } else {
                // 同目录重命名一般不会失败；失败时退回复制语义（由调用方负责删除原文件）
                source.copyTo(target, overwrite = true)
                target.absolutePath
            }
        }.getOrNull()
    }

    override fun delete(path: String) {
        runCatching { File(path).takeIf { it.isFile }?.delete() }
    }

    override fun exists(path: String): Boolean = File(path).isFile

    override fun cleanupOrphanFiles(referencedPaths: Set<String>, olderThanMillis: Long): Int {
        val threshold = clock.currentTimeMillis() - olderThanMillis
        val files = root.listFiles() ?: return 0
        var removed = 0
        files.forEach { file ->
            if (!file.isFile || file.absolutePath in referencedPaths) {
                return@forEach
            }
            if (file.lastModified() < threshold && runCatching { file.delete() }.getOrDefault(false)) {
                removed++
            }
        }
        return removed
    }

    /**
     * 上传前压缩：先按最长边 [MAX_DIMENSION] 采样缩放，再以 [JPEG_QUALITY] 质量编码为 JPEG。
     * 解码失败（非图片/损坏文件）返回 null，由上层按「无法读取图片」提示。
     */
    private fun compress(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeOf(bounds.outWidth, bounds.outHeight)
        }
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
        }.getOrNull() ?: return null
        val scaled = scaleToMaxDimension(decoded)
        return runCatching {
            ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        }.getOrNull()?.also {
            if (scaled !== decoded) {
                scaled.recycle()
            }
            decoded.recycle()
        }
    }

    /** 采样率：保证解码后的位图不超过最长边限制的 2 倍，避免解码阶段就占用过多内存 */
    private fun sampleSizeOf(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_DIMENSION) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    /** 等比缩放到最长边不超过 [MAX_DIMENSION]（已足够小则原样返回） */
    private fun scaleToMaxDimension(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) {
            return bitmap
        }
        val ratio = MAX_DIMENSION.toFloat() / longest
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private companion object {

        const val DIRECTORY_NAME = "homework"
        const val EXTENSION_JPEG = ".jpg"

        /** 上传前最长边上限（像素）：兼顾识别精度与请求体大小 */
        const val MAX_DIMENSION = 1600

        /** JPEG 压缩质量 */
        const val JPEG_QUALITY = 80

        /** 压缩后字节上限（4MB）：超过则由上层提示更换图片 */
        const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024
    }
}
