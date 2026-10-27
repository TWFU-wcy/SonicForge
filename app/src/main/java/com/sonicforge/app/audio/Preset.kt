package com.sonicforge.app.audio

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 一键预设。
 *
 * 增益不再是一个固定值，而是随系统音量变化的区间：
 * - [boostMillibel]：系统音量**最低**时给的补偿（上限，也是滑杆能拖到的最大值）
 * - [minBoostMillibel]：系统音量**最大**时剩下的补偿（下限，默认 0 = 完全让路）
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
        /**
         * 系统音量拉满时保留的补偿：**0**，即音量推满时完全让路。
         *
         * 实测满音量下再叠 +1.2 dB 就会破音，所以这里不再留"底噪补偿"。
         */
        const val FloorBoostMillibel = 0

        /** 「最大补偿」滑杆的下限。滑到 0 等于关掉增强，没有意义，所以留 1 dB。 */
        const val MinSliderBoostMillibel = 100

        /**
         * 主增益曲线的**斜率预算**，单位 1/100 dB 每 1.0 音量。
         *
         * 系统音量本身在衰减信号：按线性幅度折算，`d(20·log10 v)/dv = 8.686 / v`，
         * 在 v = 1 处最慢，等于 8.686 dB 每单位 v。补偿曲线只要降得比它快，
         * 总音量就会倒挂——「音量键按上去反而变小」，也就是 90% / 95% 档比 100% 档还响。
         *
         * 主增益与低音补偿共享同一份斜率预算，两段加起来必须小于 868：
         * 560 + 180 = 740，留 14.8% 余量。余量不是浪费：下发值以 1/100 dB 取整，
         * 每档最多引入 ±1.6 毫贝尔的量化误差，档位越细每档的真实涨幅越小，
         * 余量不够时取整噪声就会把某两档的顺序翻过来。留到 740 之后，
         * 上限 1~18 dB 逐毫贝尔 × 低音 0~1000 逐档 × 15/16/25/30/50/100 档音量
         * 全部实测 0 倒挂（覆盖所有真机音量档数）。
         */
        const val BoostSlopeBudgetMillibel = 560

        /** 低音补偿的斜率预算（见 [BoostSlopeBudgetMillibel]，两者之和是硬约束）。 */
        const val BassSlopeBudgetMillibel = 180

        /** 低音强度 1000 时折算到最低频段的额外增益，单位 1/100 dB（+6 dB）。 */
        const val BassMaxExtraDbMillibel = 600

        /** 曲线的最大指数（低音量区间平缓、接近满音量时快速收窄）。 */
        const val MaxCurveExponent = 1.6f

        /** 曲线的指数下限：上限拖到很高时也要保证单调，不能无限逼近 0。 */
        const val MinCurveExponent = 0.3f

        /**
         * 按「增益区间」与「斜率预算」算曲线指数。
         *
         * 指数 p 满足 `区间 × p ≤ 预算`，即补偿曲线在任何音量档位的下降速度
         * 都不超过系统音量的衰减速度。区间 ≤ 3.5 dB 时 p 被 1.6 封顶，曲线形状和以前一致。
         */
        fun exponentFor(rangeMillibel: Int, budgetMillibel: Int = BoostSlopeBudgetMillibel): Float {
            if (rangeMillibel <= 0) return MaxCurveExponent
            return (budgetMillibel.toFloat() / rangeMillibel)
                .coerceIn(MinCurveExponent, MaxCurveExponent)
        }

        /**
         * 按系统音量算实际补偿。
         *
         * 曲线用 `1 - v^p`：前段平缓、接近满音量时快速收窄，
         * v = 0 → 上限，v = 1 → 下限（默认 0）。指数 p 见 [exponentFor]。
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
            val range = max - min
            val weight = 1f - v.pow(exponentFor(range, BoostSlopeBudgetMillibel))
            return (min + range * weight).roundToInt().coerceIn(min, max)
        }

        /**
         * 低音也随音量收敛，满音量时归零：低频最容易先失真。
         *
         * 低音补偿最终会折算成 dB（[BassMaxExtraDbMillibel]）叠在主增益上，所以它同样要吃
         * 斜率预算，否则两者叠加后又会倒挂。
         */
        fun effectiveBass(strength: Int, volume: Int, maxVolume: Int): Int {
            val range = strength.coerceIn(0, 1000)
            if (maxVolume <= 0) return range
            val v = (volume.toFloat() / maxVolume).coerceIn(0f, 1f)
            val dbMillibel = range * BassMaxExtraDbMillibel / 1000
            val weight = 1f - v.pow(exponentFor(dbMillibel, BassSlopeBudgetMillibel))
            return (range * weight).roundToInt().coerceIn(0, 1000)
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
