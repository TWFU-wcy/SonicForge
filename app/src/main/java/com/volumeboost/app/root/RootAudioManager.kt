package com.volumeboost.app.root

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
    /** 实际使用的混音器工具路径，null 表示没找到或打不开。 */
    val mixerTool: String? = null,
    /** 混音器控制项总数。 */
    val controlCount: Int = 0,
    /** 被判定为「输出音量」的控制项。 */
    val targets: List<MixerControl> = emptyList(),
    /** 当前已应用的硬件增益（dB）。 */
    val appliedDb: Int = 0,
    /** 实际写入的明细，例如 `RX0 Digital Volume 84→108`。 */
    val appliedDetail: String? = null,
    /** 系统单声道当前状态。 */
    val mono: Boolean = false,
    /** 系统里被识别为「厂商音效」的包名。 */
    val vendorPackages: List<String> = emptyList(),
    /** 厂商音效是否已被禁用（释放全局音效通道）。 */
    val vendorBlocked: Boolean = false,
    val error: String? = null,
    /** 供「诊断」弹窗直接展示的文本快照。 */
    val diagnostic: String = "",
)

/**
 * ROOT 硬件增强管理器。
 *
 * 三条能力：
 * 1. **硬件增益**：通过 tinymix 直接抬高 codec 的 RX / Speaker 数字音量，
 *    这是唯一能突破「数字信号已到 0 dBFS」这条天花板的手段。
 * 2. **释放全局音效通道**：禁用厂商音效服务（杜比 / 小米音效），
 *    避免它们和本应用的音效抢 session 0 的音效槽位。
 * 3. **系统单声道**：`settings put system master_mono`，不需要「修改系统设置」权限。
 */
class RootAudioManager private constructor(private val context: Context) {

    companion object {
        /** UI 上硬件增益滑杆的上限。 */
        const val MaxGainDb = 12

        /** Qualcomm codec 的 Digital Volume 通常是 0.5 dB 一档。 */
        private const val UnitsPerDb = 2

        private val GainNameRegex =
            Regex("(?i)(speaker|spk|spkr|spkm|rx[0-9]|hph|headphone|earpiece|mono|dac)")

        private val ExcludeRegex =
            Regex("(?i)(mic|adc|dec|tx|capture|record|input|dmic|amix|aif|vad|voice|call)")

        private val VendorKeywords = listOf(
            "dolby", "dax", "dts", "audioeffect", "audio.effect", "harman", "miui.audio",
        )

        @Volatile
        private var instance: RootAudioManager? = null

        fun get(context: Context): RootAudioManager = instance ?: synchronized(this) {
            instance ?: RootAudioManager(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = context.getSharedPreferences("root_audio", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(RootState())
    val state: StateFlow<RootState> = _state.asStateFlow()

    @Volatile
    private var mixerTool: String? = null

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

        val tool = findMixerTool()
        mixerTool = tool
        val dump = if (tool != null) readDump(tool) else ""
        val controls = MixerParser.parse(dump)
        val targets = pickTargets(controls)
        val mono = readMono()
        val vendor = readVendorPackages()
        val disabled = readDisabledPackages()

        val diagnostic = buildString {
            appendLine("ROOT 诊断")
            appendLine("· su：$su")
            appendLine("· 混音器工具：${tool ?: "未找到"}")
            appendLine("· 控制项总数：${controls.size}")
            appendLine("· 匹配到的音量控制：${targets.size}")
            targets.take(12).forEach { appendLine("   - ${it.describe()}") }
            appendLine("· 系统单声道：${if (mono) "开" else "关"}")
            appendLine("· 厂商音效包：${if (vendor.isEmpty()) "未发现" else vendor.joinToString()}")
            if (dump.isNotBlank()) {
                appendLine()
                appendLine("混音器原始输出（前 20 行）：")
                dump.lines().take(20).forEach { appendLine(it) }
            }
            if (tool == null) {
                appendLine()
                appendLine("未找到可用的 tinymix / amixer。")
                appendLine("可以尝试：Magisk/KernelSU 模块里安装 tinymix，")
                appendLine("或确认 /vendor/bin/tinymix 存在且可执行。")
            } else if (controls.isEmpty()) {
                appendLine()
                appendLine("tinymix 存在但读不到控制项（可能是模拟器或没有硬件 codec）。")
            }
        }

        _state.value = RootState(
            checking = false,
            available = true,
            suPath = su,
            mixerTool = tool,
            controlCount = controls.size,
            targets = targets,
            appliedDb = prefs.getInt("gain_db", 0),
            appliedDetail = prefs.getString("detail", null),
            mono = mono,
            vendorPackages = vendor,
            vendorBlocked = vendor.isNotEmpty() && vendor.all { it in disabled },
            error = when {
                tool == null -> "未找到 tinymix / amixer，无法读写硬件音量"
                controls.isEmpty() -> "混音器读取失败"
                targets.isEmpty() -> "没有找到可写的输出音量控制"
                else -> null
            },
            diagnostic = diagnostic,
        )
    }

    /** 找可用的混音器工具；找到但打不开的（模拟器）视为不可用。 */
    private suspend fun findMixerTool(): String? {
        val tinymix = which("tinymix")
        if (tinymix != null && probeMixer(tinymix)) return tinymix
        val amixer = which("amixer")
        if (amixer != null && probeMixer(amixer)) return amixer
        return null
    }

    private suspend fun which(name: String): String? {
        val r = RootShell.exec(
            "command -v $name 2>/dev/null; " +
                "ls /vendor/bin/$name /system/bin/$name /system/xbin/$name 2>/dev/null",
            6_000L,
        )
        return r.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/") }
    }

    private suspend fun probeMixer(path: String): Boolean {
        val r = RootShell.exec("$path -D 0 2>&1 | head -30", 10_000L)
        return !r.output.contains("Failed", ignoreCase = true) && r.output.isNotBlank()
    }

    private suspend fun readDump(tool: String): String {
        val withRange = RootShell.exec("$tool -D 0 -a 2>&1", 20_000L)
        if (MixerParser.parse(withRange.output).isNotEmpty()) return withRange.output
        return RootShell.exec("$tool -D 0 2>&1", 20_000L).output
    }

    /** 从所有控制项里挑出「输出音量」。 */
    private fun pickTargets(controls: List<MixerControl>): List<MixerControl> {
        val candidates = controls.filter { control ->
            val name = control.name.lowercase()
            control.writable &&
                name.contains("volume") &&
                !ExcludeRegex.containsMatchIn(name) &&
                GainNameRegex.containsMatchIn(name)
        }
        if (candidates.isEmpty()) return emptyList()
        // 有扬声器专用控制时只动扬声器，避免把耳机音量一起抬上去
        val speakerOnly = candidates.filter {
            val n = it.name.lowercase()
            n.contains("speaker") || n.contains("spk") || n.contains("mono")
        }
        return if (speakerOnly.isNotEmpty()) speakerOnly else candidates
    }

    // ── 硬件增益 ────────────────────────────────────────────────────────────

    /**
     * 把硬件音量按 [db] 分贝整体抬高。
     *
     * 以「首次应用前的原始值」为基准，因此 6 → 12 dB 是重算而不是叠加，
     * 反复拖动不会一路顶到芯片上限。
     */
    suspend fun applyGain(db: Int): Boolean = withContext(Dispatchers.IO) {
        val tool = mixerTool
        val targets = _state.value.targets
        if (tool == null || targets.isEmpty()) {
            _state.value = _state.value.copy(error = "没有可写的硬件音量控制")
            return@withContext false
        }

        val originals = loadOriginals()
        val details = ArrayList<String>()
        var success = 0

        for (control in targets) {
            val base = originals[control.name] ?: control.value ?: continue
            if (!originals.containsKey(control.name)) originals[control.name] = base
            val max = control.max ?: base
            val min = control.min ?: 0
            val target = (base + db * UnitsPerDb).coerceIn(min, max)
            if (setControl(tool, control, target)) {
                success++
                details += "${control.name} $base→$target"
            }
        }

        if (success == 0) {
            _state.value = _state.value.copy(error = "写入硬件音量失败")
            return@withContext false
        }

        saveOriginals(originals)
        prefs.edit().putInt("gain_db", db).putString("detail", details.joinToString("；")).apply()
        _state.value = _state.value.copy(
            appliedDb = db,
            appliedDetail = details.joinToString("；"),
            error = null,
        )
        true
    }

    /** 恢复到第一次应用前的原始值。 */
    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        val tool = mixerTool
        val originals = loadOriginals()
        if (tool == null || originals.isEmpty()) {
            prefs.edit().remove("gain_db").remove("detail").remove("originals").apply()
            _state.value = _state.value.copy(appliedDb = 0, appliedDetail = null)
            return@withContext true
        }
        var success = 0
        for (control in _state.value.targets) {
            val original = originals[control.name] ?: continue
            if (setControl(tool, control, original)) success++
        }
        prefs.edit().remove("gain_db").remove("detail").remove("originals").apply()
        _state.value = _state.value.copy(
            appliedDb = 0,
            appliedDetail = null,
            error = if (success == 0) "恢复原始值失败" else null,
        )
        success > 0 || originals.isEmpty()
    }

    private suspend fun setControl(tool: String, control: MixerControl, value: Int): Boolean {
        val safeName = control.name.replace("\"", "").replace("$", "").replace("`", "")
        val byName = RootShell.exec("$tool -D 0 \"$safeName\" $value 2>&1", 8_000L)
        if (byName.ok && !byName.looksFailed) return true
        val byIndex = RootShell.exec("$tool -D 0 ${control.index} $value 2>&1", 8_000L)
        return byIndex.ok && !byIndex.looksFailed
    }

    private fun loadOriginals(): MutableMap<String, Int> {
        val raw = prefs.getString("originals", null) ?: return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, Int>()
            json.keys().forEach { key -> map[key] = json.optInt(key) }
            map
        }.getOrDefault(mutableMapOf())
    }

    private fun saveOriginals(values: Map<String, Int>) {
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString("originals", json.toString()).apply()
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

    // ── 厂商音效（释放 session 0）────────────────────────────────────────────

    private suspend fun readVendorPackages(): List<String> {
        val output = RootShell.read("pm list packages 2>/dev/null", 12_000L) ?: return emptyList()
        return output.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { pkg -> VendorKeywords.any { pkg.lowercase().contains(it) } }
            .distinct()
            .sorted()
            .toList()
    }

    private suspend fun readDisabledPackages(): Set<String> {
        val output = RootShell.read("pm list packages -d 2>/dev/null", 12_000L) ?: return emptySet()
        return output.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * 禁用 / 恢复厂商音效服务。
     *
     * 厂商音效（杜比全景声、小米音效）会独占全局混音链路的音效槽位，
     * 和本应用的全局音效互相抢占。禁用后重启 audioserver 让链路重建。
     */
    suspend fun setVendorBlocked(blocked: Boolean): Boolean = withContext(Dispatchers.IO) {
        val packages = _state.value.vendorPackages
        if (packages.isEmpty()) {
            _state.value = _state.value.copy(error = "没有识别到厂商音效服务")
            return@withContext false
        }
        var changed = 0
        for (pkg in packages) {
            val command =
                if (blocked) "pm disable-user --user 0 $pkg" else "pm enable $pkg"
            val r = RootShell.exec("$command 2>&1", 10_000L)
            if (r.ok && !r.looksFailed) changed++
        }
        // 让 audioserver 重新加载音效链路
        RootShell.exec("setprop ctl.restart audioserver", 8_000L)
        val disabled = readDisabledPackages()
        val nowBlocked = packages.all { it in disabled }
        _state.value = _state.value.copy(
            vendorBlocked = nowBlocked,
            error = if (changed == 0) "厂商音效服务操作失败" else null,
        )
        changed > 0
    }

    /** 同步刷新厂商音效状态（不重新探测 su）。 */
    suspend fun refreshVendorState() = withContext(Dispatchers.IO) {
        val disabled = readDisabledPackages()
        val packages = _state.value.vendorPackages
        _state.value = _state.value.copy(
            vendorBlocked = packages.isNotEmpty() && packages.all { it in disabled },
        )
    }
}
