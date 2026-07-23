package com.frameyu.app.analysis

import kotlin.math.abs
import kotlin.math.roundToInt

/** 计算不依赖 ML 模型的基础画质分数。 */
class QualityScorer(
    private val sceneChangeDetector: SceneChangeDetector = SceneChangeDetector(),
) {
    /** 根据当前帧和可选的上一帧计算基础评分。 */
    fun score(sample: FrameSample, previous: FrameSample? = null): FrameScore {
        val isAdjacent = previous?.let { sceneChangeDetector.areTemporallyAdjacent(it, sample) } ?: false
        val difference = if (isAdjacent) sceneChangeDetector.difference(previous!!, sample) else 0.0
        val metrics = calculateMetrics(sample)
        val clarityScore = metrics.clarityScore
        val exposureScore = metrics.exposureScore
        val blackFrameScore = metrics.blackFrameScore
        val duplicateScore = if (previous == null || !isAdjacent) {
            100
        } else {
            (difference / DUPLICATE_DIFFERENCE_FOR_FULL_SCORE * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        }
        val sceneChangeScore = (difference * 100.0).roundToInt().coerceIn(0, 100)
        val totalScore = (
            clarityScore * CLARITY_WEIGHT +
                exposureScore * EXPOSURE_WEIGHT +
                blackFrameScore * BLACK_FRAME_WEIGHT +
                duplicateScore * DUPLICATE_WEIGHT +
                sceneChangeScore * SCENE_CHANGE_WEIGHT
            ).roundToInt().coerceIn(0, 100)

        return FrameScore(
            frameIndex = sample.frameIndex,
            timestampMillis = sample.timestampMillis,
            clarityScore = clarityScore,
            exposureScore = exposureScore,
            blackFrameScore = blackFrameScore,
            duplicateScore = duplicateScore,
            sceneChangeScore = sceneChangeScore,
            totalScore = totalScore,
        )
    }

    /** 以水平和垂直相邻像素的亮度梯度近似清晰度。 */
    private fun calculateMetrics(sample: FrameSample): FrameMetrics {
        var differenceSum = 0.0
        var comparisonCount = 0
        var luminanceSum = 0.0
        var clippedCount = 0
        var blackCount = 0
        for (y in 0 until sample.height) {
            for (x in 0 until sample.width) {
                val index = y * sample.width + x
                val luminance = sample.luminanceAt(index)
                luminanceSum += luminance
                if (luminance <= UNDEREXPOSED_LIMIT || luminance >= OVEREXPOSED_LIMIT) clippedCount++
                if (luminance <= BLACK_FRAME_LIMIT) blackCount++
                if (x + 1 < sample.width) {
                    differenceSum += abs(luminance - sample.luminanceAt(index + 1))
                    comparisonCount++
                }
                if (y + 1 < sample.height) {
                    differenceSum += abs(luminance - sample.luminanceAt(index + sample.width))
                    comparisonCount++
                }
            }
        }
        val clarity = if (comparisonCount == 0) 0.0 else (differenceSum / comparisonCount / MAX_LUMINANCE * 100.0)
        val average = luminanceSum / sample.pixelCount
        val distanceFromIdeal = abs(average - IDEAL_LUMINANCE) / IDEAL_LUMINANCE
        val clippedRatio = clippedCount.toDouble() / sample.pixelCount
        val exposure = 100.0 - distanceFromIdeal * 100.0 - clippedRatio * CLIPPING_PENALTY
        val blackFrame = (1.0 - blackCount.toDouble() / sample.pixelCount) * 100.0
        return FrameMetrics(
            clarityScore = clarity.roundToInt().coerceIn(0, 100),
            exposureScore = exposure.roundToInt().coerceIn(0, 100),
            blackFrameScore = blackFrame.roundToInt().coerceIn(0, 100),
        )
    }

    private data class FrameMetrics(
        val clarityScore: Int,
        val exposureScore: Int,
        val blackFrameScore: Int,
    )

    private companion object {
        const val MAX_LUMINANCE = 255.0
        const val IDEAL_LUMINANCE = 128.0
        const val UNDEREXPOSED_LIMIT = 8.0
        const val OVEREXPOSED_LIMIT = 247.0
        const val BLACK_FRAME_LIMIT = 16.0
        const val CLIPPING_PENALTY = 35.0
        const val DUPLICATE_DIFFERENCE_FOR_FULL_SCORE = 0.12
        const val CLARITY_WEIGHT = 0.30
        const val EXPOSURE_WEIGHT = 0.20
        const val BLACK_FRAME_WEIGHT = 0.20
        const val DUPLICATE_WEIGHT = 0.15
        const val SCENE_CHANGE_WEIGHT = 0.15
    }
}
