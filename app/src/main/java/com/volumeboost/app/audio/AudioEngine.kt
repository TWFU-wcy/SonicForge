package com.volumeboost.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
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

/** 引擎工作模式，直接决定 UI 上哪些控件是"真的可用"。 */
enum class EngineMode {
    /** 未开启。 */
    OFF,

    /** 完整模式：增益 + 均衡 + 限幅全部生效。 */
    FULL,

    /** ROOT 模式：整条音效链跑在 uid 0 的守护进程里，不受厂商音效独占限制。 */
    ROOT,

    /** 降级模式：只有 LoudnessEnhancer，没有限幅，增益被强制压低。 */
    DEGRADED,

    /** 二次降级：用硬件均衡器整体抬升代替增益，没有限幅器。 */
    EQ_BOOST,

    /** 系统不支持任何输出侧效果。 */
    UNSUPPORTED,
}

data class AudioState(
    val active: Boolean = false,
    val mode: EngineMode = EngineMode.OFF,
    val error: String? = null,
    /** 目标增益，单位 1/100 dB。 */
    val boostMillibel: Int = 0,
    /** 当前硬件均衡器各频段增益，长度 = [eqCenterFreqs].size。 */
    val eqLevelsDb: IntArray = IntArray(0),
    /** 当前硬件均衡器的真实中心频率（Hz），由系统返回，不是写死的。 */
    val eqCenterFreqs: IntArray = IntArray(0),
    /** 低音补偿强度 0..1000。 */
    val bassBoostStrength: Int = 0,
    /** 虚拟环绕强度 0..1000。 */
    val virtualizerStrength: Int = 0,
    /** 虚拟环绕是否可用（仅耳机/蓝牙输出有意义）。 */
    val virtualizerAvailable: Boolean = false,
    /** 限幅器是否在工作。 */
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

    /** 是否至少有一个效果真的在跑。 */
    val supported: Boolean
        get() = mode == EngineMode.FULL || mode == EngineMode.DEGRADED ||
            mode == EngineMode.EQ_BOOST || mode == EngineMode.ROOT
}

/**
 * 音频引擎：把效果链挂到"全局混音输出"（audioSession = 0），从而放大所有 App 的声音。
 *
 * 核心链路是一条 [DynamicsProcessing]，顺序由系统保证：
 *
 *     inputGain（增益） → preEq（均衡） → limiter（限幅） → 输出
 *
 * 这样做的关键原因：
 * 1. 增益在限幅器**之前**，所以推上去的峰值一定会被限幅器压回 0 dBFS 以下，不会削波；
 * 2. 均衡也在限幅器之前，抬低频不会把已经限好的信号重新推出界；
 * 3. 多个独立 AudioEffect 之间的串联顺序系统并不保证，只有放进同一个
 *    DynamicsProcessing 内部，顺序才是确定的。
 *
 * 如果系统拒绝在全局会话上创建 DynamicsProcessing，则退化为
 * [LoudnessEnhancer] 单效果（无限幅），并把增益上限压到 +8 dB。
 *
 * 有 ROOT（KernelSU / SukiSU 等提供的 su）时，整条链会交给 uid 0 的守护进程，
 * 从而绕开厂商音效对全局 session 的独占；没有 ROOT 时退回进程内效果链。
 * 不需要 Shizuku / MediaProjection，也不申请 RECORD_AUDIO。
 */
class AudioEngine(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var dynamics: DynamicsProcessing? = null
    private var loudness: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null
    private var virtualizer: Virtualizer? = null

    /** ROOT 守护进程客户端；为 null 或 su 不可用时走进程内效果链。 */
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
            error = null,
        )
        Log.i(TAG, "已接管运行中的 ROOT 守护进程 pid=${status.pid} mode=${status.mode} boost=${cfg?.boost}")
    }

    // ── 开关 ────────────────────────────────────────────────────────────────

    /** 启用效果链；返回是否至少有一个效果真的在跑。 */
    @Synchronized
    fun enable(preset: Preset): Boolean {
        if (_state.value.active) return true

        // ROOT 优先：音效跑在 uid 0 的守护进程里，能绕过厂商音效对 session 0 的独占。
        val client = rootClient
        if (client != null) {
            if (RootShell.cachedSu != null) {
                releaseAll()
                _state.value = buildState(preset, EngineMode.ROOT, null, limiter = true)
                rootScope.launch { startRootDaemon(client) }
                Log.i(TAG, "引擎已启动 ROOT: gain=${preset.boostMillibel / 100f}dB")
                return true
            }
            // su 探测还没完成（用户可能还没点过 KernelSU 的授权框）：
            // 先按 ROOT 展示，后台探测；探测失败再退回进程内链路。
            releaseAll()
            _state.value = buildState(preset, EngineMode.ROOT, null, limiter = true)
            rootScope.launch {
                val su = runCatching { RootShell.resolve() }.getOrNull()
                if (su == null) {
                    Log.w(TAG, "未检测到 su，退回进程内效果链")
                    enableInProcess(preset)
                } else {
                    startRootDaemon(client)
                }
            }
            return true
        }
        return enableInProcess(preset)
    }

    /** 进程内效果链：FULL → DEGRADED → EQ_BOOST，全部失败才算不支持。 */
    @Synchronized
    private fun enableInProcess(preset: Preset): Boolean {
        releaseAll()

        val errors = mutableListOf<String>()

        // 1) 首选：全局会话上的 DynamicsProcessing（增益 + 均衡 + 限幅）
        val full = runCatching { openFull(preset) }
            .onFailure { errors += "DynamicsProcessing: ${it.message}" }
            .getOrNull()

        if (full != null) {
            _state.value = buildState(preset, EngineMode.FULL, null, limiter = true)
            Log.i(TAG, "引擎已启动 FULL: bands=${_state.value.eqBandCount} " +
                "gain=${preset.boostMillibel / 100f}dB")
            return true
        }

        // 2) 退化：只有 LoudnessEnhancer，没有限幅器
        val degraded = runCatching { openDegraded(preset) }
            .onFailure { errors += "LoudnessEnhancer: ${it.message}" }
            .getOrNull()

        if (degraded != null) {
            _state.value = buildState(
                preset, EngineMode.DEGRADED,
                error = "系统未提供限幅器，已降级运行（增益上限 +8 dB）",
                limiter = false,
            )
            Log.w(TAG, "引擎已启动 DEGRADED（无限幅）")
            return true
        }

        // 3) 二次降级：用均衡器把所有频段整体抬升（没有限幅器）
        val eqBoost = runCatching { openEqBoost(preset) }
            .onFailure { errors += "Equalizer: ${it.message}" }
            .getOrNull()

        if (eqBoost != null) {
            _state.value = buildState(
                preset, EngineMode.EQ_BOOST,
                error = "当前机型不支持增益/限幅效果，已改用均衡器整体提升（无限幅，建议系统音量留 20% 余量）",
                limiter = false,
            )
            Log.w(TAG, "引擎已启动 EQ_BOOST（无限幅）")
            return true
        }

        releaseAll()
        _state.value = AudioState(
            active = false,
            mode = EngineMode.UNSUPPORTED,
            error = buildString {
                append("系统拒绝了音效创建")
                if (errors.isNotEmpty()) append("：").append(errors.first().take(120))
            },
            mono = readSystemMono(),
        )
        Log.e(TAG, "引擎启动失败: ${errors.joinToString("; ")}")
        return false
    }

    /** 关闭并释放全部效果。 */
    @Synchronized
    fun disable() {
        if (_state.value.mode == EngineMode.ROOT) {
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
            return
        }
        releaseAll()
        _state.value = _state.value.copy(
            active = false,
            mode = EngineMode.OFF,
            limiterActive = false,
            eqLevelsDb = IntArray(0),
            eqCenterFreqs = IntArray(0),
            error = null,
        )
    }

    // ── 参数调节 ────────────────────────────────────────────────────────────

    @Synchronized
    fun setBoost(millibel: Int) {
        if (_state.value.mode == EngineMode.ROOT) {
            val v = millibel.coerceIn(0, MaxBoostMillibel)
            _state.value = _state.value.copy(boostMillibel = v, presetId = CustomPresetId)
            pushRootConfig()
            return
        }
        val ceiling = when (_state.value.mode) {
            EngineMode.DEGRADED -> DegradedMaxMillibel
            EngineMode.EQ_BOOST -> eqBoostCeiling()
            else -> MaxBoostMillibel
        }
        val v = millibel.coerceIn(0, ceiling)
        val applied = when (_state.value.mode) {
            EngineMode.FULL -> runCatching {
                dynamics?.setInputGainAllChannelsTo(v / 100f)
                true
            }.getOrDefault(false)

            EngineMode.DEGRADED -> runCatching {
                loudness?.setTargetGain(v)
                true
            }.getOrDefault(false)

            EngineMode.EQ_BOOST -> runCatching {
                val eq = equalizer ?: return@runCatching false
                for (i in 0 until eq.numberOfBands.toInt()) {
                    eq.setBandLevel(i.toShort(), v.toShort())
                }
                true
            }.getOrDefault(false)

            else -> false
        }
        if (!applied) return
        _state.value = _state.value.copy(boostMillibel = v, presetId = CustomPresetId)
    }

    @Synchronized
    fun setEqBand(band: Int, levelDb: Int) {
        val freqs = _state.value.eqCenterFreqs
        if (band !in freqs.indices) return
        if (_state.value.mode == EngineMode.ROOT) {
            val next = _state.value.eqLevelsDb.copyOf()
            if (band !in next.indices) return
            next[band] = levelDb.coerceIn(MinEqDb, MaxEqDb)
            _state.value = _state.value.copy(eqLevelsDb = next, presetId = CustomPresetId)
            pushRootConfig()
            return
        }
        if (_state.value.mode != EngineMode.FULL) return
        val v = levelDb.coerceIn(MinEqDb, MaxEqDb)
        val next = _state.value.eqLevelsDb.copyOf()
        next[band] = v
        if (!pushBands(next, _state.value.bassBoostStrength)) return
        _state.value = _state.value.copy(eqLevelsDb = next, presetId = CustomPresetId)
    }

    /** 低音补偿：直接加在最低几个 preEq 频段上，因此同样受限幅器保护。 */
    @Synchronized
    fun setBassBoost(strength: Int) {
        val v = strength.coerceIn(0, 1000)
        if (_state.value.mode == EngineMode.ROOT) {
            _state.value = _state.value.copy(bassBoostStrength = v, presetId = CustomPresetId)
            pushRootConfig()
            return
        }
        if (_state.value.mode != EngineMode.FULL) return
        if (!pushBands(_state.value.eqLevelsDb, v)) return
        _state.value = _state.value.copy(bassBoostStrength = v, presetId = CustomPresetId)
    }

    @Synchronized
    fun setVirtualizer(strength: Int) {
        val v = strength.coerceIn(0, 1000)
        if (_state.value.mode == EngineMode.ROOT) {
            _state.value = _state.value.copy(virtualizerStrength = v, presetId = CustomPresetId)
            pushRootConfig()
            return
        }
        if (!_state.value.virtualizerAvailable) return
        val applied = runCatching {
            virtualizer?.let { vz ->
                vz.setStrength(v.toShort())
                vz.enabled = v > 0
                true
            } ?: false
        }.getOrDefault(false)
        if (!applied) return
        _state.value = _state.value.copy(virtualizerStrength = v, presetId = CustomPresetId)
    }

    /**
     * 系统级单声道。真正生效的是 Settings.System 里的 `master_mono`，
     * 也就是「无障碍 → 单声道音频」那个开关，AudioService 会监听它并做下混。
     * 写入需要 WRITE_SETTINGS（用户手动授权），未授权时返回 false。
     */
    @Synchronized
    fun setMono(enabled: Boolean): Boolean {
        if (!Settings.System.canWrite(appContext)) return false
        val ok = runCatching {
            Settings.System.putInt(
                appContext.contentResolver,
                SystemMonoKey,
                if (enabled) 1 else 0,
            )
        }.getOrDefault(false)
        if (ok) _state.value = _state.value.copy(mono = enabled)
        return ok
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
        if (_state.value.mode == EngineMode.ROOT) {
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
            return
        }
        val freqs = _state.value.eqCenterFreqs
        if (freqs.isEmpty()) return

        val levels = IntArray(freqs.size) { i ->
            sampleCurve(preset.eqLevelsDb, freqs[i]).coerceIn(MinEqDb, MaxEqDb)
        }

        val gainOk = runCatching {
            when (_state.value.mode) {
                EngineMode.FULL -> {
                    dynamics?.setInputGainAllChannelsTo(preset.boostMillibel / 100f)
                    pushBands(levels, preset.bassBoostStrength)
                }

                EngineMode.DEGRADED -> {
                    loudness?.setTargetGain(preset.boostMillibel.coerceAtMost(DegradedMaxMillibel))
                    true
                }

                EngineMode.EQ_BOOST -> {
                    val eq = equalizer ?: return@runCatching false
                    val target = preset.boostMillibel.coerceIn(0, eqBoostCeiling())
                    for (i in 0 until eq.numberOfBands.toInt()) {
                        eq.setBandLevel(i.toShort(), target.toShort())
                    }
                    true
                }

                else -> false
            }
        }.getOrDefault(false)

        if (!gainOk) return
        if (_state.value.mode == EngineMode.EQ_BOOST) {
            val flat = IntArray(freqs.size) {
                preset.boostMillibel.coerceIn(0, _state.value.maxBoostMillibel)
            }
            _state.value = _state.value.copy(
                boostMillibel = flat.firstOrNull() ?: 0,
                eqLevelsDb = flat,
                bassBoostStrength = 0,
                presetId = preset.id,
            )
            return
        }
        _state.value = _state.value.copy(
            boostMillibel = preset.boostMillibel.coerceAtMost(_state.value.maxBoostMillibel),
            eqLevelsDb = levels,
            bassBoostStrength = preset.bassBoostStrength,
            presetId = preset.id,
        )
        if (_state.value.virtualizerAvailable) {
            setVirtualizer(preset.virtualizerStrength)
            _state.value = _state.value.copy(presetId = preset.id)
        }
    }

    /** 刷新输出路由：决定虚拟环绕是否可用，并同步系统单声道真实状态。 */
    fun refreshRoute() {
        val headset = hasHeadsetOutput()
        _state.value = _state.value.copy(
            routedToBluetooth = isBluetoothA2dpOn(),
            routeLabel = routeLabel(),
            virtualizerAvailable = virtualizer != null && headset,
            mono = readSystemMono(),
        )
    }

    // ── 效果链构建 ──────────────────────────────────────────────────────────

    /**
     * 构建完整链路。返回 null 表示系统拒绝了全局会话上的 DynamicsProcessing。
     */
    private fun openFull(preset: Preset): DynamicsProcessing? {
        // preEq 频段数量由设备实现决定：从多到少逐个"真建一次"，失败就换下一个。
        for (candidate in BandCountCandidates) {
            val freqs = pickFrequencies(candidate)
            val levels = IntArray(candidate) { i ->
                sampleCurve(preset.eqLevelsDb, freqs[i]).coerceIn(MinEqDb, MaxEqDb)
            }
            val gainDb = (preset.boostMillibel / 100f).coerceIn(MinInputGainDb, MaxInputGainDb)

            val built = runCatching {
                val builder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    ChannelCount,
                    true, candidate,      // preEq
                    false, 0,             // mbc 不用
                    false, 0,             // postEq 不用
                    true,                 // limiter
                )
                builder.setInputGainAllChannelsTo(gainDb)
                builder.setPreEqAllChannelsTo(buildEq(candidate, freqs, levels, preset.bassBoostStrength))
                builder.setLimiterAllChannelsTo(
                    // (inUse, enabled, linkGroup, attack, release, ratio, threshold, postGain)
                    DynamicsProcessing.Limiter(true, true, 0, 0.001f, 0.080f, 20f, LimiterThresholdDb, 0f)
                )

                val fx = DynamicsProcessing(GlobalAudioSession, EffectPriority, builder.build())
                val ok = fx.setEnabled(true) == AudioEffect.SUCCESS && fx.enabled
                if (!ok) {
                    runCatching { fx.release() }
                    null
                } else {
                    fx
                }
            }.getOrNull()

            if (built != null) {
                dynamics = built
                eqFreqs = freqs
                // 虚拟环绕：只有接着耳机/蓝牙时才有意义，扬声器上创建了也不启用
                virtualizer = runCatching {
                    Virtualizer(EffectPriority, GlobalAudioSession).apply {
                        if (strengthSupported) setStrength(preset.virtualizerStrength.toShort())
                        enabled = false
                    }
                }.getOrNull()
                return built
            }
        }
        return null
    }

    private fun openDegraded(preset: Preset): LoudnessEnhancer? {
        val fx = LoudnessEnhancer(GlobalAudioSession)
        fx.setTargetGain(preset.boostMillibel.coerceAtMost(DegradedMaxMillibel))
        val rc = fx.setEnabled(true)
        if (rc != AudioEffect.SUCCESS || !runCatching { fx.enabled }.getOrDefault(false)) {
            runCatching { fx.release() }
            return null
        }
        loudness = fx
        return fx
    }

    /**
     * 二次降级：用 [Equalizer] 把所有频段整体抬升，等效于一个宽带增益。
     * 没有限幅器，所以上限压在 +12 dB，并提示用户留出系统音量余量。
     */
    private fun openEqBoost(preset: Preset): Equalizer? {
        val fx = Equalizer(EffectPriority, GlobalAudioSession)
        val bands = fx.numberOfBands.toInt()
        val range = fx.bandLevelRange
        if (bands <= 0 || range == null || range.size < 2) {
            runCatching { fx.release() }
            return null
        }
        val target = preset.boostMillibel.coerceIn(0, minOf(range[1].toInt(), EqBoostMaxMillibel))
        for (i in 0 until bands) {
            fx.setBandLevel(i.toShort(), target.toShort())
        }
        if (fx.setEnabled(true) != AudioEffect.SUCCESS || !runCatching { fx.enabled }.getOrDefault(false)) {
            runCatching { fx.release() }
            return null
        }
        equalizer = fx
        eqFreqs = IntArray(bands) { i -> fx.getCenterFreq(i.toShort()) / 1000 }
        return fx
    }

    /** 均衡器抬升模式的可用上限（受硬件 bandLevelRange 限制）。 */
    private fun eqBoostCeiling(): Int {
        val max = runCatching { equalizer?.bandLevelRange?.get(1)?.toInt() }.getOrNull() ?: 0
        return minOf(max, EqBoostMaxMillibel)
    }

    /** 生成一份可复制的音效能力诊断，用来确认机型到底支持什么。 */
    fun diagnose(): String = buildString {
        appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("输出路由：${routeLabel()}")
        appendLine("当前模式：${_state.value.mode}")
        appendLine()
        appendLine("【session 0 创建测试】")
        appendLine(probe("Equalizer") { Equalizer(EffectPriority, GlobalAudioSession) })
        appendLine(probe("BassBoost") { BassBoost(EffectPriority, GlobalAudioSession) })
        appendLine(probe("Virtualizer") { Virtualizer(EffectPriority, GlobalAudioSession) })
        appendLine(probe("LoudnessEnhancer") { LoudnessEnhancer(GlobalAudioSession) })
        appendLine(probe("DynamicsProcessing") { DynamicsProcessing(GlobalAudioSession) })
        appendLine()
        appendLine("【系统注册的全局音效】")
        val all = runCatching { AudioEffect.queryEffects() }.getOrNull()
        if (all.isNullOrEmpty()) {
            appendLine("（空）系统没有注册任何可用的全局音效")
        } else {
            all.forEach { d -> appendLine("· ${d.name} / ${d.implementor}") }
        }
        if (rootClient != null) {
            appendLine()
            appendLine("【ROOT 守护进程】")
            appendLine(if (RootShell.cachedSu != null) "su：已授权（${RootShell.cachedSu}）" else "su：未授权")
            appendLine("日志尾部：")
            appendLine(rootClient?.logTail(10).orEmpty().ifBlank { "（空）" })
        }
    }

    private fun probe(name: String, create: () -> AudioEffect): String = runCatching {
        val fx = create()
        val rc = runCatching { fx.setEnabled(true) }.getOrDefault(AudioEffect.ERROR)
        val enabled = runCatching { fx.enabled }.getOrDefault(false)
        runCatching { fx.release() }
        when {
            rc != AudioEffect.SUCCESS -> "$name：创建成功，启用失败（code=$rc）"
            enabled -> "$name：可用"
            else -> "$name：创建成功但启用无效"
        }
    }.getOrElse { e -> "$name：创建失败 · ${e.message ?: e.javaClass.simpleName}" }

    private fun buildEq(
        bandCount: Int,
        freqs: IntArray,
        levels: IntArray,
        bassStrength: Int,
    ): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, bandCount)
        for (i in 0 until bandCount) {
            eq.setBand(
                i,
                DynamicsProcessing.EqBand(
                    true,
                    freqs[i].toFloat(),
                    totalGainDb(levels.getOrElse(i) { 0 }, freqs[i], bassStrength).toFloat(),
                ),
            )
        }
        return eq
    }

    /** 把当前均衡 + 低音补偿推到效果器上；失败返回 false，UI 就不会假装成功。 */
    private fun pushBands(levels: IntArray, bassStrength: Int): Boolean = runCatching {
        val fx = dynamics ?: return@runCatching false
        val freqs = eqFreqs
        if (freqs.isEmpty()) return@runCatching false
        for (i in freqs.indices) {
            fx.setPreEqBandAllChannelsTo(
                i,
                DynamicsProcessing.EqBand(
                    true,
                    freqs[i].toFloat(),
                    totalGainDb(levels.getOrElse(i) { 0 }, freqs[i], bassStrength).toFloat(),
                ),
            )
        }
        true
    }.getOrDefault(false)

    private fun totalGainDb(eqDb: Int, freq: Int, bassStrength: Int): Float {
        val bass = bassStrength / 1000f * MaxBassExtraDb * bassWeight(freq)
        return (eqDb / 100f + bass).coerceIn(MinEqDb / 100f, MaxEqDb / 100f)
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private fun buildState(
        preset: Preset,
        mode: EngineMode,
        error: String?,
        limiter: Boolean,
    ): AudioState {
        val freqs = if (mode == EngineMode.ROOT) CanonicalEqFreqs else eqFreqs
        val headset = hasHeadsetOutput()
        val ceiling = when (mode) {
            EngineMode.DEGRADED -> DegradedMaxMillibel
            EngineMode.EQ_BOOST -> eqBoostCeiling()
            else -> MaxBoostMillibel
        }
        val levels = if (mode == EngineMode.EQ_BOOST) {
            IntArray(freqs.size) { preset.boostMillibel.coerceIn(0, ceiling) }
        } else {
            IntArray(freqs.size) { i ->
                sampleCurve(preset.eqLevelsDb, freqs[i]).coerceIn(MinEqDb, MaxEqDb)
            }
        }
        return AudioState(
            active = true,
            mode = mode,
            error = error,
            boostMillibel = preset.boostMillibel.coerceAtMost(ceiling),
            eqLevelsDb = levels,
            eqCenterFreqs = freqs,
            bassBoostStrength = preset.bassBoostStrength,
            virtualizerStrength = preset.virtualizerStrength,
            virtualizerAvailable = virtualizer != null && headset,
            limiterActive = limiter,
            mono = readSystemMono(),
            presetId = preset.id,
            routedToBluetooth = isBluetoothA2dpOn(),
            routeLabel = routeLabel(),
            maxBoostMillibel = ceiling,
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
            Log.e(TAG, "ROOT 守护进程启动失败 started=$started status=$status，退回进程内效果链")
            // 守护进程起不来（su 拒绝、app_process 被拦、APK 路径不可读等）时，
            // 至少别让用户完全没声音增强：退回普通进程内链路。
            val fallback = Preset.All.firstOrNull { it.id == snapshot.presetId } ?: Preset.Default
            val ok = enableInProcess(fallback)
            if (!ok) {
                _state.value = _state.value.copy(
                    active = false,
                    mode = EngineMode.UNSUPPORTED,
                    limiterActive = false,
                    error = buildString {
                        append("ROOT 守护进程启动失败")
                        if (reason != null) append("：").append(reason)
                    },
                )
            }
            return
        }
        Log.i(TAG, "ROOT 守护进程就绪 pid=${status.pid} mode=${status.mode} bands=${status.freqs.size}")
    }

    private fun releaseAll() {
        listOf<AudioEffect?>(dynamics, loudness, equalizer, virtualizer).forEach { fx ->
            runCatching {
                fx?.enabled = false
                fx?.release()
            }
        }
        dynamics = null
        loudness = null
        equalizer = null
        virtualizer = null
        eqFreqs = IntArray(0)
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

    /** 取 n 个频段时，在 10 个标准频点上等间距抽样。 */
    private fun pickFrequencies(n: Int): IntArray {
        if (n >= CanonicalEqFreqs.size) return CanonicalEqFreqs.copyOf()
        if (n <= 1) return intArrayOf(CanonicalEqFreqs[CanonicalEqFreqs.size / 2])
        return IntArray(n) { i ->
            CanonicalEqFreqs[(i * (CanonicalEqFreqs.size - 1)) / (n - 1)]
        }
    }

    /** 低音补偿在低频段的权重：越低越满，往中频迅速衰减。 */
    private fun bassWeight(freq: Int): Float = when {
        freq <= 62 -> 1.0f
        freq <= 125 -> 0.70f
        freq <= 250 -> 0.40f
        freq <= 500 -> 0.15f
        else -> 0f
    }

    private var eqFreqs: IntArray = IntArray(0)

    private companion object {
        const val TAG = "AudioEngine"

        /** 全局混音输出会话：挂在这里才能影响所有 App。 */
        const val GlobalAudioSession = 0
        const val EffectPriority = 0
        const val ChannelCount = 2

        /** 系统 preEq 允许的频段数量候选，从多到少试探。 */
        val BandCountCandidates = intArrayOf(10, 8, 6, 5, 3)

        /** 预设曲线的标准频点（Hz）。 */
        val CanonicalEqFreqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        /** 增益上限 +18 dB（限幅器保证不会削波）。 */
        const val MaxBoostMillibel = 1800

        /** 降级模式（无限幅）只允许 +8 dB，再多就一定会破音。 */
        const val DegradedMaxMillibel = 800

        /** 均衡器整体抬升模式的硬上限 +12 dB（无限幅，再高必削波）。 */
        const val EqBoostMaxMillibel = 1200

        /** DynamicsProcessing 输入增益的可用范围。 */
        const val MinInputGainDb = -20f
        const val MaxInputGainDb = 18f

        /** 限幅阈值：留 1.5 dB 余量，避免采样间峰值削波。 */
        const val LimiterThresholdDb = -1.5f

        const val MinEqDb = -1200
        const val MaxEqDb = 1200

        /** 低音补偿最多额外 +6 dB。 */
        const val MaxBassExtraDb = 6f

        /** 自定义参数后预设名显示为"自定义"。 */
        const val CustomPresetId = "custom"

        /** 系统单声道设置项（无障碍 → 单声道音频）。 */
        const val SystemMonoKey = "master_mono"
    }
}
