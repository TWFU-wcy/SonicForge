package com.sonicforge.app.root

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 守护进程写回的状态快照。 */
data class DaemonStatus(
    val ok: Boolean,
    val mode: String,
    val pid: Int,
    val freqs: IntArray,
    val error: String?,
    /** 守护进程写入这份状态的时间戳（毫秒），用于判断它是否还活着。 */
    val ts: Long = 0L,
    /** 本机 DSP 是否支持多段压缩（响度均衡依赖它）。 */
    val mbcAvailable: Boolean = false,
    /** 当前输出路由的显示名：「扬声器」「耳机」「蓝牙耳机」等。 */
    val route: String = "",
    /** 是否因为输出到耳机/蓝牙而暂停了全部处理。 */
    val paused: Boolean = false,
    /** 守护进程写这份状态时用的协议版本；0 表示老版本（没有这个字段）。 */
    val protocolVersion: Int = 0,
) {
    /**
     * 心跳在 8 秒内视为存活。
     *
     * `paused` 也要算存活：插着耳机时守护进程故意不建链（ok=0），
     * 但它还在跑、还在盯着路由，拔掉耳机就会自己恢复。
     */
    fun isAlive(now: Long = System.currentTimeMillis()): Boolean =
        (ok || paused) && now - ts in 0..8_000L
}

/** 守护进程当前生效的配置（从 cmd 文件读回）。 */
data class DaemonConfigSnapshot(
    val active: Boolean,
    val boost: Int,
    val eq: IntArray,
    val bass: Int,
    /** 系统音量最大时保留的最小补偿，单位 1/100 dB（默认 0 = 完全让路）。 */
    val boostMin: Int = 0,
    val leveler: Int = 0,
)

/**
 * ROOT 守护进程的客户端。
 *
 * 通信方式刻意做成「文件」而不是 socket：
 * 守护进程是 uid 0 的独立进程，App 被杀掉、重启都不影响它；
 * App 只要重新写一遍配置就能接管，不需要重新拉起进程。
 */
class RootDaemonClient(private val context: Context) {

    private val cmdFile = File(context.filesDir, "sf_daemon.cmd")
    private val statusFile = File(context.filesDir, "sf_daemon.status")
    private val logFile = File(context.filesDir, "sf_daemon.log")

    /**
     * 写音效配置。文件很小，直接同步写即可。
     *
     * 只替换音效那几行，保留 `mixer_*` 行 —— 硬件增益的守护由
     * [writeMixer] 独立维护，两边互不覆盖。
     */
    fun writeConfig(
        active: Boolean,
        boost: Int,
        eq: IntArray,
        bass: Int,
        boostMin: Int = 0,
        leveler: Int = 0,
    ) {
        updateLines { lines ->
            lines.removeAll { keyOf(it) in EffectKeys }
            lines += "active=${if (active) 1 else 0}"
            lines += "boost=$boost"
            lines += "boost_min=$boostMin"
            lines += "eq=${eq.joinToString(",")}"
            lines += "bass=$bass"
            lines += "leveler=$leveler"
        }
    }

    /**
     * 写硬件音量守护配置，交给守护进程维持「系统音量变化后自动补回偏移」。
     *
     * 传 `offsetUnits = 0` 或空列表即可关闭守护。
     */
    fun writeMixer(
        tool: String?,
        card: Int,
        offsetUnits: Int,
        targets: List<MixerControl>,
        bases: Map<String, Int>,
    ) {
        updateLines { lines ->
            lines.removeAll { keyOf(it) in MixerKeys }
            if (tool != null && offsetUnits > 0 && targets.isNotEmpty()) {
                lines += "mixer_tool=$tool"
                lines += "mixer_card=$card"
                lines += "mixer_offset=$offsetUnits"
                lines += "mixer_names=" + targets.joinToString(";") { control ->
                    val base = bases[control.name] ?: control.value ?: 0
                    "${control.name}|$base|${control.min ?: 0}|${control.max ?: 0}"
                }
            }
        }
    }

    /** 关闭硬件音量守护。 */
    fun clearMixer() {
        updateLines { lines -> lines.removeAll { keyOf(it) in MixerKeys } }
    }

    private fun keyOf(line: String): String {
        val idx = line.indexOf('=')
        return if (idx <= 0) "" else line.substring(0, idx).trim()
    }

    /** 读—改—写 cmd 文件，保证两边写入不互相抹掉。 */
    private fun updateLines(transform: (MutableList<String>) -> Unit) {
        runCatching {
            val lines = cmdFile.takeIf { it.exists() }
                ?.readLines()
                ?.filter { it.isNotBlank() }
                ?.toMutableList()
                ?: mutableListOf()
            transform(lines)
            cmdFile.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    /** 拉起守护进程；已在运行则直接返回 true。 */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (isRunning()) {
            // 版本对得上就直接接管；对不上说明跑着的是装新 APK 之前拉起的旧进程，
            // 先杀掉再重新拉，否则新代码永远不会生效。
            val status = readStatus()
            if (status != null && status.protocolVersion >= DAEMON_PROTOCOL_VERSION) {
                return@withContext true
            }
            killStale()
        }
        // 清掉上一次的状态，避免把旧结果当成本次启动成功
        runCatching { statusFile.delete() }
        // 日志文件先建好并放开读权限：守护进程以 root 运行，App 需要能读回日志
        runCatching {
            if (!logFile.exists()) logFile.createNewFile()
            logFile.setReadable(true, false)
            logFile.setWritable(true, false)
        }

        val apk = context.packageCodePath
        // nohup + setsid：su 会话结束后进程要活下来，否则一关屏守护进程就被带走
        val command = "CLASSPATH=$apk nohup setsid app_process /system/bin $DAEMON_CLASS " +
            "${cmdFile.absolutePath} ${statusFile.absolutePath} " +
            ">> ${logFile.absolutePath} 2>&1 < /dev/null &"
        RootShell.exec(command, 15_000L)

        repeat(40) {
            val status = readStatus()
            // paused 也算启动成功：插着耳机时守护进程会故意不建链，
            // 但它已经在盯着路由，拔掉耳机就会自己恢复。
            if (status != null) return@withContext status.ok || status.paused
            delay(150)
        }
        isRunning()
    }

    /** 停止守护进程并清理状态。 */
    suspend fun stop() = withContext(Dispatchers.IO) {
        // 先写 active=0 让守护进程自己释放音效并退出，再兜底 pkill
        runCatching { cmdFile.writeText("active=0\n") }
        delay(600)
        RootShell.exec("pkill -f '$PROCESS_PATTERN'", 6_000L)
        // 保留 cmd 文件，App 重启后还能读回上次的增益数值
        runCatching { statusFile.delete() }
    }

    /**
     * 杀掉版本落后的守护进程。
     *
     * 不走 `stop()`：那条路会先把 cmd 文件覆盖成只有 `active=0`，
     * 把增益/均衡配置全丢了，这里要保住配置只换进程。
     */
    private suspend fun killStale() {
        RootShell.exec("pkill -f '$PROCESS_PATTERN'", 6_000L)
        runCatching { statusFile.delete() }
        delay(400)
    }

    /**
     * 如果正在跑（或残留）的守护进程状态是旧版本写的，把它清掉。
     *
     * 供 App 启动时调用：否则会接管一个旧进程，界面显示的是旧代码写出来的状态
     * （比如没有 route= / mbc= 两行，就被当成「耳机状态未知、响度均衡不可用」）。
     *
     * @return true 表示确实清掉了旧状态。
     */
    suspend fun retireIfStale(): Boolean = withContext(Dispatchers.IO) {
        val status = readStatus() ?: return@withContext false
        if (status.protocolVersion >= DAEMON_PROTOCOL_VERSION) return@withContext false
        Log.i(TAG, "发现旧版本守护进程状态 pv=${status.protocolVersion}，清理并准备重拉")
        killStale()
        true
    }

    suspend fun isRunning(): Boolean = withContext(Dispatchers.IO) {
        val r = RootShell.exec("pgrep -f '$PROCESS_PATTERN'", 5_000L)
        r.output.lineSequence().any { it.trim().toIntOrNull() != null }
    }

    /** 同步读状态文件（只读 App 自己的文件，不走 su）；供 diagnose() 这类同步上下文使用。 */
    fun readStatusSync(): DaemonStatus? = runCatching {
        val text = statusFile.readText()
        if (text.isBlank()) return@runCatching null
        parseStatus(text)
    }.getOrNull()

    suspend fun readStatus(): DaemonStatus? = withContext(Dispatchers.IO) {
        val direct = runCatching { statusFile.readText() }.getOrNull()
        // 真机上 root 进程创建的文件可能只有 root 可读，退回用 su 读
        val text = if (direct.isNullOrBlank()) {
            RootShell.read("cat '${statusFile.absolutePath}'")
        } else {
            direct
        }
        if (text.isNullOrBlank()) return@withContext null
        parseStatus(text)
    }

    private fun parseStatus(text: String): DaemonStatus {
        var ok = false
        var mode = "NONE"
        var pid = 0
        var freqs = IntArray(0)
        var error: String? = null
        var ts = 0L
        var mbc = false
        var route = ""
        var paused = false
        var protocolVersion = 0
        text.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "ok" -> ok = value == "1"
                "mode" -> mode = value
                "pid" -> pid = value.toIntOrNull() ?: 0
                "freqs" -> freqs = value.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
                "mbc" -> mbc = value == "1"
                "route" -> route = value
                "paused" -> paused = value == "1"
                "pv" -> protocolVersion = value.toIntOrNull() ?: 0
                "error" -> error = value.ifBlank { null }
                "ts" -> ts = value.toLongOrNull() ?: 0L
            }
        }
        return DaemonStatus(ok, mode, pid, freqs, error, ts, mbc, route, paused, protocolVersion)
    }

    /** 读回当前生效的配置（App 自己写的 cmd 文件，重启后靠它恢复界面数值）。 */
    fun readConfig(): DaemonConfigSnapshot? = runCatching {
        val text = cmdFile.readText()
        if (text.isBlank()) return@runCatching null
        var active = false
        var boost = 0
        var eq = IntArray(0)
        var bass = 0
        var boostMin = 0
        var leveler = 0
        text.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "active" -> active = value == "1"
                "boost" -> boost = value.toIntOrNull() ?: 0
                "boost_min" -> boostMin = value.toIntOrNull() ?: 0
                "eq" -> eq = value.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
                "bass" -> bass = value.toIntOrNull() ?: 0
                "leveler" -> leveler = value.toIntOrNull() ?: 0
            }
        }
        DaemonConfigSnapshot(active, boost, eq, bass, boostMin, leveler)
    }.getOrNull()

    /** 读取守护进程日志尾部，用于诊断。 */
    fun logTail(lines: Int = 15): String = runCatching {
        logFile.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    companion object {
        private const val TAG = "RootDaemonClient"

        const val DAEMON_CLASS = "com.sonicforge.app.daemon.RootDaemon"

        /**
         * App 期望的状态文件协议版本，必须与 `RootDaemon.ProtocolVersion` 保持一致。
         *
         * 守护进程状态文件里新增字段时，两个数字一起 +1，
         * 这样老进程会被自动杀掉重拉，不会再出现「新 APK 跑旧代码」。
         */
        const val DAEMON_PROTOCOL_VERSION = 4

        /** 音效链那几行的 key。 */
        private val EffectKeys = setOf("active", "boost", "boost_min", "eq", "bass", "leveler")

        /** 硬件音量守护那几行的 key。 */
        private val MixerKeys = setOf("mixer_tool", "mixer_card", "mixer_offset", "mixer_names")

        /**
         * 用于 pgrep/pkill 的模式。
         *
         * 写成 `Root[D]aemon` 是为了让「执行这条命令的 shell 自己」不被匹配到：
         * shell 的命令行里是字面量 `Root[D]aemon`，而正则只匹配 `RootDaemon`。
         */
        private const val PROCESS_PATTERN = "com.sonicforge.app.daemon.Root[D]aemon"
    }
}
