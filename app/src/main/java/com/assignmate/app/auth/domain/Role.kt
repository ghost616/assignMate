package com.assignmate.app.auth.domain

/**
 * 当前会话角色：家长（PARENT）或学生（STUDENT）。
 *
 * 由 auth 模块维护并随会话持久化（KeyValueStore 存枚举 name），
 * 供 homework/timer/stats 等模块读取以决定各自界面形态与数据范围。
 */
enum class Role {

    /** 家长角色：维护学生档案、查看/管理作业 */
    PARENT,

    /** 学生角色：以“家长账号 + 验证码”进入，直达本人作业界面 */
    STUDENT;

    companion object {

        /** 字符串安全解析（未知/空串返回 null），避免存储脏值导致崩溃 */
        fun fromName(name: String?): Role? =
            name?.let { raw -> entries.firstOrNull { it.name == raw } }
    }
}