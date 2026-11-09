package com.sonicforge.app.daemon

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import com.sonicforge.app.audio.Preset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln

/**
 * ROOT 音频守护进程。
 *
 * 由 App 通过 `su -c "CLASSPATH=<apk> app_process ... RootDaemon <cmd> <status>"` 启动，
 * 运行在 **uid 0**，因此 AudioFlinger 不会再以「缺少 MODIFY_AUDIO_ROUTING」为由
 * 拒绝第三方在全局混音 session 0 上创建音效——这正是普通 App 在 HyperOS 上
 * 报「系统拒绝了音效创建」的根因。
 *
 * 进程独立于 App：App 被划掉、被系统回收都不影响它，效果一直生效。
 * App 通过写配置文件下发参数，守护进程每 250ms 轮询一次；状态写回状态文件。
 *
 * 本文件不含任何提权代码，只在系统已经提供 su 时被调用。
 */
object RootDaemon {

    /** 全局混音输出会话。 */
    private const val GlobalSession = 0
    private const val EffectPriority = 0
    private const val ChannelCount = 2

    private const val LimiterThresholdDb = -1.5f
    private const val MinInputGainDb = -20f
    /** 输入增益硬顶，与 App 侧滑杆上限一致（+4 dB）。 */
    private const val MaxInputGainDb = Preset.MaxSliderBoostMillibel / 100f
    private const val MaxBassExtraDb = Preset.BassMaxExtraDbMillibel / 100f
    private const val DegradedMaxMillibel = Preset.MaxSliderBoostMillibel

    private val BandCountCandidates = intArrayOf(10, 8, 6, 5, 3)
    private val CanonicalEqFreqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** 响度均衡用的多段压缩器：低频 / 中频 / 高频三段，各段独立压缩避免低音"抽气"。 */
    private const val MbcBandCount = 3

    /**
     * 状态文件的协议版本。
     *
     * 守护进程是 setsid 脱离 App 的独立 root 进程，装新 APK 并不会杀掉它；
     * 新版本启动时如果发现它在跑就会直接接管，于是永远跑着旧代码。
     * 所以状态文件带上版本号，App 发现版本落后就杀掉重拉。
     *
     * 只要状态文件里新增/改动了字段，这个数字就要 +1。
     */
    private const val ProtocolVersion = 6

    /** 三段的分频点（Hz），也是每段的截止频率。 */
    private val MbcCutoffs = floatArrayOf(200f, 2000f, 20000f)

    /** 多久检查一次输出路由（毫秒）。插拔耳机要能在一秒多内反应过来。 */
    private const val RoutePollMs = 1_200L

    /** 多久读一次系统媒体音量（毫秒）。音量键按住不放时会连发，1.2 秒够跟手。 */
    private const val VolumePollMs = 1_200L

    /**
     * 建链失败后多久重试一次（毫秒）。
     *
     * 曾经只在配置发生变化时才调 [EffectChain.apply]，于是 audioserver 重启把音效
     * 冲掉之后，只要用户不拖滑杆就永远不会重试，界面一直停在「建链失败」。
     * 重试必须放在心跳写入之后，否则排队的重试会把状态文件的心跳卡住。
     */
    private const val RetryBaseMs = 5_000L

    /** 重试间隔上限（毫秒）：连续失败时按指数退避，最多等这么久。 */
    private const val RetryMaxMs = 60_000L

    /** 输出路由快照。 */
    private class RouteInfo(
        /** 只有内置扬声器（或听筒）时才是 true。 */
        val speakerOnly: Boolean,
        /** 给人看的名字：「扬声器」「耳机」「蓝牙耳机」「USB 耳机」。 */
        val label: String,
        /** 是否真的读到了系统数据（读不到时保守放行）。 */
        val detected: Boolean,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val cmdFile = File(args.getOrElse(0) { "/data/local/tmp/sonicforge.cmd" })
        val statusFile = File(args.getOrElse(1) { "/data/local/tmp/sonicforge.status" })
        // 事件日志与状态文件同目录：文件属主是 root，但权限放开到 0666，
        // 普通 App 进程才能直接读出来打包导出，不必再走 logcat。
        val logFile = File(statusFile.parentFile ?: File("/data/local/tmp"), "sf_daemon.log")

        DaemonLog.attach(logFile)
        DaemonLog.i("守护进程启动 uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")

        // 降低被 LMK 回收的概率。
        // 本进程是从 App 的 su 会话里派生的，会继承较高的 oom 权重；
        // 有 root 就能把自己调成"几乎不会被杀"的级别，App 退出后也能继续工作。
        runCatching { File("/proc/self/oom_score_adj").writeText("-900") }
            .onSuccess { DaemonLog.i("oom_score_adj 已设为 -900") }
            .onFailure { DaemonLog.w("oom_score_adj 设置失败: ${it.message}") }


        val chain = EffectChain()
        var lastConfig = ""
        var lastBeat = 0L
        var missingSince = 0L
        // 记住当前生效的配置，路由变化时用它重建音效链
        var activeConfig: DaemonConfig? = null
        // App 下发的原始配置（含增益上限），系统音量变化时用它折算
        var baseConfig: DaemonConfig? = null

        // 建链失败后的重试状态：连续失败按指数退避，成功一次就清零。
        var retryAt = 0L
        var retryFailures = 0

        // 输出路由：只处理内置扬声器。插耳机/连蓝牙/接 USB 时整条链要停下来，
        // 让这些设备按原本的样子出声 —— 它们的耳机口和 DAC 本来就不需要补音量，
        // 硬推只会把耳机推到失真。
        val routeMonitor = RouteMonitor()
        var route = routeMonitor.detect()
        var paused = false
        var lastRouteTick = 0L

        // 系统音量：用户按音量键时增益要跟着降下来。
        // 守护进程独立于 App，App 被杀后没人改配置，所以这里自己读。
        val volumeMonitor = VolumeMonitor()
        var lastVolumeTick = 0L
        var lastAppliedVolume = -1
        // 音效存活校验的节流时间戳
        var lastHealthTick = 0L
        writeStatus(statusFile, chain, null, route, paused)

        /**
         * 应用一份配置，并维护重试状态。
         *
         * 失败时记下下次重试时刻，由主循环在心跳之后补一次重试——不再依赖
         * 「配置文本发生变化」这个前提，否则 audioserver 重启把音效冲掉之后，
         * 只要用户不拖滑杆就永远不会重建。
         */
        fun applyWithRetry(cfg: DaemonConfig) {
            val error = chain.apply(cfg)
            if (error == null) {
                retryFailures = 0
                retryAt = 0L
            } else {
                retryFailures++
                val delayMs = minOf(RetryMaxMs, RetryBaseMs shl (retryFailures - 1).coerceAtMost(4))
                retryAt = System.currentTimeMillis() + delayMs
                DaemonLog.w("建链失败（第 ${retryFailures} 次），${delayMs / 1000} 秒后重试：$error")
            }
        }

        while (true) {
            val text = runCatching { cmdFile.readText() }.getOrDefault("")
            if (text.isEmpty()) {
                // 配置文件被删掉（App 被卸载/清理）：不能让音效无限期挂着
                if (missingSince == 0L) missingSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - missingSince > 5_000L) {
                    chain.release()
                    writeStatus(statusFile, chain, "配置已消失", route, paused)
                    DaemonLog.i("配置文件已消失，退出")
                    return
                }
            } else {
                missingSince = 0L
                if (text != lastConfig) {
                    lastConfig = text
                    val cfg = DaemonConfig.parse(text)
                    if (!cfg.active) {
                        chain.release()
                        writeStatus(statusFile, chain, "已关闭", route, paused)
                        DaemonLog.i("收到关闭指令，退出")
                        return
                    }
                    baseConfig = cfg
                    val volume = volumeMonitor.read()
                    val applied = if (volume.first >= 0) {
                        lastAppliedVolume = volume.first
                        cfg.withVolume(volume.first, volume.second)
                    } else {
                        cfg
                    }
                    activeConfig = applied
                    if (route.speakerOnly) {
                        applyWithRetry(applied)
                        paused = false
                        writeStatus(statusFile, chain, null, route, paused)
                        DaemonLog.i(
                            "应用配置 boost=${applied.boost} bass=${applied.bass} " +
                                "leveler=${applied.leveler} mode=${chain.mode} " +
                                "mbc=${chain.mbcAvailable} err=${chain.error}",
                        )
                    } else {
                        // 非扬声器输出：不建链
                        paused = true
                        writeStatus(statusFile, chain, null, route, paused)
                        DaemonLog.i("当前输出为 ${route.label}，暂停所有处理")
                    }
                    lastBeat = System.currentTimeMillis()
                }
            }

            // 路由轮询：插拔耳机/连断蓝牙后一秒多内切换处理状态
            if (System.currentTimeMillis() - lastRouteTick > RoutePollMs) {
                lastRouteTick = System.currentTimeMillis()
                val latest = routeMonitor.detect()
                if (latest.speakerOnly != route.speakerOnly || latest.label != route.label) {
                    route = latest
                    val cfg = activeConfig
                    if (cfg != null && cfg.active) {
                        if (route.speakerOnly) {
                            // 回到外放：重新建链
                            applyWithRetry(cfg)
                            paused = false
                            writeStatus(statusFile, chain, null, route, paused)
                            DaemonLog.i("输出切回 ${route.label}，恢复处理 mode=${chain.mode} err=${chain.error}")
                        } else {
                            // 切到耳机/蓝牙：释放整条链
                            chain.release()
                            paused = true
                            writeStatus(statusFile, chain, null, route, paused)
                            DaemonLog.i("输出切到 ${route.label}，暂停所有处理")
                        }
                        lastBeat = System.currentTimeMillis()
                    } else {
                        writeStatus(statusFile, chain, null, route, paused)
                    }
                }
            }
            // 系统音量轮询：用户按音量键后增益自动递减，满音量时补偿归零。
            // 放在这里而不是只依赖 App，是因为守护进程会活到 App 被杀之后。
            if (System.currentTimeMillis() - lastVolumeTick > VolumePollMs) {
                lastVolumeTick = System.currentTimeMillis()
                val volume = volumeMonitor.read()
                val base = baseConfig
                if (volume.first >= 0 && base != null && base.active && volume.first != lastAppliedVolume) {
                    lastAppliedVolume = volume.first
                    val applied = base.withVolume(volume.first, volume.second)
                    activeConfig = applied
                    if (route.speakerOnly) {
                        applyWithRetry(applied)
                        writeStatus(statusFile, chain, null, route, paused)
                        DaemonLog.i(
                            "系统音量 ${volume.first}/${volume.second}，折算 boost=${applied.boost} " +
                                "bass=${applied.bass} err=${chain.error}",
                        )
                    }
                    lastBeat = System.currentTimeMillis()
                }
            }
            // 心跳：App 靠 status 里的 ts 判断守护进程是否还活着（不需要 su）。
            // 必须写在重试之前——重试里的 binder 调用可能阻塞几十秒，
            // 放在心跳后面才不会把状态文件冻住、让界面误报「未运行」。
            if (System.currentTimeMillis() - lastBeat > 2_000L) {
                writeStatus(statusFile, chain, null, route, paused)
                lastBeat = System.currentTimeMillis()
            }
            // 建链重试：独立于配置变化，失败后按退避节奏自己爬起来。
            val cfgForRetry = activeConfig
            if (retryAt > 0L && System.currentTimeMillis() >= retryAt &&
                cfgForRetry != null && cfgForRetry.active && route.speakerOnly
            ) {
                retryAt = 0L
                applyWithRetry(cfgForRetry)
                writeStatus(statusFile, chain, null, route, paused)
            }
            // 音效存活校验：audioserver 重启后 Java 里的句柄还在，但底层 effect
            // 已经没了。只看「对象非空」会让守护进程一直报 FULL 其实早没生效。
            // 每 250 ms 一查会白白压一条 binder，这里跟路由轮询一样按 1.2 秒节流。
            if (route.speakerOnly &&
                System.currentTimeMillis() - lastHealthTick > RoutePollMs
            ) {
                lastHealthTick = System.currentTimeMillis()
                if (chain.needsRebuild()) {
                    DaemonLog.w("音效已失效（底层 effect 已随 audioserver 消失），释放后准备重建")
                    chain.release()
                    writeStatus(statusFile, chain, null, route, paused)
                    retryFailures = 0
                    retryAt = System.currentTimeMillis() + RetryBaseMs
                }
            }
            Thread.sleep(250)
        }
    }

    private fun writeStatus(
        file: File,
        chain: EffectChain,
        error: String?,
        route: RouteInfo,
        paused: Boolean,
    ) {
        val text = buildString {
            appendLine("pv=$ProtocolVersion")
            appendLine("ok=${if (chain.ready) 1 else 0}")
            appendLine("mode=${chain.mode}")
            appendLine("pid=${android.os.Process.myPid()}")
            appendLine("bands=${chain.freqs.size}")
            appendLine("freqs=${chain.freqs.joinToString(",")}")
            appendLine("mbc=${if (chain.mbcAvailable) 1 else 0}")
            appendLine("route=${route.label}")
            appendLine("paused=${if (paused) 1 else 0}")
            appendLine("error=${error ?: chain.error ?: ""}")
            appendLine("ts=${System.currentTimeMillis()}")
        }
        runCatching { file.writeText(text) }
        // 守护进程是 root，写出的文件默认属主 root；放开读权限让 App 能读回状态
        runCatching { file.setReadable(true, false) }
    }

    /** 一条音效链：DynamicsProcessing（增益 + 均衡 + 限幅），失败时退化为 LoudnessEnhancer。 */
    private class EffectChain {
        private var dynamics: DynamicsProcessing? = null
        private var loudness: LoudnessEnhancer? = null

        var freqs: IntArray = IntArray(0)
            private set
        var mode: String = "NONE"
            private set
        var error: String? = null
            private set

        /** 本机的 DynamicsProcessing 是否支持多段压缩（响度均衡靠它实现）。 */
        var mbcAvailable: Boolean = false
            private set

        /** 是否建链成功过；[release] 会清掉，用来区分「从没建起来」和「曾经好过又掉了」。 */
        private var everReady = false

        val ready: Boolean get() = dynamics != null || loudness != null

        /**
         * 音效是否已经失效，需要释放后重建。
         *
         * audioserver 重启会把底层 effect 端掉，但 Java 里的句柄仍然非空，
         * [ready] 依然为 true——于是守护进程一直报 FULL，实际早就没处理了。
         *
         * 不能查 `enabled`：它读的是 AudioEffect 里缓存的 mEnabled 字段，
         * audioserver 死掉后那个缓存不会更新，照样返回 true（实测确认过）。
         * 必须发一次真正的 binder 调用——读一个参数，底层没了就会抛
         * DEAD_OBJECT，这才是可靠的判据。
         */
        fun needsRebuild(): Boolean {
            if (!ready) return false
            val fx: AudioEffect = dynamics ?: loudness ?: return false
            // 必须发一次真正的 binder 调用：`enabled` 读的是 AudioEffect 里缓存的
            // mEnabled 字段，audioserver 死后不会更新，照样返回 true（实测确认）。
            // setEnabled 每次都会走 setParameter；底层 effect 已不存在时返回
            // ERROR_DEAD_OBJECT 或抛 IllegalStateException，两种都算失效。
            val status = runCatching { fx.setEnabled(true) }
                .getOrElse { return true }
            return status == AudioEffect.ERROR_DEAD_OBJECT
        }

        fun apply(cfg: DaemonConfig): String? {
            if (!ensure()) return error

            dynamics?.let { dp ->
                val gainDb = (cfg.boost / 100f).coerceIn(MinInputGainDb, MaxInputGainDb)
                runCatching { dp.setInputGainAllChannelsTo(gainDb) }
                for (i in freqs.indices) {
                    val band = DynamicsProcessing.EqBand(
                        true,
                        freqs[i].toFloat(),
                        totalGainDb(sampleCurve(cfg.eq, freqs[i]), freqs[i], cfg.bass),
                    )
                    runCatching { dp.setPreEqBandAllChannelsTo(i, band) }
                }
                applyLeveler(dp, cfg.leveler)
            }

            loudness?.let { le ->
                runCatching { le.setTargetGain(cfg.boost.coerceIn(0, DegradedMaxMillibel)) }
            }

            return null
        }

        /**
         * 响度均衡：三段多段压缩（MBC）。
         *
         * 抖音 / 短视频 / 不同片源之间的音量忽大忽小，本质是**响度动态范围**太大。
         * 压缩器把超过阈值的部分按比例压下去，再整体加回补偿增益（makeup），
         * 结果就是：小声的部分被抬起来、大声的部分不再炸耳，听感被"拉平"。
         *
         * 分三段而不是单段，是因为单段压缩一遇到低频就会"抽气"（bass pumping）——
         * 低音一响整条链都被压，人声跟着一沉一浮。分成低/中/高三段各管一段，
         * 低频只压自己那段，人声不会被低音带着抖。
         *
         * strength = 0 时整段旁路，链路行为和没有这个功能完全一致。
         */
        private fun applyLeveler(dp: DynamicsProcessing, strength: Int) {
            if (!mbcAvailable) return
            val s = (strength.coerceIn(0, 1000) / 1000f)
            val on = s > 0.01f

            val thresholdDb = -12f - 18f * s
            val ratio = 2f + 4f * s
            val attackMs = 20f - 15f * s
            val releaseMs = 400f - 150f * s
            // 补偿增益：按"典型节目电平 -18 dBFS 会被压掉多少"来补，
            // 这样打开均衡后整体响度基本不变，只是动态范围变小。
            val makeupDb = ((-18f - thresholdDb) * (1f - 1f / ratio)).coerceIn(0f, 12f)

            val mbc = DynamicsProcessing.Mbc(true, on, MbcBandCount)
            for (i in 0 until MbcBandCount) {
                mbc.setBand(
                    i,
                    DynamicsProcessing.MbcBand(
                        on,               // enabled
                        MbcCutoffs[i],    // cutoffFrequency
                        attackMs,         // attackTime
                        releaseMs,        // releaseTime
                        ratio,            // ratio
                        thresholdDb,      // threshold
                        6f,               // kneeWidth
                        -70f,             // noiseGateThreshold（放到最低，相当于关）
                        1f,               // expanderRatio（1 = 不扩展）
                        0f,               // preGain
                        makeupDb,         // postGain
                    )
                )
            }
            runCatching { dp.setMbcAllChannelsTo(mbc) }
                .onFailure { DaemonLog.w("响度均衡下发失败: ${it.message}") }
        }

        private fun ensure(): Boolean {
            if (ready) return true

            // 记下每个候选的真实异常：只有知道是 DEAD_OBJECT 还是 BAD_VALUE，
            // 才能判断是「audioserver 正在重启」还是「本机 DSP 不支持这个配置」。
            var lastError: String? = null
            var attempts = 0

            // 首选：DynamicsProcessing（增益 → 均衡 → 多段压缩 → 限幅 一条链，顺序确定）
            // 先按「带 MBC（响度均衡）」建链，失败再退回不带 MBC 的同一条链：
            // 少数机型的 DSP 不支持多段压缩，但增益/均衡/限幅仍然可用。
            for (withMbc in booleanArrayOf(true, false)) {
                for (candidate in BandCountCandidates) {
                    val candidateFreqs = pickFrequencies(candidate)
                    attempts++
                    var failure: String? = null
                    val built = runCatching {
                        val builder = DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            ChannelCount,
                            true, candidate,
                            withMbc, if (withMbc) MbcBandCount else 0,
                            false, 0,
                            true,
                        )
                        builder.setInputGainAllChannelsTo(0f)
                        val eq = DynamicsProcessing.Eq(true, true, candidate)
                        for (i in 0 until candidate) {
                            eq.setBand(i, DynamicsProcessing.EqBand(true, candidateFreqs[i].toFloat(), 0f))
                        }
                        builder.setPreEqAllChannelsTo(eq)
                        if (withMbc) {
                            val mbc = DynamicsProcessing.Mbc(true, false, MbcBandCount)
                            for (i in 0 until MbcBandCount) {
                                mbc.setBand(
                                    i,
                                    DynamicsProcessing.MbcBand(
                                        false, MbcCutoffs[i], 20f, 400f, 2f, -12f, 6f, -70f, 1f, 0f, 0f,
                                    )
                                )
                            }
                            builder.setMbcAllChannelsTo(mbc)
                        }
                        builder.setLimiterAllChannelsTo(
                            DynamicsProcessing.Limiter(true, true, 0, 0.001f, 0.080f, 20f, LimiterThresholdDb, 0f)
                        )
                        val fx = DynamicsProcessing(GlobalSession, EffectPriority, builder.build())
                        val enableStatus = fx.setEnabled(true)
                        if (enableStatus == AudioEffect.SUCCESS && fx.enabled) {
                            fx
                        } else {
                            runCatching { fx.release() }
                            failure = describe("启用返回 $enableStatus（bands=$candidate mbc=$withMbc）")
                            null
                        }
                    }.getOrElse { e ->
                        failure = describe("bands=$candidate mbc=$withMbc", e)
                        null
                    }

                    if (built != null) {
                        dynamics = built
                        freqs = candidateFreqs
                        mbcAvailable = withMbc
                        mode = "FULL"
                        everReady = true
                        DaemonLog.i("FULL 链路已建立 bands=$candidate mbc=$withMbc")
                        return true
                    }
                    if (failure != null) lastError = failure
                }
            }

            // 退化：只有 LoudnessEnhancer
            var leFailure: String? = null
            val le = runCatching {
                LoudnessEnhancer(GlobalSession).apply {
                    val enableStatus = setEnabled(true)
                    if (enableStatus != AudioEffect.SUCCESS) {
                        leFailure = describe("LoudnessEnhancer 启用返回 $enableStatus")
                    }
                }
            }.onFailure { e ->
                leFailure = describe("LoudnessEnhancer 创建失败", e)
            }.getOrNull()

            if (le != null && runCatching { le.enabled }.getOrDefault(false)) {
                loudness = le
                mode = "DEGRADED"
                everReady = true
                DaemonLog.w("降级为 LoudnessEnhancer 单效果")
                return true
            }

            // 把失败原因说清楚：以前这里写死「系统拒绝在 session 0 创建音效」，
            // 但真正的原因常常是 audioserver 正在重启（DEAD_OBJECT），
            // 一句假话会把排查方向带偏好几个小时。
            val detail = leFailure ?: lastError
            error = buildString {
                append("无法在全局混音会话创建音效（尝试 $attempts 种配置）")
                if (detail != null) append("：").append(detail)
                if (everReady) append("；此前曾建链成功，可能是 audioserver 重启导致失效")
            }
            DaemonLog.e(error!!)
            return false
        }

        /** 把异常翻译成一句能直接看懂的话，带上错误码。 */
        private fun describe(prefix: String, e: Throwable? = null): String {
            if (e == null) return prefix
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            // android.media.audiofx 的异常里通常带着 status_t 数字，
            // -3 = DEAD_OBJECT（audioserver 刚重启）、-22 = BAD_VALUE（参数不被接受）
            return "$prefix：${e.javaClass.simpleName}: $msg"
        }

        fun release() {
            listOf<AudioEffect?>(dynamics, loudness).forEach { fx ->
                runCatching {
                    fx?.enabled = false
                    fx?.release()
                }
            }
            dynamics = null
            loudness = null
            freqs = IntArray(0)
            mode = "NONE"
            // mbcAvailable 保留：它描述的是「本机 DSP 能力」，不是当前链的状态。
            // 但 ready 变 false 后 ok=0，界面不会再误报成在跑。
        }
    }

    private fun totalGainDb(eqDb: Int, freq: Int, bassStrength: Int): Float {
        val bass = bassStrength / 1000f * MaxBassExtraDb * bassWeight(freq)
        return (eqDb / 100f + bass).coerceIn(-12f, 12f)
    }
    private fun bassWeight(freq: Int): Float = when {
        freq <= 62 -> 1.0f
        freq <= 125 -> 0.70f
        freq <= 250 -> 0.40f
        freq <= 500 -> 0.15f
        else -> 0f
    }

    private fun pickFrequencies(n: Int): IntArray {
        if (n >= CanonicalEqFreqs.size) return CanonicalEqFreqs.copyOf()
        if (n <= 1) return intArrayOf(CanonicalEqFreqs[CanonicalEqFreqs.size / 2])
        return IntArray(n) { i -> CanonicalEqFreqs[(i * (CanonicalEqFreqs.size - 1)) / (n - 1)] }
    }

    /** 把 App 下发的 10 点曲线按对数频率插值到硬件真实频段上。 */
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

    /** App 下发的配置。 */
    private class DaemonConfig(
        val active: Boolean,
        /** 系统音量最低时的增益上限，单位 1/100 dB。 */
        val boost: Int,
        val eq: IntArray,
        /** 系统音量最低时的低音强度 0..1000。 */
        val bass: Int,
        /** 系统音量最大时保留的补偿，单位 1/100 dB（默认 0 = 完全让路）。 */
        val boostMin: Int = 0,
        /** 响度均衡强度 0..1000，0 = 关闭。 */
        val leveler: Int = 0,
    ) {
        /**
         * 按系统音量折算出一份实际要下发的配置。
         *
         * 直接调用 App 侧的 [Preset.effectiveBoostMillibel]：两条链必须用**同一个函数**，
         * 否则界面显示的折算值和实际下发的值会对不上，也容易出现"某档音量比满音量还响"。
         * 守护进程独立于 App 运行，用户按音量键时不能指望 App 还活着去改配置。
         */
        fun withVolume(volume: Int, maxVolume: Int): DaemonConfig {
            if (maxVolume <= 0) return this
            // 配置可能来自更早的版本（那时滑杆能拖到 18 dB），这里再夹一次硬顶
            val max = boost.coerceIn(0, Preset.MaxSliderBoostMillibel)
            val min = boostMin.coerceIn(0, max)
            val effBoost = Preset.effectiveBoostMillibel(max, min, volume, maxVolume)
            val effBass = Preset.effectiveBass(bass, volume, maxVolume)
            if (effBoost == boost && effBass == bass) return this
            return DaemonConfig(active, effBoost, eq, effBass, boostMin, leveler)
        }

        companion object {
            fun parse(text: String): DaemonConfig {
                var active = true
                var boost = Preset.MaxSliderBoostMillibel
                var bass = 0
                var boostMin = 0
                var leveler = 0
                var eq = IntArray(0)
                text.lineSequence().forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    when (key) {
                        "active" -> active = value != "0"
                        "boost" -> boost = value.toIntOrNull() ?: boost
                        "boost_min" -> boostMin = value.toIntOrNull() ?: boostMin
                        "bass" -> bass = value.toIntOrNull() ?: bass
                        "leveler" -> leveler = value.toIntOrNull() ?: leveler
                        "eq" -> eq = value.split(',')
                            .mapNotNull { it.trim().toIntOrNull() }
                            .toIntArray()
                    }
                }
                return DaemonConfig(active, boost, eq, bass, boostMin, leveler)
            }
        }
    }

    /**
     * 输出路由探测。
     *
     * 守护进程是独立进程，拿不到 App 的 AudioManager，所以直接读
     * `dumpsys media.audio_flinger` 里的 `Output devices:` 行。这行是
     * AudioFlinger 对每条 output thread 当前接的设备，比 `dumpsys audio`
     * 的流表更直接，而且整个 dump 只要几十毫秒。
     *
     * 只有「内置扬声器 / 听筒」才算外放；耳机、蓝牙、USB、HDMI 一律算
     * 非外放，此时把整条音效链停掉。
     */
    private class RouteMonitor {
        private val outputLine = Regex("""Output devices:\s*(0x[0-9a-fA-F]+)\s*\(([^)]*)\)""")

        fun detect(): RouteInfo {
            val text = runCatching {
                val process = ProcessBuilder("dumpsys", "media.audio_flinger")
                    .redirectErrorStream(true)
                    .start()
                val out = process.inputStream.bufferedReader().readText()
                process.waitFor()
                out
            }.getOrNull()

            if (text.isNullOrBlank()) {
                // 读不到就保守放行：宁可增强着，也不要让用户觉得开关失灵
                return RouteInfo(speakerOnly = true, label = "扬声器", detected = false)
            }

            val names = outputLine.findAll(text).map { it.groupValues[2].trim() }.toList()
            if (names.isEmpty()) {
                return RouteInfo(speakerOnly = true, label = "扬声器", detected = false)
            }

            // 排除只在通话/回环里出现的线程，它们不影响媒体播放
            val media = names.filterNot { name ->
                name.contains("TELEPHONY_TX") || name.contains("REMOTE_SUBMIX") ||
                    name.contains("FM_TUNER") || name.contains("NONE")
            }
            if (media.isEmpty()) {
                return RouteInfo(speakerOnly = true, label = "扬声器", detected = false)
            }

            val hasNonSpeaker = media.any { name -> !isBuiltinOutput(name) }
            val label = when {
                !hasNonSpeaker -> "扬声器"
                media.any { it.contains("BLUETOOTH_A2DP") || it.contains("BLE_HEADSET") || it.contains("BLE_SPEAKER") } -> "蓝牙耳机"
                media.any { it.contains("USB_HEADSET") || it.contains("USB_DEVICE") } -> "USB 耳机"
                media.any { it.contains("HEARING_AID") } -> "助听器"
                media.any { it.contains("HDMI") || it.contains("LINE") } -> "外接设备"
                else -> "耳机"
            }
            return RouteInfo(speakerOnly = !hasNonSpeaker, label = label, detected = true)
        }

        private fun isBuiltinOutput(name: String): Boolean =
            name.contains("SPEAKER") || name.contains("EARPIECE")
    }

    /**
     * 系统媒体音量监测。
     *
     * 增益要随系统音量递减：小音量时补得多，拉满时几乎不补，避免削波破音。
     * App 活着的时候它自己会算并下发，但守护进程活得更久（App 被划掉也还在），
     * 所以这里必须能独立读音量。
     *
     * 用 `cmd media_session volume --stream 3 --get` 读，输出形如
     * `[V] volume is 5 in range [0..15]`，几十毫秒就能返回。
     */
    private class VolumeMonitor {
        private val line = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)]""")

        /** @return 当前音量 to 最大音量；读不到时返回 `-1 to 15`。 */
        fun read(): Pair<Int, Int> {
            val text = runCatching {
                val process = ProcessBuilder("cmd", "media_session", "volume", "--stream", "3", "--get")
                    .redirectErrorStream(true)
                    .start()
                val out = process.inputStream.bufferedReader().readText()
                process.waitFor()
                out
            }.getOrNull() ?: return -1 to 15

            val match = line.find(text) ?: return -1 to 15
            val current = match.groupValues[1].toIntOrNull() ?: return -1 to 15
            val max = match.groupValues[3].toIntOrNull() ?: return -1 to 15
            if (max <= 0) return -1 to 15
            return current.coerceIn(0, max) to max
        }
    }
}

/**
 * 守护进程的事件日志。
 *
 * 以前所有诊断都走 [android.util.Log]，只进系统 logcat——而普通 App 进程读不到
 * uid 0 的 logcat 行，用户又不可能为了报个 bug 去连 adb。结果就是设置页的
 * 「日志尾部」永远是空的，最关键的失败现场反而拿不到。
 *
 * 所以这里额外写一份到 `files/sf_daemon.log`：文件权限放到 0666，
 * App 直接 readText 就能打包导出；同时仍然打印到 logcat，方便 adb 排查。
 *
 * 只记事件（启动、建链、失败原因、路由切换、音量折算），不记每次心跳，
 * 否则 250 ms 一轮的轮询会瞬间把日志刷满。
 */
private object DaemonLog {

    private const val TAG = "VBRootDaemon"

    private const val MaxBytes = 128 * 1024L

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    /** 绑定日志文件；父目录必须已存在（由 App 的 filesDir 提供）。 */
    fun attach(target: File) {
        file = target
        runCatching {
            if (!target.exists()) target.createNewFile()
            // 守护进程是 root，写出的文件默认 0600；App 要能读就必须放开
            target.setReadable(true, false)
            target.setWritable(true, false)
        }
    }

    fun i(message: String) = write("I", message)

    fun w(message: String) = write("W", message)

    fun e(message: String) = write("E", message)

    private fun write(level: String, message: String) {
        // 先打 logcat：即使写文件失败（权限、磁盘满），adb 那边仍然有记录
        when (level) {
            "E" -> Log.e(TAG, message)
            "W" -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }

        val target = file ?: return
        runCatching {
            rotateIfNeeded(target)
            val line = "${timeFormat.format(Date())} $level $message\n"
            target.appendText(line)
            // root 每次重建文件后权限可能被重置，这里补一次放开读
            target.setReadable(true, false)
        }
    }

    /** 超过上限就把当前文件挪成 .1（只留一份备份），避免无限增长。 */
    private fun rotateIfNeeded(target: File) {
        if (!target.exists() || target.length() < MaxBytes) return
        val backup = File(target.parentFile, target.name + ".1")
        runCatching {
            if (backup.exists()) backup.delete()
            target.renameTo(backup)
            backup.setReadable(true, false)
        }
    }
}
