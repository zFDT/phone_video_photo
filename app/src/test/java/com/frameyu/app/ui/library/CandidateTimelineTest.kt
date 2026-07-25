package com.frameyu.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateTimelineTest {
    @Test
    fun `根据当前时间返回所在帧和下一帧时间戳`() {
        assertEquals(
            FrameTimestamps(previousMillis = 100L, nextMillis = 133L),
            CandidateTimeline.frameTimestamps(
                durationMillis = 1_000L,
                currentMillis = 120L,
                frameRate = 30.0,
            ),
        )
    }

    @Test
    fun `帧时间戳不会超出视频边界`() {
        assertEquals(
            FrameTimestamps(previousMillis = 0L, nextMillis = 33L),
            CandidateTimeline.frameTimestamps(1_000L, -50L, 30.0),
        )
        assertEquals(
            FrameTimestamps(previousMillis = 966L, nextMillis = 1_000L),
            CandidateTimeline.frameTimestamps(1_000L, 2_000L, 30.0),
        )
    }

    @Test
    fun `时间轴值归一化并可反向还原`() {
        assertEquals(0.25f, CandidateTimeline.normalizeTime(2_500L, 10_000L), 0.0001f)
        assertEquals(2_500L, CandidateTimeline.timeFromNormalized(0.25f, 10_000L))
    }

    @Test
    fun `拖动值和时间归一化结果钳制在有效范围`() {
        assertEquals(0f, CandidateTimeline.normalizeTime(-1L, 10_000L), 0f)
        assertEquals(1f, CandidateTimeline.normalizeTime(20_000L, 10_000L), 0f)
        assertEquals(0L, CandidateTimeline.timeFromNormalized(-0.5f, 10_000L))
        assertEquals(10_000L, CandidateTimeline.timeFromNormalized(1.5f, 10_000L))
    }
}
