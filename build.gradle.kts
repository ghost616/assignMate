// 根构建脚本：仅统一声明插件版本（apply false），各子模块按需应用
// 版本号统一收敛于 gradle/libs.versions.toml（version catalog）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}