package com.assignmate.app.homework.ui

import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrConfigStore
import com.assignmate.app.core.domain.ocr.OcrImage
import com.assignmate.app.core.domain.ocr.OcrResult
import com.assignmate.app.core.domain.ocr.OcrRecognizer
import com.assignmate.app.core.domain.ocr.PendingOcrRepository
import com.assignmate.app.core.domain.ocr.PendingOcrStatus
import com.assignmate.app.core.domain.ocr.PendingOcrTask
import com.assignmate.app.core.domain.time.Clock
import com.assignmate.app.homework.data.HomeworkFileStore
import com.assignmate.app.homework.data.ImageLoadResult
import com.assignmate.app.homework.domain.HomeworkConstants
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 图片识别编排：调用 core 的 [OcrRecognizer]（配置取自 [OcrConfigStore]），
 * 把失败分派为「可重试（图片转正为待重试图片并登记任务）」或「不可重试（提示后清理图片）」，
 * 并统一管理本地图片文件的生命周期（成功/彻底失败即删除，避免 cacheDir 无界增长）。
 *
 * 分派规则（对应 OcrResult.Failure）：
 * - [OcrResult.Failure.NetworkError] / [OcrResult.Failure.ServiceError]：可重试 → 登记 PENDING 任务；
 * - [OcrResult.Failure.NotConfigured]：**保持 PENDING 仅提示去设置页**——全新安装未配置时
 *   用户把图片交给我们，配置好服务后应能直接重试，不能把图片锁死在不可重试状态；
 * - [OcrResult.Failure.ParseError]：提示更换更清晰的图片 → 删除图片（换图重拍即可）；
 * - [OcrResult.Failure.Unknown]：通用失败提示 → 删除图片。
 *
 * 无副作用的纯逻辑（失败文案 [failureHint]、可重试判定 [shouldRegisterRetry]）
 * 与副作用（落盘/登记/清理）分离，便于单测。
 */
@Singleton
class HomeworkOcrHandler @Inject constructor(
    private val ocrRecognizer: OcrRecognizer,
    private val ocrConfigStore: OcrConfigStore,
    private val pendingOcrRepository: PendingOcrRepository,
    private val fileStore: HomeworkFileStore,
    private val clock: Clock,
) {

    /** 识别一张本地图片（相册选图或拍照临时文件），并按结果清理或转正该文件 */
    suspend fun recognizeLocalImage(path: String): OcrOutcome =
        when (val loaded = fileStore.loadImage(path)) {
            is ImageLoadResult.Loaded -> recognizeLocalImage(path, loaded.image)

            ImageLoadResult.Missing -> OcrOutcome.Failed(FILE_UNREADABLE_HINT, false)

            ImageLoadResult.TooLarge -> {
                // 压缩后仍超限：不适合上传，清理后提示更换图片
                fileStore.delete(path)
                OcrOutcome.Failed(SIZE_LIMIT_HINT, false)
            }
        }

    /** 图片读取走文件存储入口：识别图片字节（拍照后已在内存中的场景，无本地文件需清理） */
    suspend fun recognize(image: OcrImage): OcrOutcome =
        when (val result = recognizeImage(image)) {
            is OcrResult.Success -> OcrOutcome.Filled(result.text)

            is OcrResult.Failure -> dispatchFailure(result, image.bytes, image.mimeType, null)
        }

    /**
     * 批量重试待识别任务：处理 PENDING（含此前不可重试置为 FAILED 的任务，给用户可恢复的重试入口）。
     *
     * 每个任务的处理：
     * - 已超过 [HomeworkConstants.MAX_OCR_RETRY_COUNT]：自动清理任务与图片（避免永久滞留）；
     * - 图片丢失：清理任务并提示重新拍照；
     * - 识别成功：回填文本、清理任务与图片；
     * - 仍可重试失败：任务置回 PENDING 并累加重试计数；
     * - **未配置（[OcrOutcome.NeedsRetry.needsConfiguration]）：任务置回 PENDING 但
     *   不累加重试计数**——配置缺失是环境问题、不是任务无救，若累加则家长未配置期间反复
     *   重试会触顶触发清理从而丢掉用户图片（图片必须在配置补齐后仍可直接重试）；
     * - 不可重试失败：任务置 FAILED 并累加计数（下次仍可手动重试，计数超限后自动清理）。
     *
     * @return 重试汇总（成功条数 + 首条成功文本 + 提示信息 + 是否因未配置失败），供 UI 回填与提示；
     *         调用方据此把「未配置」提示按会话角色收敛（学生无权去设置页，见 UI 层引导分流）
     */
    suspend fun retryPendingTasks(): OcrRetrySummary {
        val tasks = pendingOcrRepository.loadByStatus(PendingOcrStatus.PENDING) +
            pendingOcrRepository.loadByStatus(PendingOcrStatus.FAILED)
        if (tasks.isEmpty()) {
            return OcrRetrySummary(
                attempted = 0,
                succeeded = 0,
                firstText = null,
                message = "没有待重试的识别任务",
            )
        }
        var succeeded = 0
        var firstText: String? = null
        var lastFailureHint: String? = null
        var cleaned = 0
        var needsConfiguration = false
        tasks.forEach { task ->
            if (task.retryCount >= HomeworkConstants.MAX_OCR_RETRY_COUNT) {
                // 重试次数超上限：任务无救，清理任务与图片，避免永久滞留与存储增长
                // （「未配置」失败不累加计数，故不会因反复重试触发这里而丢用户图片）
                pendingOcrRepository.remove(task.id)
                fileStore.delete(task.localImagePath)
                cleaned++
                return@forEach
            }
            pendingOcrRepository.updateStatus(task.id, PendingOcrStatus.PROCESSING, task.retryCount)
            if (!fileStore.exists(task.localImagePath)) {
                // 图片已丢失：任务无救，直接清理并计入失败
                pendingOcrRepository.remove(task.id)
                lastFailureHint = "有图片已失效，请重新拍照识别"
                return@forEach
            }
            when (val outcome = recognizeForRetry(task)) {
                is OcrOutcome.Filled -> {
                    succeeded++
                    if (firstText == null && outcome.text.isNotBlank()) {
                        firstText = outcome.text
                    }
                    pendingOcrRepository.updateStatus(task.id, PendingOcrStatus.SUCCEEDED, task.retryCount + 1)
                    pendingOcrRepository.remove(task.id)
                    fileStore.delete(task.localImagePath)
                }

                is OcrOutcome.NeedsRetry -> {
                    // 「未配置」不累加重试计数：配置缺失是环境问题、不是任务无救，
                    // 否则家长未配置期间反复重试会触顶触发清理，把用户图片删掉
                    // （与「未配置…图片保留、配置补齐后可直接重试」的约定冲突）。
                    val nextRetryCount = if (outcome.needsConfiguration) {
                        task.retryCount
                    } else {
                        task.retryCount + 1
                    }
                    pendingOcrRepository.updateStatus(task.id, PendingOcrStatus.PENDING, nextRetryCount)
                    lastFailureHint = outcome.message
                    // 未配置这一事实必须上抛，调用方才能按会话角色收敛提示文案
                    if (outcome.needsConfiguration) {
                        needsConfiguration = true
                    }
                }

                is OcrOutcome.Failed -> {
                    pendingOcrRepository.updateStatus(task.id, PendingOcrStatus.FAILED, task.retryCount + 1)
                    lastFailureHint = outcome.message
                    // 未配置会让整批重试都停在同一条「去设置页」文案上，需向调用方暴露以按角色收敛提示
                    if (outcome.needsConfiguration) {
                        needsConfiguration = true
                    }
                }
            }
        }
        val message = when {
            succeeded > 0 && succeeded == tasks.size -> "已识别 $succeeded 张待重试图片"
            succeeded > 0 -> "识别成功 $succeeded 张，其余仍失败：${lastFailureHint ?: "请稍后再试"}"
            cleaned == tasks.size -> "有 $cleaned 张图片重试次数过多已清理，请重新拍照识别"
            lastFailureHint != null -> lastFailureHint
            else -> "识别仍然失败，请稍后再试"
        }
        return OcrRetrySummary(
            attempted = tasks.size,
            succeeded = succeeded,
            firstText = firstText,
            message = message,
            needsConfiguration = needsConfiguration,
        )
    }

    /** 待重试任务数量（用于录入/清单页入口展示） */
    fun observePendingCount(): Flow<Int> = pendingOcrRepository.observePending().map { it.size }

    /** 清理无任务引用的残留图片（录入页启动时调用，回收上次未清理的文件） */
    suspend fun cleanupOrphanFiles(): Int {
        val referenced = (pendingOcrRepository.loadByStatus(PendingOcrStatus.PENDING) +
            pendingOcrRepository.loadByStatus(PendingOcrStatus.PROCESSING) +
            pendingOcrRepository.loadByStatus(PendingOcrStatus.FAILED))
            .mapTo(mutableSetOf()) { it.localImagePath }
        return fileStore.cleanupOrphanFiles(
            referencedPaths = referenced,
            olderThanMillis = HomeworkConstants.ORPHAN_IMAGE_TTL_MILLIS,
        )
    }

    // ---- 私有工具 ----

    /** 本地图片识别：成功或不可重试失败即删除图片；可重试失败则把已拍文件转正为待重试图片 */
    private suspend fun recognizeLocalImage(path: String, image: OcrImage): OcrOutcome =
        when (val result = recognizeImage(image)) {
            is OcrResult.Success -> {
                fileStore.delete(path)
                OcrOutcome.Filled(result.text)
            }

            is OcrResult.Failure -> dispatchFailure(result, image.bytes, image.mimeType, path)
        }

    /**
     * 重试链路专用识别：只做「识别 + 结果分类」，**不再次登记待重试任务**
     * （任务已存在，重复登记会产生重复项）。
     *
     * 注意 [OcrResult.Failure.NotConfigured]：在重试链路它同样属「可重试」（任务保持 PENDING），
     * 但必须把「未配置」标记随 [OcrOutcome.NeedsRetry.needsConfiguration] 一起带出，
     * 否则调用方无从判断该把提示按会话角色收敛（家长去设置页 / 学生请家长配置）。
     */
    private suspend fun recognizeForRetry(task: PendingOcrTask): OcrOutcome =
        when (val loaded = fileStore.loadImage(task.localImagePath)) {
            is ImageLoadResult.Loaded -> when (val result = recognizeImage(loaded.image)) {
                is OcrResult.Success -> OcrOutcome.Filled(result.text)
                is OcrResult.Failure -> if (shouldRegisterRetry(result)) {
                    OcrOutcome.NeedsRetry(
                        message = failureHint(result),
                        needsConfiguration = result is OcrResult.Failure.NotConfigured,
                    )
                } else {
                    OcrOutcome.Failed(failureHint(result), result is OcrResult.Failure.NotConfigured)
                }
            }

            ImageLoadResult.Missing -> OcrOutcome.Failed(FILE_UNREADABLE_HINT, false)
            ImageLoadResult.TooLarge -> OcrOutcome.Failed(SIZE_LIMIT_HINT, false)
        }

    /** 纯识别调用：读取最新配置并请求厂商识别，不做失败分派副作用 */
    private suspend fun recognizeImage(image: OcrImage): OcrResult {
        val config: OcrConfig = ocrConfigStore.config.first()
        return ocrRecognizer.recognize(image, config)
    }

    /**
     * 失败对应的界面结果（与是否登记待重试任务解耦）：
     * - 未配置识别服务：呈现"去设置页"失败提示（[OcrOutcome.Failed.needsConfiguration] = true），
     *   同时仍登记 PENDING 任务，配置补齐后可直接重试，不丢用户图片；
     * - 其它可重试失败（网络/服务端）：呈现"已保存待重试"提示；
     * - 不可重试失败（解析/未知）：仅提示。
     */
    private fun failureOutcomeOf(failure: OcrResult.Failure): OcrOutcome = when {
        failure is OcrResult.Failure.NotConfigured ->
            OcrOutcome.Failed(failureHint(failure), needsConfiguration = true)

        shouldRegisterRetry(failure) -> OcrOutcome.NeedsRetry(failureHint(failure))

        else -> OcrOutcome.Failed(failureHint(failure), needsConfiguration = false)
    }

    /**
     * 失败分派：可重试的失败登记待重试任务，并按 [failureOutcomeOf] 返回界面结果。
     *
     * 注意「登记任务」与「界面结果」是两件事：未配置识别服务时既要登记 PENDING（配置后可直接重试），
     * 又要向用户呈现"去设置页"的失败提示，因此结果文案由 [failureOutcomeOf] 统一决定。
     *
     * @param sourcePath 若来自拍照临时文件，优先「转正重命名」而非复制字节；
     *                   不可重试失败时该文件同样被清理，避免残留
     */
    private suspend fun dispatchFailure(
        failure: OcrResult.Failure,
        imageBytes: ByteArray,
        mimeType: String,
        sourcePath: String?,
    ): OcrOutcome {
        val outcome = failureOutcomeOf(failure)
        if (!shouldRegisterRetry(failure)) {
            sourcePath?.let { fileStore.delete(it) }
            return outcome
        }
        registerPendingTask(
            sourcePath = sourcePath,
            imageBytes = imageBytes,
            mimeType = mimeType,
        ) ?: run {
            sourcePath?.let { fileStore.delete(it) }
            return OcrOutcome.Failed(failureHint(failure), failure is OcrResult.Failure.NotConfigured)
        }
        return outcome
    }

    /** 登记待重试任务（图片转正优先，转正失败则复制落盘），返回可重试图片路径 */
    private suspend fun registerPendingTask(
        sourcePath: String?,
        imageBytes: ByteArray,
        mimeType: String,
    ): String? {
        val savedPath = if (sourcePath != null) {
            fileStore.promoteCaptureFile(sourcePath, mimeType)?.also {
                // 转正成功后原始临时文件已不存在（重命名），无需再删除
            } ?: fileStore.savePendingImage(imageBytes, mimeType)?.also {
                // 转正失败（少见）：复制落盘后清理原文件
                fileStore.delete(sourcePath)
            }
        } else {
            fileStore.savePendingImage(imageBytes, mimeType)
        }
        if (savedPath == null) {
            return null
        }
        pendingOcrRepository.add(
            PendingOcrTask(
                localImagePath = savedPath,
                createdAtMillis = clock.currentTimeMillis(),
                status = PendingOcrStatus.PENDING,
            ),
        )
        return savedPath
    }

    companion object {

        /** 无法读取图片时的提示 */
        const val FILE_UNREADABLE_HINT = "无法读取所选图片，请重新选择"

        /** 压缩后仍超出上传上限时的提示 */
        const val SIZE_LIMIT_HINT = "图片过大，压缩后仍无法上传，请更换一张图片"

        /**
         * 是否登记为待重试任务：网络/服务端类失败可恢复；未配置识别服务时同样登记为 PENDING
         * （仅提示去设置页配置，配置完成后可重试），只有内容异常与未知错误才直接丢弃图片。
         */
        fun shouldRegisterRetry(failure: OcrResult.Failure): Boolean = when (failure) {
            is OcrResult.Failure.NetworkError -> true
            is OcrResult.Failure.ServiceError -> true
            is OcrResult.Failure.NotConfigured -> true
            is OcrResult.Failure.ParseError -> false
            is OcrResult.Failure.Unknown -> false
        }

        /** 失败分派文案：按原因分类给出可读提示 */
        fun failureHint(failure: OcrResult.Failure): String = when (failure) {
            is OcrResult.Failure.NetworkError -> "需要联网才能识别图片，已保存待重试"
            is OcrResult.Failure.NotConfigured -> "识别服务未配置，请到设置页开启并填写厂商参数（图片已保存待重试）"
            is OcrResult.Failure.ParseError -> "没认出文字，请换一张更清晰的图片"
            is OcrResult.Failure.ServiceError -> "识别服务暂时不可用（${failure.code}），已保存待重试"
            is OcrResult.Failure.Unknown -> failure.userMessage
        }
    }
}

/** 单次识别结果（供 UI 消费） */
sealed interface OcrOutcome {

    /** 识别成功：携带可编辑文本，由 UI 回填内容输入框 */
    data class Filled(val text: String) : OcrOutcome

    /**
     * 可重试失败：图片已落盘并登记待重试任务。
     *
     * @property needsConfiguration 失败原因是「识别服务未配置」：提示需按会话角色分流
     *   （家长可被引导去设置页，学生只能请家长配置），调用方据此选择文案
     */
    data class NeedsRetry(
        val message: String,
        val needsConfiguration: Boolean = false,
    ) : OcrOutcome

    /** 不可重试失败：仅提示 */
    data class Failed(val message: String, val needsConfiguration: Boolean) : OcrOutcome
}

/** 批量重试汇总 */
data class OcrRetrySummary(
    val attempted: Int,
    val succeeded: Int,
    val firstText: String?,
    val message: String,
    /**
     * 本次重试是否因「识别服务未配置」失败：调用方据此把提示按会话角色收敛
     * （家长可被引导去设置页，学生只能请家长配置），未配置之外的失败不受影响。
     */
    val needsConfiguration: Boolean = false,
)
