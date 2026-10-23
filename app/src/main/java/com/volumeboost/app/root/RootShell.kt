package com.volumeboost.app.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 一条 shell 命令的执行结果。 */
data class ShellResult(
    val code: Int,
    val output: String,
) {
    val ok: Boolean get() = code == 0

    /** 输出里是否出现失败字样（tinymix 有时失败也返回 0）。 */
    val looksFailed: Boolean
        get() = output.contains("fail", ignoreCase = true) ||
            output.contains("error", ignoreCase = true) ||
            output.contains("denied", ignoreCase = true)
}

/**
 * ROOT 命令执行器。
 *
 * 只做一件事：设备上**已经存在** su 时，用它执行命令。
 * 应用本身不含任何提权或漏洞利用代码；没有 su 时全部静默降级。
 */
object RootShell {

    private const val DEFAULT_TIMEOUT_MS = 8_000L

    /** 常见 su 位置，KernelSU / Magisk / 系统自带各不相同。 */
    private val Candidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/sbin/su",
    )

    @Volatile
    private var resolved: String? = null

    /** 已经探测到的 su 路径（不发起新的探测）。 */
    val cachedSu: String? get() = resolved

    /** 探测可用的 su，返回其路径；没有则返回 null。结果会缓存。 */
    suspend fun resolve(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!force) resolved?.let { return@withContext it }
        for (candidate in Candidates) {
            // 首次请求 ROOT 时 KernelSU / Magisk 会弹授权框，留足 60 秒给用户点「允许」。
            val r = runProcess(candidate, "id", 60_000L)
            if (r.ok && r.output.contains("uid=0")) {
                resolved = candidate
                return@withContext candidate
            }
        }
        null
    }

    suspend fun isAvailable(): Boolean = resolve() != null

    /** 以 root 身份执行命令。 */
    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult =
        withContext(Dispatchers.IO) {
            val su = resolve() ?: return@withContext ShellResult(-1, "未获得 ROOT")
            runProcess(su, command, timeoutMs)
        }

    /** 执行并返回去掉首尾空白的输出，失败返回 null。 */
    suspend fun read(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? =
        exec(command, timeoutMs).takeIf { it.ok }?.output?.trim()?.takeIf { it.isNotEmpty() }

    private fun runProcess(executable: String, command: String, timeoutMs: Long): ShellResult {
        return runCatching {
            val process = ProcessBuilder(executable, "-c", command)
                .redirectErrorStream(true)
                .start()
            val buffer = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(buffer) { buffer.append(line).append('\n') }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(300)
                val partial = synchronized(buffer) { buffer.toString() }
                return ShellResult(-1, "$partial\n[执行超时]")
            }
            reader.join(800)
            ShellResult(process.exitValue(), synchronized(buffer) { buffer.toString() })
        }.getOrElse { error ->
            ShellResult(-1, error.message ?: error.javaClass.simpleName)
        }
    }
}
