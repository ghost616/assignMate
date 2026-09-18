package com.assignmate.app.settings.data

import com.assignmate.app.core.domain.ocr.OcrCacheCleaner as CoreOcrCacheCleaner
import com.assignmate.app.settings.domain.SettingsFeature
import com.assignmate.app.settings.domain.SettingsRoleGuard
import javax.inject.Inject

/**
 * settings 侧的 [OcrCacheCleaner] 实现：**分权守卫 + 委托 core 清理**。
 *
 * 为什么把守卫放在这一层：需求红线要求「学生访问/写入 OCR 配置必须被拒绝」为双保险
 * （页面层不可达 + 用例/仓库层拒绝）。识别缓存清理虽不涉及配置写入，但会删除全应用共用的
 * 缓存记录与图片文件，属于家长设置项能力，故与学生可用的护眼设置区别对待，同样在数据层拦截。
 *
 * 清理范围与去重口径由 core 的 [CoreOcrCacheCleaner] 保证（4 种状态记录一并清空 + 图片文件同删）；
 * 「本次清理了多少条」由 [com.assignmate.app.settings.domain.DataCleanupService] 以清理前后快照差值判定，
 * 本实现不重复计算，避免两处口径分叉。
 */
class GuardedOcrCacheCleaner @Inject constructor(
    private val coreCleaner: CoreOcrCacheCleaner,
    private val guard: SettingsRoleGuard,
) : OcrCacheCleaner {

    override suspend fun clear(): OcrCacheCleanResult {
        val denial = guard.denialReason(SettingsFeature.OCR_CONFIG)
        if (denial != null) {
            return OcrCacheCleanResult.Denied(denial)
        }
        coreCleaner.clear()
        return OcrCacheCleanResult.Cleaned(count = 0)
    }
}