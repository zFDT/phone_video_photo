package com.frameyu.app.sampling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameSamplerTest {
    @Test
    fun `采样器会去除采样计划中的重复时间戳`() {
        val plan = SamplingPlan.create(
            videoDurationMillis = 2_000L,
            requestedRanges = listOf(
                SampleRange(0L, 1_000L, 10.0, SamplingTier.FOCUSED),
                SampleRange(1_000L, 2_000L, 10.0, SamplingTier.FOCUSED),
            ),
        )
        val decoded = mutableListOf<Long>()

        val result = FrameSamplingCoordinator<Int>().sample(
            plan = plan,
            decoder = FrameDecoder { timestampMillis ->
                decoded += timestampMillis
                DecodedFrame(timestampMillis, timestampMillis, timestampMillis.toInt())
            },
            callback = FrameSamplingCallback { },
        )

        assertEquals(FrameSamplingStatus.COMPLETED, result.status)
        assertEquals(decoded.distinct(), decoded)
        assertEquals(decoded, result.sampledTimestampsMillis)
    }

    @Test
    fun `取消后停止后续解码并返回取消状态`() {
        val plan = SamplingPlan.create(videoDurationMillis = 5_000L)
        val handle = FrameSamplingHandle()
        val decoded = mutableListOf<Long>()

        val result = FrameSamplingCoordinator<Int>().sample(
            plan = plan,
            decoder = FrameDecoder { timestampMillis ->
                decoded += timestampMillis
                handle.cancel()
                DecodedFrame(timestampMillis, timestampMillis, timestampMillis.toInt())
            },
            callback = FrameSamplingCallback { },
            handle = handle,
        )

        assertEquals(FrameSamplingStatus.CANCELLED, result.status)
        assertTrue(decoded.size < plan.sampleTimestamps().distinct().size)
        assertTrue(handle.isCancelled)
    }

    @Test
    fun `解码异常返回失败状态并保留已采样时间戳`() {
        val expected = IllegalStateException("解码失败")
        val plan = SamplingPlan.create(videoDurationMillis = 3_000L)
        var decodeCount = 0

        val result = FrameSamplingCoordinator<Int>().sample(
            plan = plan,
            decoder = FrameDecoder { timestampMillis ->
                decodeCount += 1
                if (decodeCount == 2) throw expected
                DecodedFrame(timestampMillis, timestampMillis, timestampMillis.toInt())
            },
            callback = FrameSamplingCallback { },
        )

        assertEquals(FrameSamplingStatus.FAILED, result.status)
        assertEquals(listOf(0L), result.sampledTimestampsMillis)
        assertEquals(expected, result.error)
    }

    @Test
    fun `采样帧明确由回调方负责释放`() {
        val decoded = DecodedFrame(0L, 0L, "frame")

        assertEquals(FrameOwnership.CALLER_OWNS, decoded.ownership)
    }
}
