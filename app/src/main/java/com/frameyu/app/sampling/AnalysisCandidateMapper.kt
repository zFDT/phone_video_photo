package com.frameyu.app.sampling

import com.frameyu.app.analysis.FrameScore
import com.frameyu.app.data.db.AnalysisCandidateEntity
import java.security.MessageDigest

/** 将规则评分转换为 Room 候选帧记录，并保证同一媒体时间点可幂等写入。 */
object AnalysisCandidateMapper {
    fun toEntity(mediaAssetId: Long, score: FrameScore): AnalysisCandidateEntity = AnalysisCandidateEntity(
        id = stableId(mediaAssetId, score.timestampMillis),
        mediaAssetId = mediaAssetId,
        timestampMillis = score.timestampMillis,
        score = score.totalScore,
        sharpnessScore = score.clarityScore,
        subjectScore = 0,
        expressionScore = 0,
        compositionScore = 0,
        sceneScore = score.sceneChangeScore,
        thumbnailPath = null,
        isSelected = false,
        modelStatus = CandidateModelStatus.PENDING.name,
        modelErrorMessage = null,
    )

    private fun stableId(mediaAssetId: Long, timestampMillis: Long): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$mediaAssetId:$timestampMillis".toByteArray())
        var value = 0L
        repeat(Long.SIZE_BYTES) { index ->
            value = (value shl 8) or (digest[index].toLong() and 0xFFL)
        }
        return (value and Long.MAX_VALUE).coerceAtLeast(1L)
    }
}
