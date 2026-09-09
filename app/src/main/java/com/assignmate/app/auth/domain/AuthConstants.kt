package com.assignmate.app.auth.domain

/**
 * auth 业务规则常量集中收敛，禁止在实现层散落魔法值。
 * 规则同源：AuthValidators / 仓库实现 / UI 提示共用本组常量。
 */
object AuthConstants {

    /** 单个家长账号名下学生档案数量上限 */
    const val MAX_STUDENTS = 5

    // ---- 家长账号（登录账号）格式约束 ----
    const val MIN_PARENT_ACCOUNT_LENGTH = 4
    const val MAX_PARENT_ACCOUNT_LENGTH = 64

    // ---- 密码约束 ----
    const val MIN_PASSWORD_LENGTH = 6
    const val MAX_PASSWORD_LENGTH = 32

    // ---- 学生姓名约束 ----
    const val MAX_STUDENT_NAME_LENGTH = 20

    // ---- 进入验证码约束 ----
    const val VERIFICATION_CODE_MIN_LENGTH = 4
    const val VERIFICATION_CODE_MAX_LENGTH = 6

    /** 家长“重新生成”验证码的固定位数（位于 4-6 位合法区间内） */
    const val GENERATED_VERIFICATION_CODE_LENGTH = 6

    /** 生成验证码避免与同家长已有验证码冲突的最大尝试次数（防御性上限） */
    const val VERIFICATION_CODE_GENERATE_MAX_ATTEMPTS = 100
}