package com.sonicforge.app.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ROOT 侧能力的状态快照。
 */
data class RootState(
    /** 是否正在检测。 */
    val checking: Boolean = true,
    /** su 是否可用。 */
    val available: Boolean = false,
    val suPath: String? = null,
    /** 系统单声道当前状态。 */
    val mono: Boolean = false,
    val error: String? = null,
    /** 供「诊断」弹窗直接展示的文本快照。 */
    val diagnostic: String = "",
)

/**
 * ROOT 能力管理器。
 *
 * 提供两项能力：
 * 1. **su 可用性检测**：决定引擎能否走 ROOT 路线。
 * 2. **系统单声道**：`settings put system master_mono`，不需要「修改系统设置」权限。
 *
 * 音量增强本身全部由 uid 0 的守护进程在系统混音链上完成（见 [com.sonicforge.app.daemon.RootDaemon]），
 * 这里不再直接读写 codec 的硬件音量。
 *
 * 曾经的「释放全局音效通道」（disable 厂商音效包 + 重启 audioserver）已移除：
 * 真机实测它禁用不到 HAL 层由 `audio_effects_config.xml` 声明的音效（MiSound / DAP 照样注册），
 * 却会用一次 `ctl.restart audioserver` 把本应用自己的音效链冲掉，并让小米音效 App 打不开。
 */
class RootAudioManager private constructor(private val context: Context) {

    companion object {
        /** 一次性迁移最多扫多少张声卡（card 0..MaxCardScan-1）。 */
        private const val MaxCardScan = 8

        @Volatile
        private var instance: RootAudioManager? = null

        fun get(context: Context): RootAudioManager = instance ?: synchronized(this) {
            instance ?: RootAudioManager(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = context.getSharedPreferences("root_audio", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(RootState())
    val state: StateFlow<RootState> = _state.asStateFlow()

    // ── 检测 ────────────────────────────────────────────────────────────────

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(checking = true, error = null)

        val su = RootShell.resolve(force = true)
        if (su == null) {
            _state.value = RootState(
                checking = false,
                available = false,
                error = "未检测到 ROOT：su 不可用",
                diagnostic = "ROOT 检测\n· su：不可用\n· 说明：临时 ROOT 每次重启都会失效，重新注入后再点「重新检测」",
            )
            return@withContext
        }

        // 升级自老版本（带硬件增益的版本）时，把当年写高的 codec 音量放回原值。
        // 必须在 su 可用之后做，失败也不影响下面的能力检测。
        val legacyNote = runCatching { restoreLegacyGain() }.getOrDefault("")

        val mono = readMono()

        val diagnostic = buildString {
            appendLine("ROOT 诊断")
            appendLine("· su：$su")
            appendLine("· 系统单声道：${if (mono) "开" else "关"}")
            if (legacyNote.isNotEmpty()) {
                appendLine("· 旧版硬件增益：$legacyNote")
            }
        }

        _state.value = RootState(
            checking = false,
            available = true,
            suPath = su,
            mono = mono,
            diagnostic = diagnostic,
        )
    }

    // ── 旧版硬件增益的一次性清理 ──────────────────────────────────────────────

    /**
     * 把老版本写高的 codec 数字音量写回原值。
     *
     * 1.3.7 及更早的版本提供过「硬件增益」：用 `tinymix` 直接抬高 codec 的
     * 数字音量，并由 uid 0 守护进程每 1.2 秒维持偏移。这套能力已经删除，
     * 守护进程也会被协议版本顶掉，但寄存器会停在抬高的状态，直到系统下一次
     * 因为音量调节重写它 —— 用户不碰音量键就一直停在那儿。
     *
     * 所以升级后第一次启动时，用旧版本记录在偏好里的原始值写回去，然后清掉
     * 那几个键；偏好清空后本方法自然不再有活干。
     *
     * @return 给诊断看的一行说明；没有旧数据时返回空串。
     */
    private suspend fun restoreLegacyGain(): String {
        val raw = prefs.getString("originals", null)
        val gainDb = prefs.getInt("gain_db", 0)
        if (raw.isNullOrBlank()) {
            // 没有旧数据也要把残留的展示字段清掉，免得设置页还显示一个假的 +N dB
            prefs.edit().remove("gain_db").remove("detail").apply()
            return ""
        }
        val originals = runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key -> put(key, json.optInt(key)) }
            }
        }.getOrDefault(emptyMap())
        if (originals.isEmpty()) return ""

        // 找不到工具就保留记录，下次进设置页再试一次（写回原始值是幂等的，重试无害）
        val tool = findTinymix() ?: return "未找到 tinymix，下次检测时再试"
        val pending = originals.toMutableMap()
        for (card in 0 until MaxCardScan) {
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val (name, value) = iterator.next()
                val safe = name.replace("\"", "").replace("$", "").replace("`", "")
                val r = RootShell.exec("$tool -D $card \"$safe\" $value 2>&1", 8_000L)
                if (r.ok && !r.looksFailed) iterator.remove()
            }
            if (pending.isEmpty()) break
        }
        val restored = originals.size - pending.size
        if (pending.isEmpty()) {
            // 全部写回成功才清记录；没成功就留着，下次进设置页接着试
            prefs.edit().remove("gain_db").remove("detail").remove("originals").apply()
        } else {
            val remaining = JSONObject()
            pending.forEach { (name, value) -> remaining.put(name, value) }
            prefs.edit().putString("originals", remaining.toString()).apply()
        }
        return when {
            pending.isEmpty() -> "已恢复 $restored 项原始值（升级前为 +${gainDb}dB）"
            restored == 0 -> "恢复失败，下次检测时再试"
            else -> "已恢复 $restored/${originals.size} 项，其余下次再试"
        }
    }

    /** 找 tinymix；找不到返回 null。 */
    private suspend fun findTinymix(): String? {
        val r = RootShell.exec(
            "command -v tinymix 2>/dev/null; " +
                "ls /vendor/bin/tinymix /system/bin/tinymix /system/xbin/tinymix 2>/dev/null",
            6_000L,
        )
        return r.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") }
    }

    // ── 系统单声道 ──────────────────────────────────────────────────────────

    suspend fun setMono(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val value = if (enabled) 1 else 0
        RootShell.exec("settings put system master_mono $value", 6_000L)
        val now = readMono()
        _state.value = _state.value.copy(mono = now, error = if (now != enabled) "单声道写入失败" else null)
        now == enabled
    }

    private suspend fun readMono(): Boolean {
        val value = RootShell.read("settings get system master_mono", 5_000L)
        return value?.trim() == "1"
    }
}
