package com.sonicforge.app.audio

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 一键预设。
 *
 * 增益不再是一个固定值，而是随系统音量变化的区间：
 * - [boostMillibel]：系统音量**最低**时给的补偿（上限，也是滑杆能拖到的最大值）
 * - [minBoostMillibel]：系统音量**最大**时剩下的补偿（下限）
 *
 * 为什么这么做：扬声器在系统音量已经拉满时，数字信号本来就在 0 dBFS 附近，
 * 再叠一层增益只会让限幅器一直工作，听感是"糊"和"破"。低音量时补得多，
 * 高音量时补得少，既能把小声的内容抬起来，又不会在满音量时把扬声器推到失真。
 *
 * - eqLevelsDb: 10 段，各段 -1200..1200（单位 1/100 dB，即 ±12 dB）
 * - bassBoostStrength: 0..1000，同样会随系统音量升高自动收敛
 * - levelerStrength: 0..1000，响度均衡（三段 MBC）的默认强度
 */
data class Preset(
    val id: String,
    val label: String,
    val boostMillibel: Int,
    val minBoostMillibel: Int,
    val eqLevelsDb: IntArray,
    val bassBoostStrength: Int,
    val levelerStrength: Int,
) {
    companion object {
        /** 音量最高时所有预设都只保留这一档补偿（+1.2 dB）。 */
        const val FloorBoostMillibel = 120

        /**
         * 按系统音量算实际补偿。
         *
         * 曲线用 `1 - v^1.6`：前段平缓、接近满音量时快速收窄。
         * v = 0 → 上限，v = 1 → 下限，中间不会出现"音量过半增益就没了"的突兀感。
         */
        fun effectiveBoostMillibel(
            maxMillibel: Int,
            minMillibel: Int,
            volume: Int,
            maxVolume: Int,
        ): Int {
            val max = maxMillibel.coerceAtLeast(0)
            val min = minMillibel.coerceIn(0, max)
            if (maxVolume <= 0) return max
            val v = (volume.toFloat() / maxVolume).coerceIn(0f, 1f)
            val weight = 1f - v.pow(1.6f)
            return (min + (max - min) * weight).roundToInt().coerceIn(min, max)
        }

        /** 低音也随音量收敛：满音量时只保留 55%，低频最容易先失真。 */
        fun effectiveBass(strength: Int, volume: Int, maxVolume: Int): Int {
            if (maxVolume <= 0) return strength
            val v = (volume.toFloat() / maxVolume).coerceIn(0f, 1f)
            return (strength * (1f - 0.45f * v)).roundToInt().coerceIn(0, 1000)
        }

        /**
         * 把 dB 换算成人耳感知的响度增幅百分比。
         *
         * 用 sone 标度：响度每 +10 dB 翻一倍，即 `2^(dB/10)`。
         * 所以 +6 dB ≈ +52%，+3 dB ≈ +23%，+1.2 dB ≈ +8.7%。
         * 比直接显示电压倍数（+6 dB = 2 倍）更接近"听起来大了多少"。
         */
        fun perceivedPercent(millibel: Int): Int {
            val db = millibel / 100f
            return ((2.0.pow(db / 10.0) - 1.0) * 100.0).roundToInt().coerceAtLeast(0)
        }

        /**
         * 标准增强：轻微降掉最低两段避免低频先削波，中频抬一点让口齿更清楚，
         * 高频略收，整体听感是"更清楚"而不是"更吵"。
         */
        val Default = Preset(
            id = "default",
            label = "标准增强",
            boostMillibel = 500,
            minBoostMillibel = FloorBoostMillibel,
            eqLevelsDb = intArrayOf(-150, -80, 0, 40, 0, 60, 120, 80, 20, -120),
            bassBoostStrength = 250,
            levelerStrength = 500,
        )

        /** 人声增强：切掉低频隆隆声与高频齿音，1~4 kHz 抬升，人声更靠前。 */
        val Voice = Preset(
            id = "voice",
            label = "人声增强",
            boostMillibel = 400,
            minBoostMillibel = FloorBoostMillibel,
            eqLevelsDb = intArrayOf(-300, -200, -50, 120, 260, 360, 300, 140, -60, -260),
            bassBoostStrength = 0,
            levelerStrength = 600,
        )

        /** 外放最大：补偿给得最足，但低频只做小幅提升，重心放在中高频的穿透力。 */
        val Speaker = Preset(
            id = "speaker",
            label = "外放最大",
            boostMillibel = 600,
            minBoostMillibel = FloorBoostMillibel,
            eqLevelsDb = intArrayOf(0, 60, 60, 30, 0, 50, 150, 110, 30, -180),
            bassBoostStrength = 450,
            levelerStrength = 400,
        )

        /** 夜间清晰：等响度补偿思路，小音量下补两端，同时压掉 500 Hz 的浑浊感。 */
        val Night = Preset(
            id = "night",
            label = "夜间清晰",
            boostMillibel = 300,
            minBoostMillibel = FloorBoostMillibel,
            eqLevelsDb = intArrayOf(180, 140, 40, 0, -40, 20, 160, 220, 160, 0),
            bassBoostStrength = 150,
            levelerStrength = 700,
        )

        val All = listOf(Default, Voice, Speaker, Night)
    }
}
