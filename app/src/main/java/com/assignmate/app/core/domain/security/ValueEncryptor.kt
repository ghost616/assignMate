package com.assignmate.app.core.domain.security

/**
 * 敏感值加解密抽象（预留 Keystore 加密方案接口）。
 *
 * 当前默认实现为透传（明文落盘，见 core.data.prefs.IdentityValueEncryptor），
 * 并已在 OCR 配置存储等调用方按本接口接入；后续替换为 Android Keystore（AES-GCM，密钥不出安全硬件）
 * 或加密 DataStore 实现即可，上层存取路径无需改动。任何实现都不允许把明文密钥写入日志。
 */
interface ValueEncryptor {

    /** 加密：明文 -> 密文（空字符串应原样返回，避免无密钥场景落盘噪声） */
    fun encrypt(plainText: String): String

    /** 解密：密文 -> 明文 */
    fun decrypt(cipherText: String): String
}
