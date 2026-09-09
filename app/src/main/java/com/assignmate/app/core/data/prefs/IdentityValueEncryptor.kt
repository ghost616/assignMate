package com.assignmate.app.core.data.prefs

import com.assignmate.app.core.domain.security.ValueEncryptor
import javax.inject.Inject

/**
 * 透传（明文）加密器——临时默认实现。
 *
 * TODO(limu): 后续以 Android Keystore（AES-GCM，密钥不出安全硬件）实现真正加解密并替换本类绑定；
 * 敏感键（如 OCR API 密钥）的存取路径已按 [ValueEncryptor] 接入，届时无需改动上层。
 * 约束：任何实现（含本透传实现）都不得把明文密钥写入日志。
 */
class IdentityValueEncryptor @Inject constructor() : ValueEncryptor {

    override fun encrypt(plainText: String): String = plainText

    override fun decrypt(cipherText: String): String = cipherText
}
