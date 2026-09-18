package com.assignmate.app.settings.domain

import com.assignmate.app.auth.data.AuthRepository
import com.assignmate.app.auth.domain.Role
import com.assignmate.app.auth.domain.SessionState
import javax.inject.Inject

/**
 * 设置项分权口径（settings 模块唯一来源）。
 *
 * 需求红线：学生会话访问/写入 OCR 厂商配置必须被拒绝（页面层 + 用例/仓库层双保险）；
 * 学生可访问护眼设置；[SessionState.role] 为 null（未登录/已登出）一律按未登录处理，
 * 不得因其「不是学生」而放行 OCR 配置读写。
 */
enum class SettingsFeature {

    /** OCR 厂商配置（含 API 密钥）：仅家长可访问 */
    OCR_CONFIG,

    /** 护眼设置（三档主题）：家长与学生均可访问 */
    THEME,
}

/** 分权拒绝原因（机器可读，用户文案由 UI 层映射） */
enum class SettingsDenialReason {

    /** 无会话（未登录/已登出）：role 为 null 亦按未登录处理 */
    NOT_SIGNED_IN,

    /** 学生会话访问仅家长可用的设置项 */
    STUDENT_FORBIDDEN,
}

/**
 * 设置模块分权守卫：把「当前会话 -> 是否可访问某项设置」收敛成一处判定，
 * 供页面层（入口可见性、路由拒绝态）与数据层（仓库读写拒绝）共同复用，避免口径漂移。
 *
 * 会话来源经 [AuthRepository.currentSession] 注入（生产绑定 auth 既有实现，单测注入替身）。
 */
class SettingsRoleGuard @Inject constructor(
    private val authRepository: AuthRepository,
) {

    /** 当前会话对 [feature] 的拒绝原因；可访问返回 null */
    suspend fun denialReason(feature: SettingsFeature): SettingsDenialReason? =
        denialReasonOf(authRepository.currentSession(), feature)

    /** 当前会话是否可访问 [feature] */
    suspend fun canAccess(feature: SettingsFeature): Boolean = denialReason(feature) == null

    /**
     * 对**指定会话快照**做拒绝判定（便捷入口，委托伴生对象的纯判定）。
     *
     * 为什么保留实例方法：数据层在会话变更流里拿到的是「会话快照」而非「当前会话」，
     * 若此处只能走伴生对象，调用方需在两个入口间切换，容易写错；
     * 实例入口让「按快照判定」也能从注入的守卫对象上直接调用。
     */
    fun denialReasonOf(session: SessionState, feature: SettingsFeature): SettingsDenialReason? =
        Companion.denialReasonOf(session, feature)

    companion object {

        /**
         * 纯判定：会话 [session] 对 [feature] 是否可访问（不触达数据层，页面层可直接调用）。
         *
         * 规则：
         * - role 为 null -> [SettingsDenialReason.NOT_SIGNED_IN]（访问任何设置项都按未登录处理）；
         * - 学生会话 -> 仅 [SettingsFeature.OCR_CONFIG] 拒绝（[SettingsDenialReason.STUDENT_FORBIDDEN]），
         *   [SettingsFeature.THEME] 放行；
         * - 家长会话 -> 两项均放行。
         */
        fun denialReasonOf(session: SessionState, feature: SettingsFeature): SettingsDenialReason? =
            when {
                session.role == null -> SettingsDenialReason.NOT_SIGNED_IN
                session.role == Role.STUDENT && feature == SettingsFeature.OCR_CONFIG ->
                    SettingsDenialReason.STUDENT_FORBIDDEN

                else -> null
            }

        /**
         * 设置主页是否展示 OCR 配置入口（页面层可见性口径，与仓库层写拒绝同源）。
         *
         * 学生与未登录会话一律不可见，杜绝「入口可见但写入被拒」的割裂体验。
         */
        fun canShowOcrEntry(session: SessionState): Boolean =
            denialReasonOf(session, SettingsFeature.OCR_CONFIG) == null
    }
}