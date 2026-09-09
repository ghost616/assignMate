package com.assignmate.app.auth.domain

import java.time.Instant

/**
 * 学生档案领域模型（data 层与 student 表互转）。
 *
 * [verificationCode] 为学生端“家长账号 + 验证码”进入校验码，
 * 展示给家长后仅由家长重置/修改，学生本人不感知修改过程。
 */
data class Student(
    val id: Long,
    val parentAccountId: Long,
    val name: String,
    val verificationCode: String,
    val createdAt: Instant,
)