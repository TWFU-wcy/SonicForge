package com.sonicforge.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.sonicforge.app.root.RootDaemonClient
import com.sonicforge.app.root.RootShell
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
    /** 系统音量最低时允许的最大增益，单位 1/100 dB（滑杆拖的就是这个上限）。 */
    val boostMillibel: Int = 0,
    /** 系统音量最大时保留的底噪补偿，单位 1/100 dB。 */
    val minBoostMillibel: Int = Preset.FloorBoostMillibel,
    /** 按当前系统音量折算出来的真实增益，单位 1/100 dB。 */
    val effectiveBoostMillibel: Int = 0,
    /** 当前媒体音量档位与最大档位，用来显示「音量 8/15」与折算增益。 */
    val systemVolume: Int = 0,
    val maxSystemVolume: Int = 15,
    /** 当前均衡器各频段增益，长度 = [eqCenterFreqs].size。 */
    val eqLevelsDb: IntArray = IntArray(0),
    /** 当前均衡器的真实中心频率（Hz）。 */
    val eqCenterFreqs: IntArray = IntArray(0),
    /** 低音补偿强度 0..1000。 */
    val bassBoostStrength: Int = 0,
    /** 响度均衡强度 0..1000，0 = 关闭。 */
    val levelerStrength: Int = 0,
    /** 本机 DSP 是否支持多段压缩（响度均衡依赖它）。 */
    val levelerAvailable: Boolean = false,
    /** 限幅器是否在工作（守护进程报告 FULL 时为真）。 */
    val limiterActive: Boolean = false,
    /** 系统级单声道开关的真实状态。 */
    val mono: Boolean = false,
    val presetId: String = Preset.Default.id,
    val routedToBluetooth: Boolean = false,
    /** 当前输出路由的可读描述：「扬声器」「有线耳机」「蓝牙耳机」。 */
    val routeLabel: String = "扬声器",
    /** 是否因为输出到耳机/蓝牙而自动暂停了全部处理。 */
    val pausedForHeadset: Boolean = false,
    /** 当前模式下允许的最大增益，UI 用它限制滑块。 */
    val maxBoostMillibel: Int = 1800,
) {
    /** 按当前系统音量折算后的真实增益（dB），主页显示的就是它。 */
    val gainDb: Float get() = effectiveBoostMillibel / 100f

    /** 折算增益相对原始信号的感知响度增幅（%），用 sone 标度换算。 */
    val gainPercent: Int get() = Preset.perceivedPercent(effectiveBoostMillibel)

    /** 系统音量占比 0..1，UI 用它画音量条。 */
    val volumeRatio: Float
        get() = if (maxSystemVolume <= 0) 0f
        else (systemVolume.toFloat() / maxSystemVolume).coerceIn(0f, 1f)

    val eqBandCount: Int get() = eqCenterFreqs.size

    /** 是否真的在跑。 */
    val supported: Boolean get() = mode == EngineMode.ROOT
}

/**
 * 音频引擎（ROOT 版）。
 *
 * 所有效果都不在本进程创建，而是交给 uid 0 的 [com.sonicforge.app.daemon.RootDaemon]：
 *
 *     su -c "CLASSPATH=<apk> app_process /system/bin com.sonicforge.app.daemon.RootDaemon <cmd> <status>"
 *
 * 守护进程里建一条 [android.media.audiofx.DynamicsProcessing]：
 *
 *     inputGain（增益） → preEq（均衡） → limiter（限幅） → 输出
 *
 * 顺序由系统保证：增益与均衡都在限幅器**之前**，所以推上去的峰值一定会被压回
 * -1.5 dBFS 以下，不会削波爆音。这条链挂在 audioSession = 0（全局混音输出），
 * 对所有 App 的媒体流生效。
 *
 * App 只负责写参数文件（files/sf_daemon.cmd）与读状态文件（files/sf_daemon.status），
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

    private val _state = MutableStateFlow(
        // 关闭状态下也要有可看的数值：滑杆和预设卡显示的是「小音量时最多补多少」，
        // 不初始化成 0 的话用户一进来看到 +0%，会以为软件没功能。
        AudioState(
            mono = readSystemMono(),
            boostMillibel = Preset.Default.boostMillibel,
            minBoostMillibel = Preset.Default.minBoostMillibel,
            eqLevelsDb = Preset.Default.eqLevelsDb.copyOf(),
            eqCenterFreqs = CanonicalEqFreqs,
            bassBoostStrength = Preset.Default.bassBoostStrength,
            levelerStrength = Preset.Default.levelerStrength,
            presetId = Preset.Default.id,
        )
    )
    val state: StateFlow<AudioState> = _state.asStateFlow()

    /** 记住当前预设的增益区间，系统音量一变就按它重算折算增益。 */
    private var presetMaxBoost = Preset.Default.boostMillibel
    private var presetMinBoost = Preset.Default.minBoostMillibel

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
        val volume = readSystemVolume()
        // 配置文件里的 boost 是「小音量时的补偿上限」，不是当前实际值；
        // 实际值由上限 + 当前系统音量折算出来。
        val maxBoost = (cfg?.boost ?: presetMaxBoost).coerceIn(Preset.FloorBoostMillibel, MaxBoostMillibel)
        val minBoost = (cfg?.boostMin ?: Preset.FloorBoostMillibel)
            .coerceIn(Preset.FloorBoostMillibel, maxBoost)
        val effective = Preset.effectiveBoostMillibel(maxBoost, minBoost, volume.first, volume.second)
        presetMaxBoost = maxBoost
        presetMinBoost = minBoost
        _state.value = _state.value.copy(
            active = true,
            mode = EngineMode.ROOT,
            limiterActive = status.mode == "FULL",
            eqCenterFreqs = status.freqs,
            boostMillibel = presetMaxBoost,
            minBoostMillibel = presetMinBoost,
            effectiveBoostMillibel = effective,
            systemVolume = volume.first,
            maxSystemVolume = volume.second,
            eqLevelsDb = cfg?.eq?.takeIf { it.isNotEmpty() } ?: _state.value.eqLevelsDb,
            bassBoostStrength = cfg?.bass?.coerceIn(0, 1000) ?: _state.value.bassBoostStrength,
            levelerStrength = cfg?.leveler?.coerceIn(0, 1000) ?: _state.value.levelerStrength,
            levelerAvailable = status.mbcAvailable,
            routeLabel = status.route.takeIf { it.isNotBlank() } ?: routeLabel(),
            pausedForHeadset = status.paused,
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
            error = null,
            // 关掉后界面不该再显示「当前实际 +23%」这类折算值
            effectiveBoostMillibel = 0,
        )
        rootScope.launch { client?.stop() }
    }

    /**
     * 用当前界面上的参数重新开启，不重置成任何预设。
     *
     * 「自定义」预设点开开关时走这条路径：用户自己调好的增益上限、均衡、低音
     * 必须原样保留，不能被 [Preset.Default] 覆盖掉。
     */
    @Synchronized
    fun enableCurrent(): Boolean {
        if (_state.value.active) return true
        val client = rootClient ?: run {
            _state.value = _state.value.copy(
                active = false,
                mode = EngineMode.UNSUPPORTED,
                error = "ROOT 客户端未初始化",
            )
            return false
        }
        val volume = readSystemVolume()
        val effective = Preset.effectiveBoostMillibel(
            _state.value.boostMillibel, _state.value.minBoostMillibel, volume.first, volume.second,
        )
        _state.value = _state.value.copy(
            active = true,
            mode = EngineMode.ROOT,
            limiterActive = true,
            error = null,
            effectiveBoostMillibel = effective,
            systemVolume = volume.first,
            maxSystemVolume = volume.second,
            presetId = CustomPresetId,
        )
        if (RootShell.cachedSu != null) {
            rootScope.launch { startRootDaemon(client) }
            return true
        }
        rootScope.launch {
            val su = runCatching { RootShell.resolve() }.getOrNull()
            if (su == null) {
                Log.w(TAG, "未检测到 ROOT，拒绝启动")
                _state.value = _state.value.copy(
                    active = false,
                    mode = EngineMode.UNSUPPORTED,
                    limiterActive = false,
                    error = "需要 ROOT：没有拿到 su。请先用临时 ROOT 工具注入，再点「重新检测」。",
                )
            } else {
                startRootDaemon(client)
            }
        }
        return true
    }

    // ── 参数调节 ────────────────────────────────────────────────────────────

    /**
     * 调整增益上限（滑杆拖的是「低音量时补多少」）。
     *
     * 实际下发的是按当前系统音量折算后的值：用户把上限调高，满音量时
     * 也只会拿到 [Preset.FloorBoostMillibel] 那一档，不会因为拖满而破音。
     */
    @Synchronized
    fun setBoost(millibel: Int) {
        if (_state.value.mode != EngineMode.ROOT) return
        val v = millibel.coerceIn(Preset.FloorBoostMillibel, MaxBoostMillibel)
        presetMaxBoost = v
        val volume = readSystemVolume()
        val effective = Preset.effectiveBoostMillibel(
            presetMaxBoost, presetMinBoost, volume.first, volume.second,
        )
        _state.value = _state.value.copy(
            boostMillibel = v,
            effectiveBoostMillibel = effective,
            systemVolume = volume.first,
            maxSystemVolume = volume.second,
            presetId = CustomPresetId,
        )
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

    /**
     * 响度均衡强度 0..1000。
     *
     * 抖音这类短视频平台自己会做响度归一化，但不同片源、不同 App 之间的电平
     * 仍然差很多；这里在系统混音输出上再压一道，把忽大忽小的动态范围收窄。
     */
    @Synchronized
    fun setLeveler(strength: Int) {
        if (_state.value.mode != EngineMode.ROOT) return
        val v = strength.coerceIn(0, 1000)
        _state.value = _state.value.copy(levelerStrength = v, presetId = CustomPresetId)
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
        presetMaxBoost = preset.boostMillibel.coerceAtMost(MaxBoostMillibel)
        presetMinBoost = preset.minBoostMillibel.coerceIn(0, presetMaxBoost)
        val volume = readSystemVolume()
        val effective = Preset.effectiveBoostMillibel(
            presetMaxBoost, presetMinBoost, volume.first, volume.second,
        )
        _state.value = _state.value.copy(
            boostMillibel = presetMaxBoost,
            minBoostMillibel = presetMinBoost,
            effectiveBoostMillibel = effective,
            systemVolume = volume.first,
            maxSystemVolume = volume.second,
            eqLevelsDb = rootLevels,
            // 低音强度存基准值，随音量递减由守护进程按同一条曲线折算
            bassBoostStrength = preset.bassBoostStrength.coerceIn(0, 1000),
            levelerStrength = preset.levelerStrength.coerceIn(0, 1000),
            presetId = preset.id,
        )
        pushRootConfig()
    }

    /**
     * 刷新输出路由：同步守护进程写回的路由与暂停状态，并回显系统单声道真实状态。
     *
     * 耳机/蓝牙的暂停由守护进程自己判断（它每隔一秒多读一次 AudioFlinger 的
     * 输出设备），App 这里只把状态读回来显示，不重复做决定，免得两边打架。
     *
     * 同时按当前系统音量重算折算增益：用户按音量键后增益要跟着降下来，
     * 否则满音量还顶着 +5 dB 就会破音。折算值变了才写配置文件，避免无谓的 IO。
     */
    fun refreshRoute() {
        val status = rootClient?.readStatusSync()
        val volume = readSystemVolume()
        val effective = Preset.effectiveBoostMillibel(
            presetMaxBoost, presetMinBoost, volume.first, volume.second,
        )
        val gainChanged = effective != _state.value.effectiveBoostMillibel
        _state.value = _state.value.copy(
            routedToBluetooth = isBluetoothA2dpOn(),
            routeLabel = status?.route?.takeIf { it.isNotBlank() } ?: routeLabel(),
            pausedForHeadset = status?.paused ?: _state.value.pausedForHeadset,
            mono = readSystemMono(),
            systemVolume = volume.first,
            maxSystemVolume = volume.second,
            // 关闭状态下保持 0，避免界面回显一个并不存在的增益
            effectiveBoostMillibel = if (_state.value.active) effective else 0,
        )
        if (gainChanged && _state.value.active) {
            pushRootConfig()
            Log.i(TAG, "系统音量 ${volume.first}/${volume.second}，折算增益 ${effective / 100f}dB")
        }
    }

    /** 读系统媒体音量与最大档位。 */
    private fun readSystemVolume(): Pair<Int, Int> = runCatching {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        current to max
    }.getOrDefault(0 to 15)

    // ── 诊断 ────────────────────────────────────────────────────────────────

    /** 生成一份可复制的运行状态诊断，用来确认 ROOT 链路到底卡在哪。 */
    fun diagnose(): String = buildString {
        appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("输出路由：${routeLabel()}")
        appendLine("当前模式：${_state.value.mode}")
        appendLine("增益：上限 ${_state.value.boostMillibel / 100f} dB → 当前折算 ${_state.value.gainDb} dB（感知 +${_state.value.gainPercent}%）")
        appendLine("系统音量：${_state.value.systemVolume}/${_state.value.maxSystemVolume}")
        appendLine("均衡 ${_state.value.eqBandCount} 段 / 限幅 ${_state.value.limiterActive}")
        appendLine("响度均衡：${_state.value.levelerStrength} / 1000（本机支持：${_state.value.levelerAvailable}）")
        appendLine()
        appendLine("【ROOT】")
        appendLine(if (RootShell.cachedSu != null) "su：已授权（${RootShell.cachedSu}）" else "su：未授权")
        val client = rootClient
        if (client != null) {
            // 用状态文件的心跳判断存活：diagnose() 是同步方法，不能直接调 suspend 的 isRunning()
            val status = client.readStatusSync()
            appendLine("守护进程运行中：${status?.isAlive() == true}")
            appendLine("守护进程模式：${status?.mode ?: "NONE"}")
            appendLine("守护进程输出路由：${status?.route ?: "（未知）"}（耳机暂停：${status?.paused == true}）")
            appendLine("多段压缩（MBC）：${if (status?.mbcAvailable == true) "可用" else "不可用"}")
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
        presetMaxBoost = preset.boostMillibel.coerceAtMost(MaxBoostMillibel)
        presetMinBoost = preset.minBoostMillibel.coerceIn(0, presetMaxBoost)
        val volume = readSystemVolume()
        val effective = Preset.effectiveBoostMillibel(
            presetMaxBoost, presetMinBoost, volume.first, volume.second,
        )
        return AudioState(
            active = mode == EngineMode.ROOT,
            mode = mode,
            error = error,
            boostMillibel = presetMaxBoost,
            minBoostMillibel = presetMinBoost,
            // 没开的时候不给折算值：界面显示「当前实际 +0%」才不误导
            effectiveBoostMillibel = if (mode == EngineMode.ROOT) effective else 0,
            systemVolume = volume.first,
            maxSystemVolume = volume.second,
            eqLevelsDb = levels,
            eqCenterFreqs = freqs,
            // 低音强度存基准值，随音量递减由守护进程按同一条曲线折算
            bassBoostStrength = preset.bassBoostStrength.coerceIn(0, 1000),
            levelerStrength = preset.levelerStrength.coerceIn(0, 1000),
            levelerAvailable = _state.value.levelerAvailable,
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
            // 这里下发的是「上限」，不是折算后的值：守护进程自己会按当前系统音量
            // 折算（App 被杀掉后它还得继续跟着音量键走）。两边用同一条曲线，
            // 所以界面显示的折算值和实际下发的值始终一致。
            boost = s.boostMillibel,
            eq = s.eqLevelsDb,
            bass = s.bassBoostStrength,
            boostMin = s.minBoostMillibel,
            leveler = s.levelerStrength,
        )
    }

    /** 拉起 uid 0 的守护进程，并校验它是否真的建立起了音效链。 */
    private suspend fun startRootDaemon(client: RootDaemonClient) {
        val snapshot = _state.value
        client.writeConfig(
            active = true,
            // 下发上限，守护进程按当前系统音量自己折算
            boost = snapshot.boostMillibel,
            eq = snapshot.eqLevelsDb,
            bass = snapshot.bassBoostStrength,
            boostMin = snapshot.minBoostMillibel,
            leveler = snapshot.levelerStrength,
        )
        val started = runCatching { client.start() }.getOrDefault(false)
        val status = if (started) runCatching { client.readStatus() }.getOrNull() else null
        // 插着耳机时守护进程会故意不建链（status.ok = false、paused = true），
        // 这是正常状态而不是启动失败，不能报错。
        if (status?.paused == true) {
            _state.value = _state.value.copy(
                active = true,
                mode = EngineMode.ROOT,
                limiterActive = false,
                routeLabel = status.route.takeIf { it.isNotBlank() } ?: _state.value.routeLabel,
                pausedForHeadset = true,
                error = null,
            )
            Log.i(TAG, "ROOT 守护进程就绪，但当前输出为 ${status.route}，已暂停处理")
            return
        }
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
            levelerAvailable = status.mbcAvailable,
            routeLabel = status.route.takeIf { it.isNotBlank() } ?: _state.value.routeLabel,
            pausedForHeadset = status.paused,
            error = null,
        )
        Log.i(
            TAG,
            "ROOT 守护进程就绪 pid=${status.pid} mode=${status.mode} " +
                "bands=${status.freqs.size} mbc=${status.mbcAvailable}",
        )
    }

    /** 当前输出路由的可读描述（守护进程读不到时用来兜底）。 */
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
