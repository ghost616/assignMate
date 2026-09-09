package com.assignmate.app.core.data.ocr.remote

import com.assignmate.app.core.domain.ocr.OcrConfig
import com.assignmate.app.core.domain.ocr.OcrImage
import com.assignmate.app.core.domain.ocr.OcrRecognizer
import com.assignmate.app.core.domain.ocr.OcrResult
import com.assignmate.app.core.domain.util.CoreConstants
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 基于多模态大模型 chat/completions 协议的云端 OCR 实现（厂商可配置）。
 *
 * 协议要点（OpenAI 兼容，国内厂商如通义/智谱/DeepSeek 的兼容接口同样适用）：
 * - 请求：POST {baseUrl}/chat/completions，鉴权头 Authorization: Bearer {apiKey}；
 * - content 采用多段结构：text（识别指令）+ image_url（图片 data URL，base64）；
 * - 响应：choices[0].message.content，兼容 String 与分段数组两种返回形态。
 *
 * 本实现与可注入 Clock 无关（不依赖时间源）；无网络/请求失败返回 [OcrResult.Failure.NetworkError]。
 */
class ChatCompletionsOcrRecognizer @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : OcrRecognizer {

    override suspend fun recognize(image: OcrImage, config: OcrConfig): OcrResult =
        withContext(Dispatchers.IO) {
            val issues = config.validationIssues()
            if (issues.isNotEmpty()) {
                return@withContext OcrResult.Failure.NotConfigured(
                    userMessage = issues.first().message,
                )
            }
            try {
                val url = "${config.apiBaseUrl.trimEnd('/')}/${CoreConstants.OCR_CHAT_COMPLETIONS_PATH}"
                val request = Request.Builder()
                    .url(url)
                    .header(AUTHORIZATION_HEADER, "Bearer ${config.apiKey}")
                    .post(buildRequestBody(image, config).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        OcrResult.Failure.ServiceError(
                            code = response.code,
                            userMessage = parseErrorMessage(body) ?: "识别服务异常（${response.code}）",
                        )
                    } else {
                        parseSuccess(body) ?: OcrResult.Failure.ParseError()
                    }
                }
            } catch (e: SocketTimeoutException) {
                OcrResult.Failure.NetworkError()
            } catch (e: IOException) {
                OcrResult.Failure.NetworkError()
            } catch (e: Exception) {
                // 兜底：统一收敛为失败结果、绝不外抛，且不把异常明细（可能含内部/服务端详情）透传给用户 UI，
                // 使用统一友好文案；如需诊断，应仅经 debug 日志输出，且禁止打印图片内容与鉴权密钥。
                OcrResult.Failure.Unknown()
            }
        }

    private fun buildRequestBody(image: OcrImage, config: OcrConfig): String {
        val dataUrl = "data:${image.mimeType};base64,${image.toBase64()}"
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", CoreConstants.OCR_RECOGNIZE_PROMPT))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl)),
            )
        val payload = JSONObject()
            .put("model", config.modelName)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", OCR_MAX_TOKENS)
        return payload.toString()
    }

    private fun parseSuccess(body: String): OcrResult.Success? = runCatching {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: return null
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        val text = extractText(message.opt("content"))
        if (text.isNullOrBlank()) null else OcrResult.Success(text = text.trim())
    }.getOrNull()

    /** 兼容 message.content 为 String 或分段数组两种形态 */
    private fun extractText(content: Any?): String? = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val text = content.optJSONObject(i)?.optString("text")
                if (!text.isNullOrBlank()) append(text)
            }
        }.ifBlank { "" }
        else -> null
    }

    /** 尽力从错误响应体中提取服务端说明；失败时返回 null 由调用方兜底文案 */
    private fun parseErrorMessage(body: String): String? = runCatching {
        val root = JSONObject(body)
        root.optString("message")
            .takeIf { it.isNotBlank() }
            ?: root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val OCR_MAX_TOKENS = 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
