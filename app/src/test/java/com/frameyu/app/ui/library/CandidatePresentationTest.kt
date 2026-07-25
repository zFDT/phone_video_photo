package com.frameyu.app.ui.library

import com.frameyu.app.data.db.AnalysisCandidateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidatePresentationTest {
    @Test
    fun `候选帧时间点格式化为分秒`() {
        assertEquals("00:00.000", formatCandidateTimestamp(0L))
        assertEquals("01:05.250", formatCandidateTimestamp(65_250L))
    }

    @Test
    fun `候选帧支持按评分和时间排序`() {
        val candidates = listOf(
            candidate(id = 1L, timestampMillis = 2_000L, score = 80),
            candidate(id = 2L, timestampMillis = 1_000L, score = 95),
        )

        assertEquals(listOf(2L, 1L), sortCandidates(candidates, sortByScore = true).map { it.id })
        assertEquals(listOf(2L, 1L), sortCandidates(candidates, sortByScore = false).map { it.id })
    }

    @Test
    fun `动态导出时长显示为秒`() {
        assertEquals("3 秒", formatDurationSeconds(3_000L))
        assertEquals("10 秒", formatDurationSeconds(10_000L))
    }

    @Test
    fun `批量选择评分最高的候选帧`() {
        val candidates = listOf(
            candidate(id = 1L, timestampMillis = 2_000L, score = 70),
            candidate(id = 2L, timestampMillis = 1_000L, score = 95),
            candidate(id = 3L, timestampMillis = 3_000L, score = 88),
        )

        assertEquals(listOf(2L, 3L), topCandidateIds(candidates, 2))
    }

    @Test
    fun `批量导出只返回已选候选帧`() {
        val candidates = listOf(
            candidate(id = 1L, timestampMillis = 2_000L, score = 70, isSelected = true),
            candidate(id = 2L, timestampMillis = 1_000L, score = 95),
        )

        assertEquals(listOf(1L), selectedCandidateIds(candidates))
    }

    private fun candidate(
        id: Long,
        timestampMillis: Long,
        score: Int,
        isSelected: Boolean = false,
    ) = AnalysisCandidateEntity(
        id = id,
        mediaAssetId = 10L,
        timestampMillis = timestampMillis,
        score = score,
        sharpnessScore = score,
        subjectScore = 0,
        expressionScore = 0,
        compositionScore = score,
        sceneScore = 0,
        thumbnailPath = null,
        isSelected = isSelected,
    )
}
