package com.volumeboost.app.root

/**
 * 一条 ALSA 混音器控制项（tinymix 输出的一行）。
 *
 * 例：
 * ```
 * 12	INT	2	"RX0 Digital Volume"	84	(range 0->124)
 * ```
 */
data class MixerControl(
    val index: Int,
    val type: String,
    val name: String,
    /** 当前值（数值型控制才有）。 */
    val value: Int?,
    val min: Int?,
    val max: Int?,
) {
    /** 只有 INT 类型且能读到当前值、能读到上限的控制才允许写入。 */
    val writable: Boolean
        get() = type.equals("INT", ignoreCase = true) && value != null && max != null

    /** 供诊断展示的一行。 */
    fun describe(): String {
        val range = if (min != null && max != null) " [$min..$max]" else ""
        return "$name = ${value ?: "?"}$range"
    }
}

/**
 * tinymix 输出解析。
 *
 * tinymix 各版本输出略有差异，这里用宽松正则：
 * - 行首是控制编号，接着是类型（INT/ENUM/BYTES），接着是通道数，接着是带引号的名字；
 * - 名字之后的部分里找 `range a->b`，再找第一个整数当当前值。
 */
object MixerParser {

    private val LineRegex =
        Regex("""^\s*(\d+)\s+(INT|ENUM|BYTES)\s+(\d+)\s+"([^"]+)"\s*(.*)$""")
    private val RangeRegex = Regex("""range\s*(-?\d+)\s*->\s*(-?\d+)""")
    private val NumberRegex = Regex("""-?\d+""")

    fun parse(text: String): List<MixerControl> {
        val result = ArrayList<MixerControl>()
        for (line in text.lineSequence()) {
            val match = LineRegex.find(line) ?: continue
            val index = match.groupValues[1].toIntOrNull() ?: continue
            val type = match.groupValues[2]
            val name = match.groupValues[4]
            val tail = match.groupValues[5]
            val range = RangeRegex.find(tail)
            val min = range?.groupValues?.get(1)?.toIntOrNull()
            val max = range?.groupValues?.get(2)?.toIntOrNull()
            val value = NumberRegex
                .findAll(RangeRegex.replace(tail, ""))
                .mapNotNull { it.value.toIntOrNull() }
                .firstOrNull()
            result += MixerControl(index, type, name, value, min, max)
        }
        return result
    }
}
