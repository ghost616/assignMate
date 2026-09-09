package com.assignmate.app.core.domain.ocr

/**
 * OCR 识别结果（领域模型）：以密封类型显式表达成功/失败，杜绝“吞异常 + 空文本”式歧义。
 *
 * UI 层根据 [OcrResult.Failure.userMessage] 直接提示用户；
 * [OcrResult.Failure.isRetryable] 供“拍摄暂存、联网后重试”链路判断是否应登记待重试任务。
 */
sealed interface OcrResult {

    /** 识别成功：携带可编辑文本 */
    data class Success(
        val text: String,
        val language: String? = null,
    ) : OcrResult

    /** 识别失败：失败原因分类 + 用户可读文案 + 是否可重试 */
    sealed interface Failure : OcrResult {
        /** 面向用户的失败说明（如“需要联网”提示文案） */
        val userMessage: String

        /** 是否可通过重试恢复（网络类为 true；配置缺失/内容异常为 false） */
        val isRetryable: Boolean

        /** 识别功能未启用或厂商参数不完整：需先到设置页完善配置 */
        data class NotConfigured(
            override val userMessage: String = "识别服务未配置，请先在设置中开启并填写厂商参数",
        ) : Failure {
            override val isRetryable: Boolean = false
        }

        /** 网络不可用或请求失败：供 UI 提示“需要联网”，可登记待重试任务 */
        data class NetworkError(
            override val userMessage: String = "网络不可用，请联网后重试",
        ) : Failure {
            override val isRetryable: Boolean = true
        }

        /** 服务端返回错误（HTTP 非 2xx）：携带状态码便于定位 */
        data class ServiceError(
            val code: Int,
            override val userMessage: String,
        ) : Failure {
            override val isRetryable: Boolean = true
        }

        /** 服务端响应内容无法解析/识别结果为空 */
        data class ParseError(
            override val userMessage: String = "识别结果为空，请换一张更清晰的图片重试",
        ) : Failure {
            override val isRetryable: Boolean = false
        }

        /** 其他未知异常（不应吞掉，作为失败呈现） */
        data class Unknown(
            override val userMessage: String = "识别失败，请稍后重试",
            override val isRetryable: Boolean = false,
        ) : Failure
    }
}
