package com.assignmate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.assignmate.app.core.domain.prefs.ThemeMode
import com.assignmate.app.core.domain.prefs.ThemePreferenceStore
import com.assignmate.app.core.ui.theme.AssignMateTheme
import com.assignmate.app.navigation.AssignMateNavHost
import com.assignmate.app.settings.domain.EyeCareTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 单 Activity 宿主：加载 Compose 导航宿主。
 * 所有业务页面（家长/学生入口、作业、计时、统计、设置等）以路由方式挂载，不新增 Activity。
 *
 * 主题偏好生效（护眼三档）：
 * - 订阅 core 的 [ThemePreferenceStore.observeThemeMode]，按 [EyeCareTheme.resolveDarkTheme] 口径
 *   决定 Material3 深浅配色——护眼浅色 → 浅色、护眼夜间 → 深色、跟随系统 → `isSystemInDarkTheme()`；
 * - 订阅态用 [collectAsStateWithLifecycle] 呈现为 Compose 状态：护眼设置页写库后观察流即发射新档位，
 *   根主题随之重组，**切换档位即时生效、无需重启**；
 * - 主题档位缺失或存量值非法时由 core 一并兜底为「跟随系统」（[ThemeMode.fromRawValue]），
 *   宿主侧取初值也用 [ThemeMode.DEFAULT]，故读偏好失败只会回落跟随系统，不会崩溃。
 *
 * 为什么在这里订阅而不是各页面各自订阅：配色只有根节点能全局生效，
 * 且必须与「设置页写库 → 全局换肤」保持单一数据源（settings 只负责写，framework 负责消费）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** 主题偏好（DataStore 偏好文件 assignmate_prefs，键名与默认值均由 core 收敛） */
    @Inject
    lateinit var themePreferenceStore: ThemePreferenceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 偏好初值取 DEFAULT（跟随系统）：DataStore 首次发射前不会出现黑白闪烁式的非法态
            val themeMode by themePreferenceStore.observeThemeMode()
                .collectAsStateWithLifecycle(initialValue = ThemeMode.DEFAULT)
            AssignMateTheme(
                darkTheme = EyeCareTheme.resolveDarkTheme(themeMode, isSystemInDarkTheme()),
            ) {
                AssignMateNavHost()
            }
        }
    }
}
