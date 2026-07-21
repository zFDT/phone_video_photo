package com.frameyu.app.domain.model

/** 视频分析产生的候选帧。所有时间字段均使用毫秒。 */
data class AnalysisCandidate(
    val id: Long,
    val mediaAssetId: Long,
    val timestampMillis: Long,
    val score: Int,
    val sharpnessScore: Int,
    val subjectScore: Int,
    val expressionScore: Int,
    val compositionScore: Int,
    val sceneScore: Int,
    val thumbnailPath: String?,
    val isSelected: Boolean,
) {
    init {
        require(id >= 0) { "候选帧 ID 不能为负数" }
        require(mediaAssetId >= 0) { "媒体 ID 不能为负数" }
        require(timestampMillis >= 0) { "候选帧时间点不能为负数" }
        requireScore("总分", score)
        requireScore("清晰度评分", sharpnessScore)
        requireScore("主体评分", subjectScore)
        requireScore("表情评分", expressionScore)
        requireScore("构图评分", compositionScore)
        requireScore("场景评分", sceneScore)
    }

    private fun requireScore(name: String, value: Int) {
        require(value in SCORE_RANGE) { "$name 必须在 0 到 100 之间" }
    }

    private companion object {
        val SCORE_RANGE = 0..100
    }
}
