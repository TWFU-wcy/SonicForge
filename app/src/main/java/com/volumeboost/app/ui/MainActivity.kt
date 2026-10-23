package com.volumeboost.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.volumeboost.app.audio.AudioEngineProvider
import com.volumeboost.app.audio.EngineMode
import com.volumeboost.app.audio.Preset
import com.volumeboost.app.root.RootAudioManager
import com.volumeboost.app.root.RootShell
import com.volumeboost.app.service.BoostForegroundService
import com.volumeboost.app.ui.theme.VolumeBoostTheme
import kotlinx.coroutines.launch

/**
 * 单页面 Activity：所有交互集中在一个简洁界面里。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val engine = AudioEngineProvider.get(this)
        val rootManager = RootAudioManager.get(this)
        // 恢复上次状态（预设仅作默认选中，效果需用户显式开启）
        val prefs = getSharedPreferences("volume_boost", MODE_PRIVATE)
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
            VolumeBoostTheme(darkTheme = darkTheme) {
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
                // ROOT 模式下音效由 uid 0 的守护进程承载，前台服务与通知都是多余的。
                // 无论是同步切换还是异步探测后升级到 ROOT，这里统一把通知收掉。
                LaunchedEffect(state.mode) {
                    if (state.mode == EngineMode.ROOT) {
                        BoostForegroundService.disable(applicationContext)
                    }
                }
                VolumeBoostApp(
                    state = state,
                    initialPresetId = lastPreset,
                    onToggle = { enable, presetId ->
                        prefs.edit().putString("preset", presetId).apply()
                        val appContext = applicationContext
                        if (enable) {
                            val preset = Preset.All.firstOrNull { it.id == presetId } ?: Preset.Default
                            if (RootShell.cachedSu != null) {
                                // ROOT 模式：守护进程独立运行，不需要前台服务，也不发通知
                                engine.enable(preset)
                                BoostForegroundService.disable(appContext)
                            } else {
                                maybeRequestNotificationPermission()
                                BoostForegroundService.enable(appContext, presetId)
                            }
                        } else {
                            engine.disable()
                            BoostForegroundService.disable(appContext)
                            BoostForegroundService.cancelNotification(appContext)
                        }
                    },
                    onPresetSelected = { preset ->
                        val wasActive = engine.state.value.active
                        engine.applyPreset(preset)
                        prefs.edit().putString("preset", preset.id).apply()
                        // 关闭状态下点预设会自动开启效果；只有非 ROOT 模式才需要前台服务
                        if (!wasActive && engine.state.value.active &&
                            engine.state.value.mode != EngineMode.ROOT
                        ) {
                            maybeRequestNotificationPermission()
                            BoostForegroundService.enable(applicationContext, preset.id)
                        }
                    },
                    onBoostChange = { engine.setBoost(it) },
                    onEqBandChange = { band, db -> engine.setEqBand(band, db) },
                    onBassChange = { engine.setBassBoost(it) },
                    onVirtualizerChange = { engine.setVirtualizer(it) },
                    onMonoChange = { enabled ->
                        if (rootManager.state.value.available) {
                            // 有 ROOT 时直接写系统设置，不需要「修改系统设置」权限
                            lifecycleScope.launch {
                                if (rootManager.setMono(enabled)) engine.syncMono(enabled)
                            }
                        } else if (Settings.System.canWrite(this)) {
                            engine.setMono(enabled)
                        } else {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.parse("package:$packageName"),
                                )
                            )
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
                    onRootGainChange = { db -> lifecycleScope.launch { rootManager.applyGain(db) } },
                    onRootRestore = { lifecycleScope.launch { rootManager.restore() } },
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
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
