package com.assignmate.app.auth.data

/**
 * 三方登录预留接口层（微信/QQ/支付宝等实现形态待定）。
 *
 * 当前阶段仅声明契约与“未接入”占位实现；接入具体 SDK 时新增实现类并通过
 * Hilt 绑定即可，UI 侧无需改动（按 [ThirdPartyAuthProvider.isSupported] 渲染置灰或点击态）。
 */
interface ThirdPartyAuthProvider {

    /** 所属三方渠道 */
    val channel: ThirdPartyChannel

    /** 渠道当前是否可用（false 时 UI 置灰并提示“即将上线”） */
    val isSupported: Boolean

    /** 发起三方登录（可用时调用）；当前占位实现一律返回 [ThirdPartyLoginResult.NotSupported] */
    suspend fun login(): ThirdPartyLoginResult
}

/** 三方登录渠道 */
enum class ThirdPartyChannel(val displayName: String) {

    /** 微信登录 */
    WECHAT("微信"),

    /** QQ 登录 */
    QQ("QQ"),

    /** 支付宝登录 */
    ALIPAY("支付宝"),
}

/** 三方登录结果 */
sealed class ThirdPartyLoginResult {

    /** 登录成功（预留：成功后需回调 auth 仓库建立家长会话） */
    data object Success : ThirdPartyLoginResult()

    /** 渠道未接入 */
    data object NotSupported : ThirdPartyLoginResult()

    /** 登录失败（携带用户可读文案） */
    data class Failure(val userMessage: String) : ThirdPartyLoginResult()
}

/**
 * 三方登录占位实现：所有渠道均未接入（isSupported = false）。
 * UI 依此渲染禁用态入口 + “即将上线”提示；SDK 落地后以真实实现替换。
 */
class UnsupportedThirdPartyAuthProvider(
    override val channel: ThirdPartyChannel,
) : ThirdPartyAuthProvider {

    override val isSupported: Boolean = false

    override suspend fun login(): ThirdPartyLoginResult =
        ThirdPartyLoginResult.NotSupported
}