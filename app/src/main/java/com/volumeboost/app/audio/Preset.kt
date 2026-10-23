package com.volumeboost.app.audio

/**
 * 一键预设。数值范围与 [AudioEngine] 约定一致：
 * - boostMillibel: 0..1900（1900 ≈ +19dB，留出余量避免长时间顶满）
 * - eqLevelsDb: 10 段，各段 -600..600（单位 1/100 dB，即 ±6.0dB）
 * - bassBoostStrength / virtualizerStrength: 0..1000
 */
data class Preset(
    val id: String,
    val label: String,
    val boostMillibel: Int,
    val eqLevelsDb: IntArray,
    val bassBoostStrength: Int,
    val virtualizerStrength: Int,
) {
    companion object {
        /** 默认：显著提亮音量，轻微高低频补偿，听感更"响"而不是"破"。 */
        val Default = Preset(
            id = "default",
            label = "标准增强",
            boostMillibel = 1000,
            eqLevelsDb = intArrayOf(200, 200, 100, 0, 0, 0, 100, 200, 200, 100),
            bassBoostStrength = 300,
            virtualizerStrength = 0,
        )

        /** 人声为主（通话/播客/看剧）：中频 1-3kHz 抬升，低频收敛。 */
        val Voice = Preset(
            id = "voice",
            label = "人声增强",
            boostMillibel = 1200,
            eqLevelsDb = intArrayOf(-100, 0, 200, 400, 400, 300, 200, 100, 0, -100),
            bassBoostStrength = 0,
            virtualizerStrength = 0,
        )

        /** 外放小声场景：更大增益 + 低频补偿（最能提升感知响度）。 */
        val Speaker = Preset(
            id = "speaker",
            label = "外放最大",
            boostMillibel = 1600,
            eqLevelsDb = intArrayOf(400, 300, 200, 100, 0, 0, 100, 300, 400, 300),
            bassBoostStrength = 600,
            virtualizerStrength = 0,
        )

        /** 夜间/低音量细节补偿（等响度曲线）：小音量时补高低频。 */
        val Night = Preset(
            id = "night",
            label = "夜间清晰",
            boostMillibel = 800,
            eqLevelsDb = intArrayOf(300, 200, 0, -100, -100, 0, 200, 300, 300, 200),
            bassBoostStrength = 200,
            virtualizerStrength = 0,
        )

        val All = listOf(Default, Voice, Speaker, Night)
    }
}
