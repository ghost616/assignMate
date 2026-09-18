package com.assignmate.app.settings.ui

/**
 * settings 模块自有路由常量（framework 的 AssignMateNavHost 负责注册接线，本模块不直接改 NavHost）。
 *
 * 接线约定（供 framework 计划参考）：
 * - [HOME] 为设置主页：家长端由 auth 家长主界面的「⚙ 设置」入口进入；
 * - [OCR_CONFIG] 为 OCR 厂商配置页：仅家长会话可达（学生会话在页面层直接渲染拒绝态，见 OcrConfigRoute）；
 * - [THEME] 为护眼设置页（三档主题）：家长与学生均可访问（学生端由 auth 学生首页「🌙 护眼设置」入口进入）；
 * - 各 Route 组件的回调参数即导航意图，返回键回上一级由 framework 的 popBackStack 承接。
 */
object SettingsDestination {

    /** 设置主页（OCR 配置摘要 / 主题档位 / 数据清理） */
    const val HOME = "settings/home"

    /** OCR 厂商配置页（仅家长） */
    const val OCR_CONFIG = "settings/ocr_config"

    /** 护眼设置页（三档主题：跟随系统 / 护眼浅色 / 护眼夜间） */
    const val THEME = "settings/theme"
}