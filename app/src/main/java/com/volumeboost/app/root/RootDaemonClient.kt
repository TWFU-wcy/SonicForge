package com.volumeboost.app.root

import android.content.Context
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
) {
    /** 心跳在 8 秒内视为存活。 */
    fun isAlive(now: Long = System.currentTimeMillis()): Boolean = ok && now - ts in 0..8_000L
}

/** 守护进程当前生效的配置（从 cmd 文件读回）。 */
data class DaemonConfigSnapshot(
    val active: Boolean,
    val boost: Int,
    val eq: IntArray,
    val bass: Int,
    val virt: Int,
)

/**
 * ROOT 守护进程的客户端。
 *
 * 通信方式刻意做成「文件」而不是 socket：
 * 守护进程是 uid 0 的独立进程，App 被杀掉、重启都不影响它；
 * App 只要重新写一遍配置就能接管，不需要重新拉起进程。
 */
class RootDaemonClient(private val context: Context) {

    private val cmdFile = File(context.filesDir, "vb_daemon.cmd")
    private val statusFile = File(context.filesDir, "vb_daemon.status")
    private val logFile = File(context.filesDir, "vb_daemon.log")

    /**
     * 写音效配置。文件很小，直接同步写即可。
     *
     * 只替换音效那几行，保留 `mixer_*` 行 —— 硬件增益的守护由
     * [writeMixer] 独立维护，两边互不覆盖。
     */
    fun writeConfig(active: Boolean, boost: Int, eq: IntArray, bass: Int, virt: Int) {
        updateLines { lines ->
            lines.removeAll { keyOf(it) in EffectKeys }
            lines += "active=${if (active) 1 else 0}"
            lines += "boost=$boost"
            lines += "eq=${eq.joinToString(",")}"
            lines += "bass=$bass"
            lines += "virt=$virt"
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
        if (isRunning()) return@withContext true

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
            if (status != null) return@withContext status.ok
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
                "error" -> error = value.ifBlank { null }
                "ts" -> ts = value.toLongOrNull() ?: 0L
            }
        }
        return DaemonStatus(ok, mode, pid, freqs, error, ts)
    }

    /** 读回当前生效的配置（App 自己写的 cmd 文件，重启后靠它恢复界面数值）。 */
    fun readConfig(): DaemonConfigSnapshot? = runCatching {
        val text = cmdFile.readText()
        if (text.isBlank()) return@runCatching null
        var active = false
        var boost = 0
        var eq = IntArray(0)
        var bass = 0
        var virt = 0
        text.lineSequence().forEach { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "active" -> active = value == "1"
                "boost" -> boost = value.toIntOrNull() ?: 0
                "eq" -> eq = value.split(',').mapNotNull { it.toIntOrNull() }.toIntArray()
                "bass" -> bass = value.toIntOrNull() ?: 0
                "virt" -> virt = value.toIntOrNull() ?: 0
            }
        }
        DaemonConfigSnapshot(active, boost, eq, bass, virt)
    }.getOrNull()

    /** 读取守护进程日志尾部，用于诊断。 */
    fun logTail(lines: Int = 15): String = runCatching {
        logFile.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    companion object {
        const val DAEMON_CLASS = "com.volumeboost.app.daemon.RootDaemon"

        /** 音效链那几行的 key。 */
        private val EffectKeys = setOf("active", "boost", "eq", "bass", "virt")

        /** 硬件音量守护那几行的 key。 */
        private val MixerKeys = setOf("mixer_tool", "mixer_card", "mixer_offset", "mixer_names")

        /**
         * 用于 pgrep/pkill 的模式。
         *
         * 写成 `Root[D]aemon` 是为了让「执行这条命令的 shell 自己」不被匹配到：
         * shell 的命令行里是字面量 `Root[D]aemon`，而正则只匹配 `RootDaemon`。
         */
        private const val PROCESS_PATTERN = "com.volumeboost.app.daemon.Root[D]aemon"
    }
}
