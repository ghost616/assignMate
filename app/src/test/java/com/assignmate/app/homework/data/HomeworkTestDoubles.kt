package com.assignmate.app.homework.data

import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrImage
import com.assignmate.app.core.domain.ocr.OcrRecognizer
import com.assignmate.app.core.domain.ocr.OcrResult
import com.assignmate.app.core.domain.ocr.PendingOcrRepository
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * homework 录入链路的测试替身集合：文件存储、OCR 识别、待重试任务仓库。
 * 全部为纯 JVM 实现（不依赖 Android），保证 OCR 失败分派、待重试登记/清理可确定性单测。
 */

/**
 * 内存/临时目录版文件存储：真实读写临时文件，验证「落盘 → 读回 → 删除/转正」链路。
 *
 * 压缩在真实实现中依赖 Android Bitmap，此处以「字节原样」替代，
 * 并支持通过 [loadResults] 注入 [ImageLoadResult]（用于验证 TooLarge / Missing 分支）。
 */
class FakeHomeworkFileStore(private val root: File) : HomeworkFileStore {

    init {
        if (!root.exists()) {
            root.mkdirs()
        }
    }

    private var counter = 0

    /** 依路径覆盖 loadImage 的返回结果（用于构造 TooLarge / Missing 场景） */
    val loadResults = mutableMapOf<String, ImageLoadResult>()

    /** 图片读取调用次数（验证读取确实经文件存储） */
    var loadCallCount: Int = 0
        private set

    /** 转正（重命名）调用次数（验证可重试失败走「转正」而非复制） */
    var promoteCallCount: Int = 0
        private set

    override fun createCaptureFile(): String {
        val file = File(root, "capture_${counter++}.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file.absolutePath
    }

    override fun loadImage(path: String): ImageLoadResult {
        loadCallCount++
        loadResults[path]?.let { return it }
        val file = File(path)
        if (!file.isFile) {
            return ImageLoadResult.Missing
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return ImageLoadResult.Missing
        if (bytes.isEmpty()) {
            return ImageLoadResult.Missing
        }
        return ImageLoadResult.Loaded(
            OcrImage(bytes = bytes, mimeType = mimeTypeOf(path), name = file.name),
        )
    }

    override fun savePendingImage(bytes: ByteArray, mimeType: String): String? {
        if (bytes.isEmpty()) {
            return null
        }
        val file = File(root, "pending_${counter++}.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    override fun promoteCaptureFile(path: String, mimeType: String): String? {
        promoteCallCount++
        val source = File(path)
        if (!source.isFile) {
            return null
        }
        val target = File(root, "pending_promoted_${counter++}.jpg")
        return if (source.renameTo(target)) target.absolutePath else null
    }

    override fun delete(path: String) {
        File(path).takeIf { it.isFile }?.delete()
    }

    override fun exists(path: String): Boolean = File(path).isFile

    override fun cleanupOrphanFiles(referencedPaths: Set<String>, olderThanMillis: Long): Int {
        var removed = 0
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath !in referencedPaths &&
                runCatching { file.delete() }.getOrDefault(false)
            ) {
                removed++
            }
        }
        return removed
    }

    /** 测试辅助：目录下现存文件数（验证清理是否彻底） */
    fun fileCount(): Int = root.listFiles()?.count { it.isFile } ?: 0

    /** 测试辅助：目录下现存文件名列表 */
    fun fileNames(): List<String> =
        root.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
}

/** OCR 识别替身：按预设脚本返回成功/失败，并记录调用次数与最近一次输入 */
class FakeOcrRecognizer(private val script: MutableList<OcrResult> = mutableListOf()) : OcrRecognizer {

    var callCount: Int = 0
        private set
    var lastImage: OcrImage? = null
        private set
    var lastConfig: OcrConfig? = null
        private set

    /** 追加一次识别结果：严格按调用顺序先进先出消费，保证多次调用结果可预期 */
    fun enqueue(result: OcrResult) {
        script += result
    }

    override suspend fun recognize(image: OcrImage, config: OcrConfig): OcrResult {
        callCount++
        lastImage = image
        lastConfig = config
        if (script.isEmpty()) {
            return OcrResult.Success(text = "默认识别文本")
        }
        return script.removeAt(0)
    }
}

/** 待重试 OCR 任务仓库替身（内存实现，行为与 Room 实现一致） */
class FakePendingOcrRepository : PendingOcrRepository {

    private val rows = mutableListOf<PendingOcrTask>()
    private var nextId = 1L
    private val revision = MutableStateFlow(0)

    override suspend fun add(task: PendingOcrTask): Long {
        val id = nextId++
        rows += task.copy(id = id)
        revision.value += 1
        return id
    }

    override fun observePending(): Flow<List<PendingOcrTask>> =
        revision.map {
            rows.filter {
                it.status == PendingOcrStatus.PENDING || it.status == PendingOcrStatus.PROCESSING
            }
        }

    override suspend fun loadByStatus(status: PendingOcrStatus): List<PendingOcrTask> =
        rows.filter { it.status == status }.sortedBy { it.createdAtMillis }

    override suspend fun updateStatus(id: Long, status: PendingOcrStatus, retryCount: Int) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(status = status, retryCount = retryCount)
            revision.value += 1
        }
    }

    override suspend fun remove(id: Long) {
        rows.removeAll { it.id == id }
        revision.value += 1
    }

    /** 测试断言辅助：全部任务快照 */
    fun all(): List<PendingOcrTask> = rows.toList()
}
