package com.assignmate.app.auth.domain

import kotlin.random.Random

/**
 * 进入验证码生成器：纯数字验证码（默认 6 位，位于 4-6 位合法区间）。
 * 与家长已有验证码的冲突规避（同家长内唯一）由仓库层基于本生成器循环完成。
 */
object VerificationCodeGenerator {

    private val random = Random.Default

    /** 生成 [length] 位纯数字验证码（默认见 AuthConstants.GENERATED_VERIFICATION_CODE_LENGTH） */
    fun generateCode(length: Int = AuthConstants.GENERATED_VERIFICATION_CODE_LENGTH): String {
        require(length in 1..9) { "验证码位数需在 1-9 之间" }
        return buildString {
            repeat(length) { append(random.nextInt(10)) }
        }
    }
}