package com.assignmate.app.core.domain.prefs

/**
 * 主题档位（三档，供设置页选择）：跟随系统 / 护眼浅色 / 护眼夜间。
 *
 * 落盘存 [name]（枚举名，稳定标识，勿依赖 [label]）；读取时经 [fromRawValue] 解析，
 * 存量值非法或缺失一律兜底为 [DEFAULT]（跟随系统），避免历史脏数据导致主题不可用。
 *
 * @param label 中文档位文案（设置页直接展示，业务侧不必再自建文案映射）
 */
enum class ThemeMode(val label: String) {

    /** 跟随系统深浅色（默认档位） */
    SYSTEM("跟随系统"),

    /** 固定护眼浅色：低蓝光暖色浅色方案 */
    EYE_CARE_LIGHT("护眼浅色"),

    /** 固定护眼夜间：低蓝光暖棕暗色方案 */
    EYE_CARE_DARK("护眼夜间"),
    ;

    companion object {

        /** 默认档位（唯一来源）：跟随系统 */
        val DEFAULT: ThemeMode = SYSTEM

        /**
         * 解析落盘值：null、空串或无法识别的枚举名一律兜底 [DEFAULT]。
         *
         * 不抛异常：主题属于展示偏好，读到脏数据时「回到跟随系统」比崩溃/空白页更合理。
         */
        fun fromRawValue(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}