package com.assignmate.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口：Hilt 依赖注入容器。
 *
 * @HiltAndroidApp 生成全局组件并接入 Application 生命周期，
 * 后续 core 各数据层（Room/DataStore/网络等）均以 Hilt 模块方式注入。
 */
@HiltAndroidApp
class AssignMateApplication : Application()