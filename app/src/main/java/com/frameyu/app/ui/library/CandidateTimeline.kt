package com.frameyu.app.ui.library

/** 候选帧时间轴上当前帧及相邻下一帧的时间戳。 */
data class FrameTimestamps(
    val previousMillis: Long,
    val nextMillis: Long,
)

/** 不依赖 Android 的候选帧时间计算。 */
object CandidateTimeline {
    /**
     * 根据当前时间返回所在帧和下一帧的时间戳，结果均限制在视频时长内。
     */
    fun frameTimestamps(
        durationMillis: Long,
        currentMillis: Long,
        frameRate: Double,
    ): FrameTimestamps {
        require(durationMillis >= 0L) { "视频时长不能为负数" }
        require(frameRate > 0.0 && frameRate.isFinite()) { "帧率必须为正数" }

        val current = currentMillis.coerceIn(0L, durationMillis)
        val frameDurationMillis = 1_000.0 / frameRate
        val frameIndex = kotlin.math.floor(current / frameDurationMillis).toLong()
        val previous = (frameIndex * frameDurationMillis).toLong()
            .coerceIn(0L, durationMillis)
        val next = ((frameIndex + 1L) * frameDurationMillis).toLong()
            .coerceIn(0L, durationMillis)

        return FrameTimestamps(previous, next)
    }

    /** 将时间点归一化为时间轴拖动值 [0, 1]。 */
    fun normalizeTime(currentMillis: Long, durationMillis: Long): Float {
        require(durationMillis >= 0L) { "视频时长不能为负数" }
        if (durationMillis == 0L) return 0f
        return (currentMillis.toDouble() / durationMillis)
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    /** 将时间轴拖动值 [0, 1] 转换为视频内的毫秒时间点。 */
    fun timeFromNormalized(normalizedValue: Float, durationMillis: Long): Long {
        require(durationMillis >= 0L) { "视频时长不能为负数" }
        return (normalizedValue.toDouble().coerceIn(0.0, 1.0) * durationMillis)
            .toLong()
    }
}
