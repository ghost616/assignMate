package com.assignmate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.assignmate.app.navigation.AssignMateNavHost
import com.assignmate.app.core.ui.theme.AssignMateTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 单 Activity 宿主：加载 Compose 导航宿主。
 * 所有业务页面（家长/学生入口、作业、计时、统计等）以路由方式挂载，不新增 Activity。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AssignMateTheme {
                AssignMateNavHost()
            }
        }
    }
}