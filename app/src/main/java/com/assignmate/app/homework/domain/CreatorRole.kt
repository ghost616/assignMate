package com.assignmate.app.homework.domain

import com.assignmate.app.auth.domain.Role

/**
 * 作业录入者角色：家长录入 / 学生录入。
 *
 * 与 auth 的会话角色 [Role] 分离：本枚举描述「某条作业最初由谁录入」
 * （持久化在 homework_item.created_by_role 列，不随会话变化），
 * 权限规则据此判定「学生仅可修改/删除自己新增的作业，不可动家长录入的作业」。
 */
enum class CreatorRole {

    /** 家长录入 */
    PARENT,

    /** 学生录入 */
    STUDENT,
    ;

    /** 用户可读的中文标签 */
    val label: String
        get() = when (this) {
            PARENT -> "家长录入"
            STUDENT -> "学生录入"
        }

    companion object {

        /** 字符串安全解析（null/未知取值均返回 null） */
        fun fromName(name: String?): CreatorRole? =
            name?.let { raw -> entries.firstOrNull { it.name == raw } }

        /** 由当前会话角色推导新作业的录入者角色 */
        fun fromSessionRole(role: Role): CreatorRole = when (role) {
            Role.PARENT -> PARENT
            Role.STUDENT -> STUDENT
        }
    }
}