package com.frameyu.app.sampling

/** 一个短时音频窗口的归一化特征；不保存音频样本本身。 */
data class AudioFeatureWindow(
    val startMillis: Long,
    val endMillis: Long,
    val energy: Double,
    val spectralChange: Double,
    val rhythmChange: Double,
) {
    init {
        require(startMillis >= 0L && endMillis > startMillis) { "音频窗口时间必须有效" }
        listOf(energy, spectralChange, rhythmChange).forEach {
            require(it.isFinite() && it in 0.0..1.0) { "音频特征必须是 0 到 1 的有限值" }
        }
    }
}

/** 从音频摘要中找出演唱会中的高能量或节奏/频谱突变区间。 */
class ConcertAudioAnalyzer {
    fun analyze(videoDurationMillis: Long, windows: List<AudioFeatureWindow>): List<SampleRange> {
        require(videoDurationMillis > 0L && videoDurationMillis <= SamplingPlan.MAX_VIDEO_DURATION_MILLIS) {
            "视频时长必须在 1 毫秒到 2 小时之间"
        }
        val candidates = windows.mapNotNull { window ->
            val end = window.endMillis.coerceAtMost(videoDurationMillis)
            if (window.startMillis >= end || window.energy < MIN_ENERGY) return@mapNotNull null
            val tier = if (window.energy >= HIGH_ENERGY) {
                SamplingTier.HIGH_VALUE
            } else if (window.spectralChange >= CHANGE_THRESHOLD || window.rhythmChange >= CHANGE_THRESHOLD) {
                SamplingTier.FOCUSED
            } else {
                return@mapNotNull null
            }
            SampleRange(window.startMillis, end, tier.defaultFramesPerSecond, tier)
        }.sortedBy(SampleRange::startMillis)

        return candidates.fold(mutableListOf()) { merged, current ->
            val previous = merged.lastOrNull()
            if (previous != null && current.startMillis <= previous.endMillis) {
                merged[merged.lastIndex] = SampleRange(
                    previous.startMillis,
                    maxOf(previous.endMillis, current.endMillis),
                    maxOf(previous.framesPerSecond, current.framesPerSecond),
                    if (previous.tier.ordinal >= current.tier.ordinal) previous.tier else current.tier,
                )
            } else {
                merged += current
            }
            merged
        }
    }

    private companion object {
        const val MIN_ENERGY = 0.1
        const val HIGH_ENERGY = 0.7
        const val CHANGE_THRESHOLD = 0.6
    }
}
