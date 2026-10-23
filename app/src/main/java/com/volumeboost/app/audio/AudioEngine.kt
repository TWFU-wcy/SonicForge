package com.volumeboost.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.volumeboost.app.root.RootDaemonClient
import com.volumeboost.app.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ln

/**
 * 引擎工作模式。
 *
 * 本应用只走 ROOT 路线：音效链必须由 uid 0 的守护进程在全局混音会话上创建。
 * 普通 App 进程在 HyperOS / MIUI 上创建全局音效会被 AudioFlinger 直接拒绝
 * （缺少 MODIFY_AUDIO_ROUTING），所以本应用只提供 ROOT 一条路线。
 */
enum class EngineMode {
    /** 未开启。 */
    OFF,

    /** ROOT 模式：整条音效链跑在 uid 0 的守护进程里。 */
    ROOT,

    /** 没有拿到 ROOT，或守护进程起不来。 */
    UNSUPPORTED,
}

data class AudioState(
    val active: Boolean = false,
    val mode: EngineMode = EngineMode.OFF,
    val error: String? = null,
    /** 目标增益，单位 1/100 dB。 */
    val boostMillibel: Int = 0,
    /** 当前均衡器各频段增益，长度 = [eqCenterFreqs].size。 */
    val eqLevelsDb: IntArray = IntArray(0),
    /** 当前均衡器的真实中心频率（Hz）。 */
    val eqCenterFreqs: IntArray = IntArray(0),
    /** 低音补偿强度 0..1000。 */
    val bassBoostStrength: Int = 0,
    /** 虚拟环绕强度 0..1000。 */
    val virtualizerStrength: Int = 0,
    /** 虚拟环绕是否可用（仅耳机/蓝牙输出有意义）。 */
    val virtualizerAvailable: Boolean = false,
    /** 限幅器是否在工作（守护进程报告 FULL 时为真）。 */
    val limiterActive: Boolean = false,
    /** 系统级单声道开关的真实状态。 */
    val mono: Boolean = false,
    val presetId: String = Preset.Default.id,
    val routedToBluetooth: Boolean = false,
    /** 当前输出路由的可读描述：「扬声器」「有线耳机」「蓝牙耳机」。 */
    val routeLabel: String = "扬声器",
    /** 当前模式下允许的最大增益，UI 用它限制滑块。 */
    val maxBoostMillibel: Int = 1800,
) {
    val gainDb: Float get() = boostMillibel / 100f
    val eqBandCount: Int get() = eqCenterFreqs.size

    /** 是否真的在跑。 */
    val supported: Boolean get() = mode == EngineMode.ROOT
}

/**
 * 音频引擎（ROOT 版）。
 *
 * 所有效果都不在本进程创建，而是交给 uid 0 的 [com.volumeboost.app.daemon.RootDaemon]：
 *
 *     su -c "CLASSPATH=<apk> app_process /system/bin com.volumeboost.app.daemon.RootDaemon <cmd> <status>"
 *
 * 守护进程里建一条 [android.media.audiofx.DynamicsProcessing]：
 *
 *     inputGain（增益） → preEq（均衡） → limiter（限幅） → 输出
 *
 * 顺序由系统保证：增益与均衡都在限幅器**之前**，所以推上去的峰值一定会被压回
 * -1.5 dBFS 以下，不会削波爆音。这条链挂在 audioSession = 0（全局混音输出），
 * 对所有 App 的媒体流生效。
 *
 * App 只负责写参数文件（files/vb_daemon.cmd）与读状态文件（files/vb_daemon.status），
 * 进程被杀不影响声音；守护进程用 setsid 脱离会话并把 oom_score_adj 调到 -900。
 *
 * 本应用不含任何提权或漏洞利用代码，只在系统已经提供 su 时使用它。
 */
class AudioEngine(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** ROOT 守护进程客户端；由 Application 启动时注入。 */
    private var rootClient: RootDaemonClient? = null
    private val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AudioState(mono = readSystemMono()))
    val state: StateFlow<AudioState> = _state.asStateFlow()

    val supported: Boolean
        get() = _state.value.supported

    /** 注入 ROOT 守护进程客户端（由 Application 启动时调用一次）。 */
    fun attachRootClient(client: RootDaemonClient) {
        rootClient = client
    }

    /**
     * App 重启后接管仍在运行的守护进程。
     *
     * 守护进程是独立于 App 的 uid 0 进程，App 被系统杀掉后它仍在增强声音；
     * 如果不接管，界面会显示「已关闭」但实际还在响，状态就骗人了。
     */
    suspend fun adoptRunningDaemon() {
        val client = rootClient ?: return
        if (_state.value.active) return
        // 直接读状态文件里的心跳时间戳，不需要 su 也能判断守护进程是否还活着
        val status = runCatching { client.readStatus() }.getOrNull() ?: return
        if (!status.isAlive()) return
        val cfg = runCatching { client.readConfig() }.getOrNull()
        _state.value = _state.value.copy(
            active = true,
            mode = EngineMode.ROOT,
            limiterActive = status.mode == "FULL",
            eqCenterFreqs = status.freqs,
            boostMillibel = cfg?.boost?.coerceIn(0, MaxBoostMillibel) ?: _state.value.boostMillibel,
            eqLevelsDb = cfg?.eq?.takeIf { it.isNotEmpty() } ?: _state.value.eqLevelsDb,
            bassBoostStrength = cfg?.bass?.coerceIn(0, 1000) ?: _state.value.bassBoostStrength,
            virtualizerStrength = cfg?.virt?.coerceIn(0, 1000) ?: _state.value.virtualizerStrength,
            virtualizerAvailable = hasHeadsetOutput(),
            error = null,
        )
        Log.i(TAG, "已接管运行中的 ROOT 守护进程 pid=${status.pid} mode=${status.mode} boost=${cfg?.boost}")
    }

    // ── 开关 ────────────────────────────────────────────────────────────────

    /**
     * 启用效果链；返回是否已进入 ROOT 模式。
     *
     * su 探测可能还没完成（用户还没点 KernelSU 的授权框），此时先按 ROOT 展示，
     * 后台等授权结果；授权失败就把状态翻成 [EngineMode.UNSUPPORTED] 并给出原因。
     */
    @Synchronized
    fun enable(preset: Preset): Boolean {
        if (_state.value.active) return true

        val client = rootClient
        if (client == null) {
            _state.value = buildState(preset, EngineMode.UNSUPPORTED, "ROOT 客户端未初始化", limiter = false)
            return false
        }

        _state.value = buildState(preset, EngineMode.ROOT, null, limiter = true)

        if (RootShell.cachedSu != null) {
            rootScope.launch { startRootDaemon(client) }
            Log.i(TAG, "引擎已启动 ROOT: gain=${preset.boostMillibel / 100f}dB")
            return true
        }

        rootScope.launch {
            val su = runCatching { RootShell.resolve() }.getOrNull()
            if (su == null) {
                Log.w(TAG, "未检测到 ROOT，拒绝启动")
                _state.value = buildState(
                    preset, EngineMode.UNSUPPORTED,
                    "需要 ROOT：没有拿到 su。请先用临时 ROOT 工具注入，再点「重新检测」。",
                    limiter = false,
                )
            } else {
                startRootDaemon(client)
            }
        }
        return true
    }

    /** 关闭守护进程并释放音效。 */
    @Synchronized
    fun disable() {
        val client = rootClient
        _state.value = _state.value.copy(
            active = false,
            mode = EngineMode.OFF,
            limiterActive = false,
            eqLevelsDb = IntArray(0),
            eqCenterFreqs = IntArray(0),
            error = null,
        )
        rootScope.launch { client?.stop() }
    }

    // ── 参数调节 ────────────────────────────────────────────────────────────

    @Synchronized
    fun setBoost(millibel: Int) {
        if (_state.value.mode != EngineMode.ROOT) return
        val v = millibel.coerceIn(0, MaxBoostMillibel)
        _state.value = _state.value.copy(boostMillibel = v, presetId = CustomPresetId)
        pushRootConfig()
    }

    @Synchronized
    fun setEqBand(band: Int, levelDb: Int) {
        if (_state.value.mode != EngineMode.ROOT) return
        val next = _state.value.eqLevelsDb.copyOf()
        if (band !in next.indices) return
        next[band] = levelDb.coerceIn(MinEqDb, MaxEqDb)
        _state.value = _state.value.copy(eqLevelsDb = next, presetId = CustomPresetId)
        pushRootConfig()
    }

    /** 低音补偿：直接加在最低几个 preEq 频段上，因此同样受限幅器保护。 */
    @Synchronized
    fun setBassBoost(strength: Int) {
        if (_state.value.mode != EngineMode.ROOT) return
        val v = strength.coerceIn(0, 1000)
        _state.value = _state.value.copy(bassBoostStrength = v, presetId = CustomPresetId)
        pushRootConfig()
    }

    @Synchronized
    fun setVirtualizer(strength: Int) {
        if (_state.value.mode != EngineMode.ROOT) return
        val v = strength.coerceIn(0, 1000)
        _state.value = _state.value.copy(virtualizerStrength = v, presetId = CustomPresetId)
        pushRootConfig()
    }

    /** ROOT 直接写入系统设置后，同步一下 UI 状态。 */
    fun syncMono(enabled: Boolean) {
        _state.value = _state.value.copy(mono = enabled)
    }

    /** 读取系统单声道真实状态，用于回显。 */
    fun readSystemMono(): Boolean = runCatching {
        Settings.System.getInt(appContext.contentResolver, SystemMonoKey, 0) == 1
    }.getOrDefault(false)

    @Synchronized
    fun applyPreset(preset: Preset) {
        if (!_state.value.active) {
            enable(preset)
            return
        }
        if (_state.value.mode != EngineMode.ROOT) return

        val rootFreqs = _state.value.eqCenterFreqs
        val rootLevels = IntArray(rootFreqs.size) { i ->
            sampleCurve(preset.eqLevelsDb, rootFreqs[i]).coerceIn(MinEqDb, MaxEqDb)
        }
        _state.value = _state.value.copy(
            boostMillibel = preset.boostMillibel.coerceAtMost(MaxBoostMillibel),
            eqLevelsDb = rootLevels,
            bassBoostStrength = preset.bassBoostStrength,
            virtualizerStrength = preset.virtualizerStrength,
            presetId = preset.id,
        )
        pushRootConfig()
    }

    /** 刷新输出路由：决定虚拟环绕是否可用，并同步系统单声道真实状态。 */
    fun refreshRoute() {
        val headset = hasHeadsetOutput()
        _state.value = _state.value.copy(
            routedToBluetooth = isBluetoothA2dpOn(),
            routeLabel = routeLabel(),
            virtualizerAvailable = headset,
            mono = readSystemMono(),
        )
    }

    // ── 诊断 ────────────────────────────────────────────────────────────────

    /** 生成一份可复制的运行状态诊断，用来确认 ROOT 链路到底卡在哪。 */
    fun diagnose(): String = buildString {
        appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("输出路由：${routeLabel()}")
        appendLine("当前模式：${_state.value.mode}")
        appendLine("增益：${_state.value.gainDb} dB / 均衡 ${_state.value.eqBandCount} 段 / 限幅 ${_state.value.limiterActive}")
        appendLine()
        appendLine("【ROOT】")
        appendLine(if (RootShell.cachedSu != null) "su：已授权（${RootShell.cachedSu}）" else "su：未授权")
        val client = rootClient
        if (client != null) {
            // 用状态文件的心跳判断存活：diagnose() 是同步方法，不能直接调 suspend 的 isRunning()
            val status = client.readStatusSync()
            appendLine("守护进程运行中：${status?.isAlive() == true}")
            appendLine("守护进程模式：${status?.mode ?: "NONE"}")
            appendLine("日志尾部：")
            appendLine(client.logTail(15).ifBlank { "（空）" })
        } else {
            appendLine("守护进程客户端：未初始化")
        }
        appendLine()
        appendLine("【系统注册的全局音效】")
        val all = runCatching { AudioEffect.queryEffects() }.getOrNull()
        if (all.isNullOrEmpty()) {
            appendLine("（空）系统没有注册任何可用的全局音效")
        } else {
            all.forEach { d -> appendLine("· ${d.name} / ${d.implementor}") }
        }
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private fun buildState(
        preset: Preset,
        mode: EngineMode,
        error: String?,
        limiter: Boolean,
    ): AudioState {
        val freqs = CanonicalEqFreqs
        val levels = IntArray(freqs.size) { i ->
            sampleCurve(preset.eqLevelsDb, freqs[i]).coerceIn(MinEqDb, MaxEqDb)
        }
        return AudioState(
            active = mode == EngineMode.ROOT,
            mode = mode,
            error = error,
            boostMillibel = preset.boostMillibel.coerceAtMost(MaxBoostMillibel),
            eqLevelsDb = levels,
            eqCenterFreqs = freqs,
            bassBoostStrength = preset.bassBoostStrength,
            virtualizerStrength = preset.virtualizerStrength,
            virtualizerAvailable = hasHeadsetOutput(),
            limiterActive = limiter,
            mono = readSystemMono(),
            presetId = preset.id,
            routedToBluetooth = isBluetoothA2dpOn(),
            routeLabel = routeLabel(),
            maxBoostMillibel = MaxBoostMillibel,
        )
    }

    // ── ROOT 守护进程 ───────────────────────────────────────────────────────

    /** 把当前状态推给守护进程（文件很小，同步写即可）。 */
    private fun pushRootConfig() {
        val s = _state.value
        rootClient?.writeConfig(
            active = true,
            boost = s.boostMillibel,
            eq = s.eqLevelsDb,
            bass = s.bassBoostStrength,
            virt = s.virtualizerStrength,
        )
    }

    /** 拉起 uid 0 的守护进程，并校验它是否真的建立起了音效链。 */
    private suspend fun startRootDaemon(client: RootDaemonClient) {
        val snapshot = _state.value
        client.writeConfig(
            active = true,
            boost = snapshot.boostMillibel,
            eq = snapshot.eqLevelsDb,
            bass = snapshot.bassBoostStrength,
            virt = snapshot.virtualizerStrength,
        )
        val started = runCatching { client.start() }.getOrDefault(false)
        val status = if (started) runCatching { client.readStatus() }.getOrNull() else null
        if (!started || status == null || !status.ok) {
            val reason = status?.error?.takeIf { it.isNotBlank() }
            Log.e(TAG, "ROOT 守护进程启动失败 started=$started status=$status")
            _state.value = _state.value.copy(
                active = false,
                mode = EngineMode.UNSUPPORTED,
                limiterActive = false,
                error = buildString {
                    append("ROOT 守护进程启动失败")
                    if (reason != null) append("：").append(reason)
                },
            )
            return
        }
        _state.value = _state.value.copy(
            limiterActive = status.mode == "FULL",
            eqCenterFreqs = status.freqs.takeIf { it.isNotEmpty() } ?: _state.value.eqCenterFreqs,
            error = null,
        )
        Log.i(TAG, "ROOT 守护进程就绪 pid=${status.pid} mode=${status.mode} bands=${status.freqs.size}")
    }

    /** 是否接着耳机 / 蓝牙（虚拟环绕只在耳机上真正有意义）。 */
    private fun hasHeadsetOutput(): Boolean = runCatching {
        connectedDeviceTypes().any { type ->
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }.getOrDefault(false)

    /** 当前输出路由的可读描述。 */
    private fun routeLabel(): String = runCatching {
        val types = connectedDeviceTypes()
        when {
            types.any {
                it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } -> "蓝牙耳机"

            types.any {
                it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it == AudioDeviceInfo.TYPE_USB_HEADSET
            } -> "有线耳机"

            else -> "扬声器"
        }
    }.getOrDefault("扬声器")

    private fun isBluetoothA2dpOn(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        audioManager.isBluetoothA2dpOn
    }.getOrDefault(false)

    private fun connectedDeviceTypes(): Set<Int> = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
    }.getOrDefault(emptySet())

    /**
     * 按对数频率在预设的 10 点曲线上插值，得到任意硬件频段的目标增益。
     * 这样预设不会绑死在某个设备的频段数量上。
     */
    private fun sampleCurve(curve: IntArray, freq: Int): Int {
        if (curve.isEmpty()) return 0
        if (curve.size == 1) return curve[0]
        val freqs = CanonicalEqFreqs
        if (freq <= freqs.first()) return curve.first()
        if (freq >= freqs.last()) return curve.last()
        var hi = 1
        while (hi < freqs.size && freqs[hi] < freq) hi++
        val lo = hi - 1
        val f0 = ln(freqs[lo].toDouble())
        val f1 = ln(freqs[hi].toDouble())
        val t = (ln(freq.toDouble()) - f0) / (f1 - f0)
        return (curve[lo] + (curve[hi] - curve[lo]) * t).toInt()
    }

    private companion object {
        const val TAG = "AudioEngine"

        /** 预设曲线的标准频点（Hz），守护进程也按这套频点建链。 */
        val CanonicalEqFreqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        /** 增益上限 +18 dB（限幅器保证不会削波）。 */
        const val MaxBoostMillibel = 1800

        const val MinEqDb = -1200
        const val MaxEqDb = 1200

        /** 自定义参数后预设名显示为"自定义"。 */
        const val CustomPresetId = "custom"

        /** 系统单声道设置项（无障碍 → 单声道音频）。 */
        const val SystemMonoKey = "master_mono"
    }
}
