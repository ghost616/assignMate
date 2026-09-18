package com.assignmate.app.settings.domain

/**
 * OCR API 密钥掩码纯函数：密钥默认以掩码显示（如 `****abcd`），避免家长在公共场合录入/查看时被旁人看到全量凭据。
 *
 * 约定：
 * - 明文/掩码切换只发生在 UI 展示层，落盘值始终是明文输入（由 core 的 OcrConfigStore 负责加密）；
 * - 本对象**只做字符串变换**，不参与日志；调用方不得把明文密钥写入任何日志（见 [OcrLogSink] 约定）。
 */
object OcrApiKeyMask {

    /** 掩码字符：与「已隐藏」语义一致，且不会与常见密钥字符混淆 */
    const val MASK_CHAR: Char = '*'

    /** 保留可见的尾部字符数上限（超过该长度的密钥只显示末 4 位） */
    const val VISIBLE_TAIL_LENGTH: Int = 4

    /** 掩码前缀长度上限（密钥较长时固定 4 个掩码字符，保证展示紧凑） */
    private const val MAX_MASK_PREFIX_LENGTH: Int = 4

    /**
     * 生成掩码文案（口径固定，便于 UI 与测试共用）：
     * - 空串 -> 空串（不伪造 `****` 之类的凭据，空密钥由「请填写 API 密钥」校验提示）；
     * - 长度 ≤ [VISIBLE_TAIL_LENGTH] -> **整串掩码**（短密钥一旦露出尾巴就泄露了大部分内容）；
     * - 其他 -> 最大 [MAX_MASK_PREFIX_LENGTH] 个掩码字符 + 末 [VISIBLE_TAIL_LENGTH] 位明文，
     *   如 `sk-abcdefgh` -> `****efgh`（长度恒 ≤ 8，不随密钥变长而变长）。
     *
     * 调用方传入的应是**去除首尾空白后**的密钥（与保存口径一致）。
     */
    fun mask(apiKey: String): String {
        if (apiKey.isEmpty()) {
            return ""
        }
        if (apiKey.length <= VISIBLE_TAIL_LENGTH) {
            return MASK_CHAR.toString().repeat(apiKey.length)
        }
        val hidden = minOf(apiKey.length - VISIBLE_TAIL_LENGTH, MAX_MASK_PREFIX_LENGTH)
        return MASK_CHAR.toString().repeat(hidden) + apiKey.takeLast(VISIBLE_TAIL_LENGTH)
    }
}