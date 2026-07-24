package com.frameyu.app.data.db

import com.frameyu.app.sampling.VideoAnalysisProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class SamplingCheckpointRecordTest {
    @Test
    fun `分析进度可以往返转换为断点记录`() {
        val progress = VideoAnalysisProgress(
            planId = "plan-1",
            rangeIndex = 2,
            nextTimestampMillis = 12_500L,
            sampledFrameCount = 125L,
            totalSampleCount = 500L,
        )

        val restored = SamplingCheckpointRecord.from(progress).toProgress()

        assertEquals(progress, restored)
    }
}
