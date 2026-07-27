package com.frameyu.app.sampling

/** 采样区间使用半开范围 [startMillis, endMillis)，避免相邻区间重复采样。 */
data class SampleRange(
    val startMillis: Long,
    val endMillis: Long,
    val framesPerSecond: Double,
    val tier: SamplingTier,
) {
    init {
        require(startMillis >= 0L) { "采样开始时间不能为负数" }
        require(endMillis > startMillis) { "采样结束时间必须晚于开始时间" }
        require(framesPerSecond.isFinite()) { "采样帧率必须是有限数值" }
        require(framesPerSecond in tier.minFramesPerSecond..tier.maxFramesPerSecond) {
            "${tier.name} 采样帧率必须在 ${tier.minFramesPerSecond}～${tier.maxFramesPerSecond} 帧/秒之间"
        }
    }

    val durationMillis: Long
        get() = endMillis - startMillis

    fun contains(timestampMillis: Long): Boolean =
        timestampMillis >= startMillis && timestampMillis < endMillis
}

/** 视频分析的三档采样密度。高价值区间不超过 30 帧/秒，避免长视频产生失控的采样量。 */
enum class SamplingTier(
    val minFramesPerSecond: Double,
    val maxFramesPerSecond: Double,
    val defaultFramesPerSecond: Double,
) {
    COARSE(1.0, 2.0, 1.0),
    FOCUSED(5.0, 10.0, 8.0),
    HIGH_VALUE(15.0, 30.0, 24.0),
}
