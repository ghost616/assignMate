package com.assignmate.app.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PasswordHasher 单测：哈希不与明文相同、加盐导致同密码哈希不同、
 * 校验正确/错误密码、损坏存储串安全返回 false。
 */
class PasswordHasherTest {

    @Test
    fun `哈希结果与明文不同且不包含明文`() {
        val hash = PasswordHasher.hash("pwd-123456")
        assertNotEquals("pwd-123456", hash)
        assertFalse(hash.contains("pwd-123456"))
    }

    @Test
    fun `相同密码因随机盐产生不同哈希`() {
        val first = PasswordHasher.hash("same-password")
        val second = PasswordHasher.hash("same-password")
        assertNotEquals(first, second)
    }

    @Test
    fun `存储格式为 v1_盐_迭代次数_哈希 四段`() {
        val parts = PasswordHasher.hash("pwd-123456").split(":")
        assertEquals(4, parts.size)
        assertEquals("v1", parts[0])
    }

    @Test
    fun `正确密码校验通过`() {
        val hash = PasswordHasher.hash("pwd-123456")
        assertTrue(PasswordHasher.verify("pwd-123456", hash))
    }

    @Test
    fun `错误密码校验失败`() {
        val hash = PasswordHasher.hash("pwd-123456")
        assertFalse(PasswordHasher.verify("wrong-password", hash))
    }

    @Test
    fun `损坏存储串返回 false 不抛异常`() {
        assertFalse(PasswordHasher.verify("any", "not-a-valid-stored-hash"))
        assertFalse(PasswordHasher.verify("any", "v1:!!!badbase64!!!:10000:xxxx"))
    }
}