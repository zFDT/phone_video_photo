package com.frameyu.app.analysis

import kotlin.math.abs
import kotlin.math.roundToInt

/** 使用相邻帧平均亮度差判断重复帧和基础场景变化。 */
class SceneChangeDetector(
    private val sceneChangeThreshold: Double = DEFAULT_SCENE_CHANGE_THRESHOLD,
) {
    init {
        require(sceneChangeThreshold in 0.0..1.0) { "场景变化阈值必须在 0 到 1 之间" }
    }

    /** 返回两帧的归一化平均亮度差，范围为 0 到 1。 */
    fun difference(first: FrameSample, second: FrameSample): Double {
        require(second.timestampMillis >= first.timestampMillis) { "帧时间戳必须按非递减顺序传入" }
        require(first.width == second.width && first.height == second.height) {
            "比较的帧尺寸必须一致"
        }
        if (!areTemporallyAdjacent(first, second)) return 0.0

        var totalDifference = 0.0
        for (index in 0 until first.pixelCount) {
            totalDifference += abs(first.luminanceAt(index) - second.luminanceAt(index))
        }
        return (totalDifference / first.pixelCount / MAX_LUMINANCE).coerceIn(0.0, 1.0)
    }

    /** 将场景差异映射为可展示的 0 到 100 分。 */
    fun score(first: FrameSample, second: FrameSample): Int =
        (difference(first, second) * 100.0).roundToInt().coerceIn(0, 100)

    fun isSceneChange(first: FrameSample, second: FrameSample): Boolean =
        difference(first, second) >= sceneChangeThreshold

    fun areTemporallyAdjacent(first: FrameSample, second: FrameSample): Boolean {
        require(second.timestampMillis >= first.timestampMillis) { "帧时间戳必须按非递减顺序传入" }
        return second.timestampMillis - first.timestampMillis <= MAX_ADJACENT_GAP_MILLIS
    }

    private companion object {
        const val DEFAULT_SCENE_CHANGE_THRESHOLD = 0.18
        const val MAX_LUMINANCE = 255.0
        const val MAX_ADJACENT_GAP_MILLIS = 2_000L
    }
}
