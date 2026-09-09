// assignMate 根构建设置：声明插件仓库、依赖解析策略与子模块
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 统一在根级解析依赖：禁止模块各自声明仓库，避免版本漂移
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "assignMate"

// 单 :app 模块；core 与各 feature 模块后续在此基础上增量添加
include(":app")