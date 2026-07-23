package com.frameyu.app.analysis

/** 单帧基础画质与时间连续性评分，所有分数均为 0 到 100。 */
data class FrameScore(
    val frameIndex: Int,
    val timestampMillis: Long,
    val clarityScore: Int,
    val exposureScore: Int,
    val blackFrameScore: Int,
    val duplicateScore: Int,
    val sceneChangeScore: Int,
    val totalScore: Int,
) {
    init {
        require(frameIndex >= 0) { "帧序号不能为负数" }
        require(timestampMillis >= 0L) { "帧时间戳不能为负数" }
        listOf(
            clarityScore,
            exposureScore,
            blackFrameScore,
            duplicateScore,
            sceneChangeScore,
            totalScore,
        ).forEach { score ->
            require(score in SCORE_RANGE) { "帧评分必须在 0 到 100 之间" }
        }
    }

    private companion object {
        val SCORE_RANGE = 0..100
    }
}
