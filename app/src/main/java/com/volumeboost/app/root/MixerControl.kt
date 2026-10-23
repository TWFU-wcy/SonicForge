package com.volumeboost.app.root

/**
 * 一条 ALSA 混音器控制项（tinymix 输出的一行）。
 *
 * tinymix 各版本输出差异很大，实测遇到过的两种：
 * ```
 * 12	INT	2	"RX0 Digital Volume"	84	(range 0->124)   // 带引号
 * 0	BOOL	1	TX_AIF1_CAP Mixer DEC0                   Off   // 不带引号、空格对齐
 * ```
 */
data class MixerControl(
    val index: Int,
    val type: String,
    val name: String,
    /** 当前值（只有 INT 型控制才解析成数字）。 */
    val value: Int?,
    val min: Int?,
    val max: Int?,
    /** 原始取值文本（BOOL 的 On/Off、ENUM 的枚举名等），用于诊断展示。 */
    val rawValue: String = "",
) {
    /** 只有 INT 类型且能读到当前值、能读到上限的控制才允许写入。 */
    val writable: Boolean
        get() = type.equals("INT", ignoreCase = true) && value != null && max != null

    /** 供诊断展示的一行。 */
    fun describe(): String {
        val range = if (min != null && max != null) " [$min..$max]" else ""
        val shown = value?.toString() ?: rawValue.ifBlank { "?" }
        return "$name = $shown$range"
    }
}

/**
 * tinymix 输出解析。
 *
 * 关键点（都是真机上踩出来的）：
 * 1. 类型不止 INT/ENUM/BYTES，还有 **BOOL** —— 旧版正则漏了它，导致整个 dump 一条都解析不出来；
 * 2. 名字**不一定带引号**（小米自带的 tinymix 就不带），旧版正则强制要求 `"..."`，同样全灭；
 * 3. 名字和值之间可能是制表符，也可能是用于对齐的多个空格；
 * 4. 范围标注 `(range a->b)` 有时带括号有时不带。
 */
object MixerParser {

    private val HeadRegex = Regex("""^\s*(\d+)\s+(BOOL|INT|ENUM|BYTES)\s+(\d+)\s+(.*)$""")
    private val RangeSuffix = Regex("""\s*\(?range\s*(-?\d+)\s*->\s*(-?\d+)\)?\s*$""")
    private val Separator = Regex("""\t+|\s{2,}""")
    private val IntRegex = Regex("""-?\d+""")

    fun parse(text: String): List<MixerControl> {
        val result = ArrayList<MixerControl>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val head = HeadRegex.find(line) ?: continue
            val index = head.groupValues[1].toIntOrNull() ?: continue
            val type = head.groupValues[2].uppercase()
            var rest = head.groupValues[4].trimEnd()
            if (rest.isEmpty()) continue

            val rangeMatch = RangeSuffix.find(rest)
            val min = rangeMatch?.groupValues?.get(1)?.toIntOrNull()
            val max = rangeMatch?.groupValues?.get(2)?.toIntOrNull()
            if (rangeMatch != null) rest = rest.removeRange(rangeMatch.range).trimEnd()

            val name: String
            val rawValue: String
            if (rest.startsWith("\"")) {
                val close = rest.indexOf('"', 1)
                if (close < 0) continue
                name = rest.substring(1, close).trim()
                rawValue = rest.substring(close + 1).trim().trimStart('\t', ' ')
            } else {
                // 取最后一段「制表符或多个空格」作为名字与值的分界，
                // 名字内部的单个空格（如 "TX_AIF1_CAP Mixer DEC0"）不会被切开。
                val sep = Separator.findAll(rest).lastOrNull()
                if (sep != null) {
                    name = rest.substring(0, sep.range.first).trim()
                    rawValue = rest.substring(sep.range.last + 1).trim()
                } else {
                    val space = rest.lastIndexOf(' ')
                    if (space <= 0) {
                        name = rest.trim()
                        rawValue = ""
                    } else {
                        name = rest.substring(0, space).trim()
                        rawValue = rest.substring(space + 1).trim()
                    }
                }
            }
            if (name.isBlank()) continue

            val value = if (type == "INT") IntRegex.find(rawValue)?.value?.toIntOrNull() else null
            result += MixerControl(index, type, name, value, min, max, rawValue)
        }
        return result
    }

    /** 从 dump 里读出 `Mixer name: 'xxx'`。 */
    fun mixerName(text: String): String? =
        Regex("""Mixer name:\s*'([^']*)'""").find(text)?.groupValues?.get(1)
}
