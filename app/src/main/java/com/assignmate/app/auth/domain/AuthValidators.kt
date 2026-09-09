package com.assignmate.app.auth.domain

/**
 * auth 关键输入校验纯函数：无副作用、集中可单测。
 *
 * 约定：校验函数返回“首个命中的错误枚举”，返回 null 表示合法；
 * 账号/姓名/验证码统一先 trim 再校验（家长账号另由仓库层统一转小写后规范化入库，
 * 因此长度/空白判定对规范化值成立）。
 * 错误枚举携带用户可读文案（userMessage），供 UI 直接提示。
 */
object AuthValidators {

    /** 家长账号格式校验：非空 + 长度区间 + 不含空白字符 */
    fun parentAccountError(account: String): ParentAccountError? {
        val value = account.trim()
        return when {
            value.isEmpty() -> ParentAccountError.EMPTY
            value.length < AuthConstants.MIN_PARENT_ACCOUNT_LENGTH ->
                ParentAccountError.TOO_SHORT
            value.length > AuthConstants.MAX_PARENT_ACCOUNT_LENGTH ->
                ParentAccountError.TOO_LONG
            value.any { it.isWhitespace() } -> ParentAccountError.CONTAINS_WHITESPACE
            else -> null
        }
    }

    /** 密码校验：非空 + 长度区间（不 trim，保留用户原输入语义） */
    fun passwordError(password: String): PasswordError? =
        when {
            password.isBlank() -> PasswordError.EMPTY
            password.length < AuthConstants.MIN_PASSWORD_LENGTH -> PasswordError.TOO_SHORT
            password.length > AuthConstants.MAX_PASSWORD_LENGTH -> PasswordError.TOO_LONG
            else -> null
        }

    /** 确认密码校验：非空 + 与密码一致 */
    fun confirmPasswordError(confirm: String, password: String): ConfirmPasswordError? =
        when {
            confirm.isBlank() -> ConfirmPasswordError.EMPTY
            confirm != password -> ConfirmPasswordError.NOT_MATCH
            else -> null
        }

    /** 学生姓名校验：非空 + 长度上限（入库前 trim） */
    fun studentNameError(name: String): StudentNameError? {
        val value = name.trim()
        return when {
            value.isEmpty() -> StudentNameError.EMPTY
            value.length > AuthConstants.MAX_STUDENT_NAME_LENGTH -> StudentNameError.TOO_LONG
            else -> null
        }
    }

    /** 进入验证码校验：4-6 位纯数字（仅接受 ASCII '0'-'9'，杜绝 Unicode 数字绕过） */
    fun verificationCodeError(code: String): VerificationCodeError? {
        val value = code.trim()
        if (value.isEmpty()) {
            return VerificationCodeError.EMPTY
        }
        if (value.any { it !in '0'..'9' }) {
            return VerificationCodeError.NOT_NUMERIC
        }
        return if (value.length < AuthConstants.VERIFICATION_CODE_MIN_LENGTH ||
            value.length > AuthConstants.VERIFICATION_CODE_MAX_LENGTH
        ) {
            VerificationCodeError.LENGTH
        } else {
            null
        }
    }

    /** 是否允许继续新增学生（当前人数未达上限） */
    fun canAddStudent(currentCount: Int): Boolean =
        currentCount in 0 until AuthConstants.MAX_STUDENTS
}

/** 家长账号格式错误类型（携带用户可读文案） */
enum class ParentAccountError(val userMessage: String) {
    EMPTY("请输入家长账号"),
    TOO_SHORT("账号至少 ${AuthConstants.MIN_PARENT_ACCOUNT_LENGTH} 个字符"),
    TOO_LONG("账号不能超过 ${AuthConstants.MAX_PARENT_ACCOUNT_LENGTH} 个字符"),
    CONTAINS_WHITESPACE("账号不能包含空格"),
}

/** 密码错误类型（携带用户可读文案） */
enum class PasswordError(val userMessage: String) {
    EMPTY("请输入密码"),
    TOO_SHORT("密码至少 ${AuthConstants.MIN_PASSWORD_LENGTH} 位"),
    TOO_LONG("密码不能超过 ${AuthConstants.MAX_PASSWORD_LENGTH} 位"),
}

/** 确认密码错误类型（携带用户可读文案） */
enum class ConfirmPasswordError(val userMessage: String) {
    EMPTY("请再次输入密码"),
    NOT_MATCH("两次输入的密码不一致"),
}

/** 学生姓名错误类型（携带用户可读文案） */
enum class StudentNameError(val userMessage: String) {
    EMPTY("请输入学生姓名"),
    TOO_LONG("学生姓名不能超过 ${AuthConstants.MAX_STUDENT_NAME_LENGTH} 个字符"),
}

/** 进入验证码错误类型（携带用户可读文案） */
enum class VerificationCodeError(val userMessage: String) {
    EMPTY("请输入验证码"),
    NOT_NUMERIC("验证码需为数字"),
    LENGTH("验证码为 ${AuthConstants.VERIFICATION_CODE_MIN_LENGTH}-" +
        "${AuthConstants.VERIFICATION_CODE_MAX_LENGTH} 位数字"),
}