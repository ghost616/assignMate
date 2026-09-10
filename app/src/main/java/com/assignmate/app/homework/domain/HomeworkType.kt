package com.assignmate.app.homework.domain

/**
 * 作业类型：当天作业与阶段作业。
 *
 * 持久化约定：core 的 homework_item.type 列以本枚举 name 字符串存储
 * （见 HomeworkItemEntity 注释），便于后续新增取值而不必迁移表结构。
 *
 * 业务含义：
 * - [TODAY] 当天作业，可不设截止时间（deadline 可空）；
 * - [STAGE] 阶段作业，必须带 [StageRange]；家长录入的阶段作业必须设截止时间。
 */
enum class HomeworkType {

    /** 当天作业：今天就完成的作业，deadline 可空 */
    TODAY,

    /** 阶段作业：跨若干天/周完成，必须带阶段范围 */
    STAGE,
    ;

    /** 用户可读的中文标签 */
    val label: String
        get() = when (this) {
            TODAY -> "当天作业"
            STAGE -> "阶段作业"
        }

    companion object {

        /** 字符串安全解析（null/未知取值均返回 null），避免库内脏值导致崩溃 */
        fun fromName(name: String?): HomeworkType? =
            name?.let { raw -> entries.firstOrNull { it.name == raw } }
    }
}