package com.frameyu.app.sampling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcertAudioAnalyzerTest {
    private val analyzer = ConcertAudioAnalyzer()

    @Test
    fun `高能量窗口生成高价值候选`() {
        val result = analyzer.analyze(
            videoDurationMillis = 10_000L,
            windows = listOf(AudioFeatureWindow(2_000L, 3_000L, 0.9, 0.1, 0.1)),
        )

        assertEquals(
            listOf(SampleRange(2_000L, 3_000L, 24.0, SamplingTier.HIGH_VALUE)),
            result,
        )
    }

    @Test
    fun `频谱突发变化或节奏增强生成重点候选`() {
        val result = analyzer.analyze(
            videoDurationMillis = 10_000L,
            windows = listOf(
                AudioFeatureWindow(1_000L, 2_000L, 0.3, 0.8, 0.2),
                AudioFeatureWindow(4_000L, 5_000L, 0.3, 0.2, 0.8),
            ),
        )

        assertEquals(2, result.size)
        assertTrue(result.all { it.tier == SamplingTier.FOCUSED })
        assertTrue(result.all { it.framesPerSecond == 8.0 })
    }

    @Test
    fun `静音窗口不会生成候选`() {
        val result = analyzer.analyze(
            videoDurationMillis = 10_000L,
            windows = listOf(AudioFeatureWindow(0L, 1_000L, 0.01, 0.9, 0.9)),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `相邻和重叠候选会合并并保留更高采样密度`() {
        val result = analyzer.analyze(
            videoDurationMillis = 10_000L,
            windows = listOf(
                AudioFeatureWindow(1_000L, 2_000L, 0.3, 0.8, 0.1),
                AudioFeatureWindow(2_000L, 3_000L, 0.8, 0.1, 0.1),
                AudioFeatureWindow(2_500L, 3_500L, 0.3, 0.1, 0.8),
            ),
        )

        assertEquals(
            listOf(SampleRange(1_000L, 3_500L, 24.0, SamplingTier.HIGH_VALUE)),
            result,
        )
    }

    @Test
    fun `输入乱序且重复窗口时输出按时间排序并去重`() {
        val window = AudioFeatureWindow(3_000L, 4_000L, 0.8, 0.1, 0.1)
        val result = analyzer.analyze(
            videoDurationMillis = 10_000L,
            windows = listOf(window, window),
        )

        assertEquals(
            listOf(SampleRange(3_000L, 4_000L, 24.0, SamplingTier.HIGH_VALUE)),
            result,
        )
    }

    @Test
    fun `输出候选会被限制在视频时长内`() {
        val result = analyzer.analyze(
            videoDurationMillis = 2_500L,
            windows = listOf(
                AudioFeatureWindow(2_000L, 3_000L, 0.9, 0.0, 0.0),
                AudioFeatureWindow(3_000L, 4_000L, 0.9, 0.0, 0.0),
            ),
        )

        assertEquals(
            listOf(SampleRange(2_000L, 2_500L, 24.0, SamplingTier.HIGH_VALUE)),
            result,
        )
    }

    @Test
    fun `视频时长必须不超过两小时`() {
        assertThrows<IllegalArgumentException> {
            analyzer.analyze(SamplingPlan.MAX_VIDEO_DURATION_MILLIS + 1L, emptyList())
        }
    }

    @Test
    fun `窗口特征必须是归一化有限值`() {
        assertThrows<IllegalArgumentException> {
            AudioFeatureWindow(0L, 1_000L, Double.NaN, 0.0, 0.0)
        }
        assertThrows<IllegalArgumentException> {
            AudioFeatureWindow(0L, 1_000L, 0.0, 1.1, 0.0)
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue(error is T)
            return
        }
        throw AssertionError("预期抛出 ${T::class.java.simpleName}")
    }
}
