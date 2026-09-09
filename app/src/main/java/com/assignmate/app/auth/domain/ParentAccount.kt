package com.assignmate.app.auth.domain

import java.time.Instant

/**
 * 家长账号领域模型（data 层与 parent_account 表互转）。
 * 安全约定：领域层不携带密码明文/哈希，仅暴露业务所需字段。
 */
data class ParentAccount(
    val id: Long,
    val account: String,
    val createdAt: Instant,
)