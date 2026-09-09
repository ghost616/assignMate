package com.assignmate.app.auth.ui

import com.assignmate.app.auth.data.AddStudentFailure
import com.assignmate.app.auth.data.DeleteStudentFailure
import com.assignmate.app.auth.data.ParentLoginFailure
import com.assignmate.app.auth.data.ParentRegisterFailure
import com.assignmate.app.auth.data.RenameStudentFailure
import com.assignmate.app.auth.data.StudentEnterFailure
import com.assignmate.app.auth.data.UpdateVerificationCodeFailure
import com.assignmate.app.auth.domain.AuthConstants

/**
 * 仓库失败原因（reason 枚举）→ 用户可读文案的单一映射点。
 * 约束：机器逻辑（Reason）与文案分离，UI 层统一经本文件转换，禁止散落硬编码。
 * 登录失败故意合并“账号不存在/密码错误”为同一文案，避免泄露账号是否注册。
 */
internal fun ParentRegisterFailure.toUserMessage(): String =
    when (this) {
        ParentRegisterFailure.ACCOUNT_ALREADY_EXISTS -> "该账号已被注册，请直接登录"
        ParentRegisterFailure.INVALID_ACCOUNT -> "账号格式不正确"
        ParentRegisterFailure.INVALID_PASSWORD -> "密码不符合要求"
    }

internal fun ParentLoginFailure.toUserMessage(): String =
    when (this) {
        ParentLoginFailure.ACCOUNT_NOT_FOUND, ParentLoginFailure.WRONG_PASSWORD ->
            "账号或密码不正确，请重试"

        ParentLoginFailure.INVALID_ACCOUNT -> "账号格式不正确"
        ParentLoginFailure.INVALID_PASSWORD -> "密码不符合要求"
    }

internal fun StudentEnterFailure.toUserMessage(): String =
    when (this) {
        StudentEnterFailure.PARENT_ACCOUNT_NOT_FOUND -> "家长账号不存在，请核对后重试"
        StudentEnterFailure.INVALID_VERIFICATION_CODE -> "验证码需为 4-6 位数字"
        StudentEnterFailure.CODE_MISMATCH -> "账号与验证码不匹配，请核对后重试"
    }

internal fun AddStudentFailure.toUserMessage(): String =
    when (this) {
        AddStudentFailure.STUDENT_LIMIT_REACHED ->
            "最多只能添加 ${AuthConstants.MAX_STUDENTS} 名学生"

        AddStudentFailure.INVALID_NAME -> "学生姓名不合法"
        AddStudentFailure.CODE_GENERATION_FAILED -> "验证码生成失败，请重试"
    }

internal fun RenameStudentFailure.toUserMessage(): String =
    when (this) {
        RenameStudentFailure.STUDENT_NOT_FOUND -> "学生不存在，可能已被删除"
        RenameStudentFailure.INVALID_NAME -> "学生姓名不合法"
    }

internal fun DeleteStudentFailure.toUserMessage(): String =
    when (this) {
        DeleteStudentFailure.STUDENT_NOT_FOUND -> "学生不存在，可能已被删除"
    }

internal fun UpdateVerificationCodeFailure.toUserMessage(): String =
    when (this) {
        UpdateVerificationCodeFailure.STUDENT_NOT_FOUND -> "学生不存在，可能已被删除"
        UpdateVerificationCodeFailure.INVALID_CODE -> "验证码需为 4-6 位数字"
        UpdateVerificationCodeFailure.CODE_ALREADY_USED -> "该验证码已被其他学生使用"
        UpdateVerificationCodeFailure.CODE_GENERATION_FAILED -> "验证码生成失败，请重试"
    }