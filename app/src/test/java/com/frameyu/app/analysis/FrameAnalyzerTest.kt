package com.frameyu.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAnalyzerTest {
    @Test
    fun `批量分析保持帧顺序和时间戳`() {
        val samples = listOf(
            sample(frameIndex = 4, timestampMillis = 400L, luminance = 100),
            sample(frameIndex = 8, timestampMillis = 800L, luminance = 140),
        )

        val scores = RuleBasedFrameAnalyzer().analyze(samples)

        assertEquals(listOf(4, 8), scores.map { it.frameIndex })
        assertEquals(listOf(400L, 800L), scores.map { it.timestampMillis })
    }

    @Test
    fun `空输入返回空结果`() {
        assertTrue(RuleBasedFrameAnalyzer().analyze(emptyList()).isEmpty())
    }

    @Test
    fun `分析结果总分在零到一百`() {
        val scores = RuleBasedFrameAnalyzer().analyze(
            listOf(sample(frameIndex = 0, timestampMillis = 0L, luminance = 0))
        )

        assertTrue(scores.single().totalScore in 0..100)
    }

    private fun sample(frameIndex: Int, timestampMillis: Long, luminance: Int): FrameSample = FrameSample(
        frameIndex = frameIndex,
        timestampMillis = timestampMillis,
        width = 2,
        height = 2,
        pixels = IntArray(4) { argb(luminance, luminance, luminance) },
    )

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
