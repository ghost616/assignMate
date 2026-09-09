# assignMate app 混淆规则（当前 release 未开启 minify，规则预留给后续开启时使用）
# 各第三方库规则随依赖自动打包；需要时在此补充：

# Hilt / Dagger 由注解处理器生成代码，KSP 产物保持默认即可

# -keep class com.assignmate.app.** { *; }