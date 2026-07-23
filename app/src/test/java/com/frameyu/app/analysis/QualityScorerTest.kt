package com.frameyu.app.analysis

import org.junit.Assert.assertTrue
import org.junit.Test

class QualityScorerTest {
    private val scorer = QualityScorer()

    @Test
    fun `中等曝光且有边缘细节的画面评分高于黑帧`() {
        val normal = checkerboardSample(40)
        val black = solidSample(0)

        val normalScore = scorer.score(normal)
        val blackScore = scorer.score(black)

        assertTrue(normalScore.exposureScore > blackScore.exposureScore)
        assertTrue(normalScore.clarityScore > blackScore.clarityScore)
        assertTrue(normalScore.blackFrameScore > blackScore.blackFrameScore)
        assertTrue(normalScore.totalScore > blackScore.totalScore)
    }

    @Test
    fun `重复帧降低重复分而场景变化提高场景分`() {
        val first = solidSample(100, frameIndex = 0)
        val repeated = solidSample(100, frameIndex = 1)
        val changed = solidSample(240, frameIndex = 2)

        val repeatedScore = scorer.score(repeated, previous = first)
        val changedScore = scorer.score(changed, previous = first)

        assertTrue(repeatedScore.duplicateScore < changedScore.duplicateScore)
        assertTrue(repeatedScore.sceneChangeScore < changedScore.sceneChangeScore)
    }

    @Test
    fun `所有分数都限制在零到一百`() {
        val score = scorer.score(solidSample(255))

        listOf(
            score.clarityScore,
            score.exposureScore,
            score.blackFrameScore,
            score.duplicateScore,
            score.sceneChangeScore,
            score.totalScore,
        ).forEach { value ->
            assertTrue(value in 0..100)
        }
    }

    private fun solidSample(luminance: Int, frameIndex: Int = luminance): FrameSample = FrameSample(
        frameIndex = frameIndex,
        timestampMillis = frameIndex * 100L,
        width = 4,
        height = 4,
        pixels = IntArray(16) { argb(luminance, luminance, luminance) },
    )

    private fun checkerboardSample(luminance: Int): FrameSample = FrameSample(
        frameIndex = 0,
        timestampMillis = 0L,
        width = 4,
        height = 4,
        pixels = IntArray(16) { index ->
            val value = if ((index / 4 + index % 4) % 2 == 0) luminance else 255 - luminance
            argb(value, value, value)
        },
    )

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
