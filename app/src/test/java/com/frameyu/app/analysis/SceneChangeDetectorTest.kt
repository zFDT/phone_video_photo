package com.frameyu.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneChangeDetectorTest {
    private val detector = SceneChangeDetector()

    @Test
    fun `相同画面的差异为零`() {
        val sample = solidSample(80)

        assertEquals(0.0, detector.difference(sample, sample), 0.001)
        assertEquals(0, detector.score(sample, sample))
    }

    @Test
    fun `黑白画面的差异达到最大`() {
        val dark = solidSample(0)
        val bright = solidSample(255)

        assertEquals(1.0, detector.difference(dark, bright), 0.001)
        assertEquals(100, detector.score(dark, bright))
        assertTrue(detector.isSceneChange(dark, bright))
    }

    @Test
    fun `轻微变化不会被判定为场景切换`() {
        val first = solidSample(100)
        val second = solidSample(110)

        assertTrue(!detector.isSceneChange(first, second))
    }

    @Test
    fun `时间间隔过大时不把非邻近帧当作场景切换`() {
        val first = solidSample(0, timestampMillis = 0L)
        val second = solidSample(255, timestampMillis = 2_001L)

        assertEquals(0.0, detector.difference(first, second), 0.001)
    }

    @Test
    fun `拒绝逆序时间戳`() {
        val first = solidSample(0, timestampMillis = 100L)
        val second = solidSample(255, timestampMillis = 99L)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            detector.difference(first, second)
        }
    }

    private fun solidSample(luminance: Int, timestampMillis: Long = 0L): FrameSample = FrameSample(
        frameIndex = luminance,
        timestampMillis = timestampMillis,
        width = 2,
        height = 2,
        pixels = IntArray(4) { argb(luminance, luminance, luminance) },
    )

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
