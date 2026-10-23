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

    /** 守护进程客户端，进程内共用一个实例。 */
    private val daemonClient by lazy { RootDaemonClient(this) }

    override fun onCreate() {
        super.onCreate()
        // 提前实例化引擎，保证进程存活时效果状态一致
        val engine = AudioEngineProvider.get(this)
        // 注入 ROOT 守护进程客户端：su 可用时优先走 uid 0 的独立进程
        engine.attachRootClient(daemonClient)

        // 预热 su 探测：用户第一次打开 App 时就弹授权框，
        // 这样点「增强开关」时能立刻拉起守护进程，而不是先等一次授权。
        appScope.launch {
            RootShell.resolve()
            // 装新版本后，上一次拉起的旧守护进程可能还在跑（它是 setsid 脱离的 uid 0 进程，
            // 装 APK 不会杀掉它）。旧进程写的状态文件缺字段，界面就会误报
            // 「响度均衡不可用」「输出路由未知」，所以这里先按协议版本清理一次。
            runCatching { daemonClient.retireIfStale() }
            // 守护进程可能在上一次进程被杀后仍在运行，接管它，避免状态不一致
            engine.adoptRunningDaemon()
        }
    }
}
