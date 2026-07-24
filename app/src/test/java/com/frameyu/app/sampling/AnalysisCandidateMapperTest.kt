package com.frameyu.app.sampling

import com.frameyu.app.analysis.FrameScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisCandidateMapperTest {
    @Test
    fun `评分结果映射为可持久化候选帧`() {
        val score = FrameScore(3, 1_250L, 80, 70, 90, 60, 50, 75)

        val candidate = AnalysisCandidateMapper.toEntity(42L, score)

        assertTrue(candidate.id > 0L)
        assertEquals(42L, candidate.mediaAssetId)
        assertEquals(1_250L, candidate.timestampMillis)
        assertEquals(75, candidate.score)
        assertEquals(80, candidate.sharpnessScore)
        assertEquals(50, candidate.sceneScore)
        assertEquals(false, candidate.isSelected)
    }

    @Test
    fun `相同媒体和时间戳生成稳定候选 ID`() {
        val score = FrameScore(0, 2_000L, 1, 2, 3, 4, 5, 6)

        val first = AnalysisCandidateMapper.toEntity(7L, score)
        val second = AnalysisCandidateMapper.toEntity(7L, score)

        assertEquals(first.id, second.id)
    }
}
