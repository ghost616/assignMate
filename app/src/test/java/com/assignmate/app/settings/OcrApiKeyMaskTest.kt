package com.assignmate.app.settings

import com.assignmate.app.settings.domain.OcrApiKeyMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API 密钥掩码转换单测：密钥默认掩码显示（如 `****abcd`），且**任何长度都不泄露大部分内容**。
 *
 * 覆盖分支：空串（不伪造凭据）、极短（整串掩码）、临界长度 4/5（掩码前缀 + 末四位）、超长（掩码长度恒定）。
 */
class OcrApiKeyMaskTest {

    @Test
    fun `空密钥不伪造掩码文案`() {
        assertEquals("", OcrApiKeyMask.mask(""))
    }

    @Test
    fun `长度不超过四的短密钥整串掩码且不保留明文尾部`() {
        assertEquals("*", OcrApiKeyMask.mask("a"))
        assertEquals("**", OcrApiKeyMask.mask("ab"))
        assertEquals("***", OcrApiKeyMask.mask("abc"))
        assertEquals("****", OcrApiKeyMask.mask("abcd"))
    }

    @Test
    fun `临界长度五起保留末四位且掩码前缀一位`() {
        assertEquals("*bcde", OcrApiKeyMask.mask("abcde"))
        assertEquals("**cdef", OcrApiKeyMask.mask("abcdef"))
        assertEquals("***defg", OcrApiKeyMask.mask("abcdefg"))
    }

    @Test
    fun `常规密钥保留末四位且前缀固定四位掩码`() {
        assertEquals("****efgh", OcrApiKeyMask.mask("abcdefgh"))
        assertEquals("****abcd", OcrApiKeyMask.mask("sk-xxxxxxxxabcd"))
        assertEquals(8, OcrApiKeyMask.mask("sk-xxxxxxxxabcd").length)
    }

    @Test
    fun `超长密钥掩码长度恒为八不随密钥变长`() {
        val longKey = "sk-" + "x".repeat(200) + "wxyz"

        val masked = OcrApiKeyMask.mask(longKey)

        assertEquals("****wxyz", masked)
        assertFalse("掩码不得包含密钥中间片段", masked.contains("xxxx"))
    }

    @Test
    fun `掩码结果不含完整密钥且末四位之外不可见`() {
        val key = "sk-proj-1234567890secret-9f8e7d6c"
        val masked = OcrApiKeyMask.mask(key)

        assertFalse(masked == key)
        assertTrue(masked.endsWith("7d6c"))
        assertFalse(masked.contains("secret"))
        assertEquals(key.takeLast(4), masked.takeLast(4))
    }

    @Test
    fun `掩码常量与字符固定`() {
        assertEquals('*', OcrApiKeyMask.MASK_CHAR)
        assertEquals(4, OcrApiKeyMask.VISIBLE_TAIL_LENGTH)
    }

    @Test
    fun `任意长度密钥掩码后明文可见字符不超过四位`() {
        val keys = listOf("a", "ab", "abc", "abcd", "abcde", "abcdefgh", "sk-" + "y".repeat(120))

        keys.forEach { key ->
            val masked = OcrApiKeyMask.mask(key)
            val revealed = masked.count { it != OcrApiKeyMask.MASK_CHAR }

            assertTrue("密钥 [$key] 掩码后泄露了 $revealed 个明文字符", revealed <= 4)
            assertTrue("掩码长度不得超过 8", masked.length <= 8)
        }
    }
}