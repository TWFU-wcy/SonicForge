package com.volumeboost.app.daemon

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import java.io.File
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

    private const val TAG = "VBRootDaemon"

    /** 全局混音输出会话。 */
    private const val GlobalSession = 0
    private const val EffectPriority = 0
    private const val ChannelCount = 2

    private const val LimiterThresholdDb = -1.5f
    private const val MinInputGainDb = -20f
    private const val MaxInputGainDb = 18f
    private const val MaxBassExtraDb = 6f
    private const val DegradedMaxMillibel = 800

    private val BandCountCandidates = intArrayOf(10, 8, 6, 5, 3)
    private val CanonicalEqFreqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    @JvmStatic
    fun main(args: Array<String>) {
        val cmdFile = File(args.getOrElse(0) { "/data/local/tmp/volumeboost.cmd" })
        val statusFile = File(args.getOrElse(1) { "/data/local/tmp/volumeboost.status" })

        Log.i(TAG, "守护进程启动 uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")

        // 降低被 LMK 回收的概率。
        // 本进程是从 App 的 su 会话里派生的，会继承较高的 oom 权重；
        // 有 root 就能把自己调成"几乎不会被杀"的级别，App 退出后也能继续工作。
        runCatching { File("/proc/self/oom_score_adj").writeText("-900") }
            .onSuccess { Log.i(TAG, "oom_score_adj 已设为 -900") }
            .onFailure { Log.w(TAG, "oom_score_adj 设置失败: ${it.message}") }

        val chain = EffectChain()
        var lastConfig = ""
        var lastBeat = 0L
        var missingSince = 0L
        writeStatus(statusFile, chain, null)

        while (true) {
            val text = runCatching { cmdFile.readText() }.getOrDefault("")
            if (text.isEmpty()) {
                // 配置文件被删掉（App 被卸载/清理）：不能让音效无限期挂着
                if (missingSince == 0L) missingSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - missingSince > 5_000L) {
                    chain.release()
                    writeStatus(statusFile, chain, "配置已消失")
                    Log.i(TAG, "配置文件已消失，退出")
                    return
                }
            } else {
                missingSince = 0L
                if (text != lastConfig) {
                    lastConfig = text
                    val cfg = DaemonConfig.parse(text)
                    if (!cfg.active) {
                        chain.release()
                        writeStatus(statusFile, chain, "已关闭")
                        Log.i(TAG, "收到关闭指令，退出")
                        return
                    }
                    val error = chain.apply(cfg)
                    writeStatus(statusFile, chain, error)
                    lastBeat = System.currentTimeMillis()
                    Log.i(TAG, "应用配置 boost=${cfg.boost} bass=${cfg.bass} virt=${cfg.virt} mode=${chain.mode} err=$error")
                }
            }
            if (System.currentTimeMillis() - lastBeat > 2_000L) {
                // 心跳：App 靠 status 里的 ts 判断守护进程是否还活着（不需要 su）
                writeStatus(statusFile, chain, null)
                lastBeat = System.currentTimeMillis()
            }
            Thread.sleep(250)
        }
    }

    private fun writeStatus(file: File, chain: EffectChain, error: String?) {
        val text = buildString {
            appendLine("ok=${if (chain.ready) 1 else 0}")
            appendLine("mode=${chain.mode}")
            appendLine("pid=${android.os.Process.myPid()}")
            appendLine("bands=${chain.freqs.size}")
            appendLine("freqs=${chain.freqs.joinToString(",")}")
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
        private var virtualizer: Virtualizer? = null

        var freqs: IntArray = IntArray(0)
            private set
        var mode: String = "NONE"
            private set
        var error: String? = null
            private set

        val ready: Boolean get() = dynamics != null || loudness != null

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
            }

            loudness?.let { le ->
                runCatching { le.setTargetGain(cfg.boost.coerceIn(0, DegradedMaxMillibel)) }
            }

            applyVirtualizer(cfg.virt)
            return null
        }

        private fun applyVirtualizer(strength: Int) {
            if (strength <= 0) {
                runCatching { virtualizer?.enabled = false }
                return
            }
            val vz = virtualizer ?: runCatching { Virtualizer(EffectPriority, GlobalSession) }
                .onFailure { Log.w(TAG, "Virtualizer 创建失败: ${it.message}") }
                .getOrNull() ?: return
            virtualizer = vz
            runCatching {
                if (vz.strengthSupported) vz.setStrength(strength.coerceIn(0, 1000).toShort())
                vz.enabled = true
            }
        }

        private fun ensure(): Boolean {
            if (ready) return true

            // 首选：DynamicsProcessing（增益 → 均衡 → 限幅 一条链，顺序确定）
            for (candidate in BandCountCandidates) {
                val candidateFreqs = pickFrequencies(candidate)
                val built = runCatching {
                    val builder = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        ChannelCount,
                        true, candidate,
                        false, 0,
                        false, 0,
                        true,
                    )
                    builder.setInputGainAllChannelsTo(0f)
                    val eq = DynamicsProcessing.Eq(true, true, candidate)
                    for (i in 0 until candidate) {
                        eq.setBand(i, DynamicsProcessing.EqBand(true, candidateFreqs[i].toFloat(), 0f))
                    }
                    builder.setPreEqAllChannelsTo(eq)
                    builder.setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(true, true, 0, 0.001f, 0.080f, 20f, LimiterThresholdDb, 0f)
                    )
                    val fx = DynamicsProcessing(GlobalSession, EffectPriority, builder.build())
                    if (fx.setEnabled(true) == AudioEffect.SUCCESS && fx.enabled) {
                        fx
                    } else {
                        runCatching { fx.release() }
                        null
                    }
                }.getOrNull()

                if (built != null) {
                    dynamics = built
                    freqs = candidateFreqs
                    mode = "FULL"
                    Log.i(TAG, "FULL 链路已建立 bands=$candidate")
                    return true
                }
            }

            // 退化：只有 LoudnessEnhancer
            val le = runCatching {
                LoudnessEnhancer(GlobalSession).apply { setEnabled(true) }
            }.onFailure { Log.w(TAG, "LoudnessEnhancer 创建失败: ${it.message}") }.getOrNull()

            if (le != null && runCatching { le.enabled }.getOrDefault(false)) {
                loudness = le
                mode = "DEGRADED"
                Log.w(TAG, "降级为 LoudnessEnhancer 单效果")
                return true
            }

            error = "系统拒绝在 session 0 创建音效"
            return false
        }

        fun release() {
            listOf<AudioEffect?>(dynamics, loudness, virtualizer).forEach { fx ->
                runCatching {
                    fx?.enabled = false
                    fx?.release()
                }
            }
            dynamics = null
            loudness = null
            virtualizer = null
            freqs = IntArray(0)
            mode = "NONE"
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
        val boost: Int,
        val eq: IntArray,
        val bass: Int,
        val virt: Int,
    ) {
        companion object {
            fun parse(text: String): DaemonConfig {
                var active = true
                var boost = 1000
                var bass = 0
                var virt = 0
                var eq = IntArray(0)
                text.lineSequence().forEach { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    when (key) {
                        "active" -> active = value != "0"
                        "boost" -> boost = value.toIntOrNull() ?: boost
                        "bass" -> bass = value.toIntOrNull() ?: bass
                        "virt" -> virt = value.toIntOrNull() ?: virt
                        "eq" -> eq = value.split(',')
                            .mapNotNull { it.trim().toIntOrNull() }
                            .toIntArray()
                    }
                }
                return DaemonConfig(active, boost, eq, bass, virt)
            }
        }
    }
}
