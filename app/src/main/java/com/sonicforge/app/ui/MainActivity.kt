package com.sonicforge.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.sonicforge.app.audio.AudioEngineProvider
import com.sonicforge.app.audio.Preset
import com.sonicforge.app.root.RootAudioManager
import com.sonicforge.app.ui.theme.SonicForgeTheme
import kotlinx.coroutines.launch

/**
 * 单页面 Activity：所有交互集中在一个简洁界面里。
 *
 * 本应用只支持 ROOT 模式，没有前台服务、没有常驻通知：
 * 音效由 uid 0 的守护进程承载，App 退出后声音继续生效。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val engine = AudioEngineProvider.get(this)
        val rootManager = RootAudioManager.get(this)
        // 恢复上次状态（预设仅作默认选中，效果需用户显式开启）
        val prefs = getSharedPreferences("sonicforge", MODE_PRIVATE)
        val lastPreset = prefs.getString("preset", null)

        setContent {
            // 主题偏好：system / light / dark，存在 SharedPreferences
            var themeMode by remember {
                mutableStateOf(prefs.getString("theme_mode", "system") ?: "system")
            }
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            SonicForgeTheme(darkTheme = darkTheme) {
                // 浅色主题下把状态栏/导航栏图标切成深色，否则白图标在浅背景上看不清
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                val state by engine.state.collectAsState()
                val rootState by rootManager.state.collectAsState()
                LaunchedEffect(Unit) { rootManager.refresh() }
                // 守护进程自己判断耳机/蓝牙并暂停处理，界面每 2 秒同步一次它写回的路由与暂停状态，
                // 这样插拔耳机时主页的「已暂停」提示不用等用户切后台再回来。
                LaunchedEffect(state.active) {
                    while (state.active) {
                        kotlinx.coroutines.delay(2_000)
                        engine.refreshRoute()
                    }
                }
                SonicForgeApp(
                    state = state,
                    initialPresetId = lastPreset,
                    onToggle = { enable, presetId ->
                        prefs.edit().putString("preset", presetId).apply()
                        if (enable) {
                            if (presetId == "custom") {
                                // 自定义：原样沿用界面上的参数，不要重置回预设
                                engine.enableCurrent()
                            } else {
                                val preset = Preset.All.firstOrNull { it.id == presetId } ?: Preset.Default
                                engine.enable(preset)
                            }
                        } else {
                            engine.disable()
                        }
                    },
                    onPresetSelected = { preset ->
                        engine.applyPreset(preset)
                        prefs.edit().putString("preset", preset.id).apply()
                    },
                    onBoostChange = { engine.setBoost(it) },
                    onEqBandChange = { band, db -> engine.setEqBand(band, db) },
                    onBassChange = { engine.setBassBoost(it) },
                    onLevelerChange = { engine.setLeveler(it) },
                    onMonoChange = { enabled ->
                        // 单声道只走 ROOT（settings put system master_mono），
                        // 普通 App 写系统设置需要用户在设置里手动授权，这里不再提供那条路。
                        lifecycleScope.launch {
                            if (rootManager.setMono(enabled)) {
                                engine.syncMono(enabled)
                            } else {
                                engine.syncMono(!enabled)
                            }
                        }
                    },
                    themeMode = themeMode,
                    onThemeModeChange = {
                        themeMode = it
                        prefs.edit().putString("theme_mode", it).apply()
                    },
                    onDiagnose = {
                        engine.diagnose() + "\n\n" + rootManager.state.value.diagnostic
                    },
                    root = rootState,
                    onRootRefresh = { lifecycleScope.launch { rootManager.refresh() } },
                    onRootVendorChange = { blocked ->
                        lifecycleScope.launch { rootManager.setVendorBlocked(blocked) }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AudioEngineProvider.get(this).refreshRoute()
        // 回到前台时顺带刷新一次 ROOT 状态：用户可能刚注入完临时 ROOT
        lifecycleScope.launch { RootAudioManager.get(this@MainActivity).refresh() }
    }
}
