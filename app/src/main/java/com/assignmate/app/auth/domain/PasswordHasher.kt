package com.assignmate.app.auth.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 本地密码哈希工具（PBKDF2WithHmacSHA256 + 随机盐）。
 *
 * 安全约定：
 * - 只存哈希不落明文；盐随机生成，与迭代次数一并内嵌于存储串；
 * - 校验用常量时间比较（MessageDigest.isEqual）避免时序侧信道；
 * - 存储格式 "v1:盐Base64:迭代次数:哈希Base64"，纯函数便于单测。
 */
object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val VERSION = "v1"
    private const val ITERATIONS = 10_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private const val DELIMITER = ":"

    private val secureRandom = SecureRandom()

    /** 对明文密码计算加盐哈希（每次随机盐，结果不重复） */
    fun hash(password: String): String {
        require(password.isNotEmpty()) { "密码不允许为空" }
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val derived = pbkdf2(password, salt, ITERATIONS, KEY_LENGTH_BITS)
        return listOf(
            VERSION,
            encode(salt),
            ITERATIONS.toString(),
            encode(derived),
        ).joinToString(DELIMITER)
    }

    /** 校验明文密码是否与存储哈希匹配（存储串非法时返回 false） */
    fun verify(password: String, storedHash: String): Boolean {
        val parts = storedHash.split(DELIMITER)
        if (parts.size != 4 || parts[0] != VERSION) {
            return false
        }
        return try {
            val salt = decode(parts[1])
            val iterations = parts[2].toInt()
            val expected = decode(parts[3])
            val actual = pbkdf2(password, salt, iterations, KEY_LENGTH_BITS)
            MessageDigest.isEqual(actual, expected)
        } catch (exception: IllegalArgumentException) {
            // 存储串损坏（非法 Base64/迭代数）按不匹配处理，不向上抛
            false
        }
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyLengthBits: Int): ByteArray =
        try {
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            // 不保留明文副本（构造器参数由 GC 回收，此处无可清理句柄）
        }

    private fun encode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray =
        Base64.getDecoder().decode(text)
}