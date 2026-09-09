package com.assignmate.app.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VerificationCodeGenerator 单测：位数、纯数字、合法性区间。
 */
class VerificationCodeGeneratorTest {

    @Test
    fun `默认生成为六位数字`() {
        val code = VerificationCodeGenerator.generateCode()
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `按指定位数生成纯数字`() {
        val code = VerificationCodeGenerator.generateCode(length = 4)
        assertEquals(4, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `生成结果在合法验证码格式内`() {
        repeat(50) {
            val code = VerificationCodeGenerator.generateCode()
            assertEquals(null, AuthValidators.verificationCodeError(code))
        }
    }
}