package com.sonicforge.app

import android.app.Application
import com.sonicforge.app.audio.AudioEngineProvider
import com.sonicforge.app.root.RootDaemonClient
import com.sonicforge.app.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SonicForgeApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 提前实例化引擎，保证进程存活时效果状态一致
        val engine = AudioEngineProvider.get(this)
        // 注入 ROOT 守护进程客户端：su 可用时优先走 uid 0 的独立进程
        engine.attachRootClient(RootDaemonClient(this))

        // 预热 su 探测：用户第一次打开 App 时就弹授权框，
        // 这样点「增强开关」时能立刻拉起守护进程，而不是先等一次授权。
        appScope.launch {
            RootShell.resolve()
            // 守护进程可能在上一次进程被杀后仍在运行，接管它，避免状态不一致
            engine.adoptRunningDaemon()
        }
    }
}
