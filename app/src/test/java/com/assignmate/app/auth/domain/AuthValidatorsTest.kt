package com.assignmate.app.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AuthValidators 纯函数单测：账号/密码/确认密码/姓名/验证码/学生数量上限各分支覆盖。
 */
class AuthValidatorsTest {

    // ---- 家长账号 ----

    @Test
    fun `合法家长账号通过校验`() {
        assertNull(AuthValidators.parentAccountError("parent@test.com"))
        assertNull(AuthValidators.parentAccountError("13800138000"))
    }

    @Test
    fun `空白家长账号返回 EMPTY`() {
        assertEquals(
            ParentAccountError.EMPTY,
            AuthValidators.parentAccountError("   "),
        )
    }

    @Test
    fun `过短账号返回 TOO_SHORT`() {
        assertEquals(
            ParentAccountError.TOO_SHORT,
            AuthValidators.parentAccountError("ab"),
        )
    }

    @Test
    fun `过长账号返回 TOO_LONG`() {
        assertEquals(
            ParentAccountError.TOO_LONG,
            AuthValidators.parentAccountError("a".repeat(65)),
        )
    }

    @Test
    fun `含空白字符账号返回 CONTAINS_WHITESPACE`() {
        assertEquals(
            ParentAccountError.CONTAINS_WHITESPACE,
            AuthValidators.parentAccountError("parent 1"),
        )
    }

    @Test
    fun `账号校验自动去除首尾空白`() {
        assertNull(AuthValidators.parentAccountError("  parent@test.com  "))
    }

    // ---- 密码 ----

    @Test
    fun `合法密码通过校验`() {
        assertNull(AuthValidators.passwordError("123456"))
    }

    @Test
    fun `空密码返回 EMPTY`() {
        assertEquals(PasswordError.EMPTY, AuthValidators.passwordError(" "))
    }

    @Test
    fun `过短密码返回 TOO_SHORT`() {
        assertEquals(PasswordError.TOO_SHORT, AuthValidators.passwordError("12345"))
    }

    @Test
    fun `过长密码返回 TOO_LONG`() {
        assertEquals(PasswordError.TOO_LONG, AuthValidators.passwordError("a".repeat(33)))
    }

    // ---- 确认密码 ----

    @Test
    fun `确认密码为空返回 EMPTY`() {
        assertEquals(
            ConfirmPasswordError.EMPTY,
            AuthValidators.confirmPasswordError("", "123456"),
        )
    }

    @Test
    fun `两次密码不一致返回 NOT_MATCH`() {
        assertEquals(
            ConfirmPasswordError.NOT_MATCH,
            AuthValidators.confirmPasswordError("123457", "123456"),
        )
    }

    @Test
    fun `两次密码一致通过校验`() {
        assertNull(AuthValidators.confirmPasswordError("123456", "123456"))
    }

    // ---- 学生姓名 ----

    @Test
    fun `合法学生姓名通过校验`() {
        assertNull(AuthValidators.studentNameError("小明"))
    }

    @Test
    fun `空姓名返回 EMPTY`() {
        assertEquals(StudentNameError.EMPTY, AuthValidators.studentNameError("  "))
    }

    @Test
    fun `超长姓名返回 TOO_LONG`() {
        assertEquals(StudentNameError.TOO_LONG, AuthValidators.studentNameError("名".repeat(21)))
    }

    // ---- 进入验证码 ----

    @Test
    fun `四到六位纯数字验证码通过校验`() {
        assertNull(AuthValidators.verificationCodeError("1234"))
        assertNull(AuthValidators.verificationCodeError("12345"))
        assertNull(AuthValidators.verificationCodeError("123456"))
    }

    @Test
    fun `空验证码返回 EMPTY`() {
        assertEquals(VerificationCodeError.EMPTY, AuthValidators.verificationCodeError(""))
    }

    @Test
    fun `含非数字字符验证码返回 NOT_NUMERIC`() {
        assertEquals(VerificationCodeError.NOT_NUMERIC, AuthValidators.verificationCodeError("12a4"))
    }

    @Test
    fun `含Unicode数字验证码返回 NOT_NUMERIC`() {
        // 阿拉伯-印度数字（Char.isDigit 会放行，现按 ASCII 数字拒绝）
        assertEquals(
            VerificationCodeError.NOT_NUMERIC,
            AuthValidators.verificationCodeError("١٢٣٤"),
        )
        // 全角数字
        assertEquals(
            VerificationCodeError.NOT_NUMERIC,
            AuthValidators.verificationCodeError("１２３４"),
        )
    }

    @Test
    fun `位数越界数字验证码返回 LENGTH`() {
        assertEquals(VerificationCodeError.LENGTH, AuthValidators.verificationCodeError("123"))
        assertEquals(VerificationCodeError.LENGTH, AuthValidators.verificationCodeError("1234567"))
    }

    // ---- 学生数量上限 ----

    @Test
    fun `未达上限允许新增`() {
        assertTrue(AuthValidators.canAddStudent(0))
        assertTrue(AuthValidators.canAddStudent(AuthConstants.MAX_STUDENTS - 1))
    }

    @Test
    fun `达到或超过上限禁止新增`() {
        assertFalse(AuthValidators.canAddStudent(AuthConstants.MAX_STUDENTS))
        assertFalse(AuthValidators.canAddStudent(AuthConstants.MAX_STUDENTS + 1))
    }
}