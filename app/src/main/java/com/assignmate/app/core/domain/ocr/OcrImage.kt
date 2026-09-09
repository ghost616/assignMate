package com.assignmate.app.core.domain.ocr

import java.util.Base64

/**
 * 待识别图片（领域模型）：携带图片字节与 MIME 类型。
 *
 * 云端识别实现经 [toBase64] 将图片编码为 base64 后拼接 data URL 发送给多模态大模型；
 * 本地暂存重试场景则保存本地路径（见 PendingOcrTask），不长期持有字节。
 */
data class OcrImage(
    val bytes: ByteArray,
    val mimeType: String = MIME_TYPE_JPEG,
    val name: String? = null,
) {

    /** 转 base64（用于多模态大模型 image_url data URL），Java 内置实现可在 JVM 单测中直接验证 */
    fun toBase64(): String = Base64.getEncoder().encodeToString(bytes)

    override fun equals(other: Any?): Boolean =
        other is OcrImage &&
            other.mimeType == mimeType &&
            other.name == name &&
            other.bytes.contentEquals(bytes)

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val MIME_TYPE_JPEG = "image/jpeg"
        const val MIME_TYPE_PNG = "image/png"
    }
}
