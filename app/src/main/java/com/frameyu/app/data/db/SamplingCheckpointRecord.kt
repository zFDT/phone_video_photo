package com.frameyu.app.data.db

import com.frameyu.app.sampling.VideoAnalysisProgress

/** 分析任务断点的纯数据表示，便于 Room 和 WorkManager 共享。 */
data class SamplingCheckpointRecord(
    val planId: String,
    val rangeIndex: Int,
    val nextTimestampMillis: Long,
    val sampledFrameCount: Long,
    val totalSampleCount: Long,
) {
    fun toProgress(): VideoAnalysisProgress = VideoAnalysisProgress(
        planId = planId,
        rangeIndex = rangeIndex,
        nextTimestampMillis = nextTimestampMillis,
        sampledFrameCount = sampledFrameCount,
        totalSampleCount = totalSampleCount,
    )

    companion object {
        fun from(progress: VideoAnalysisProgress): SamplingCheckpointRecord = SamplingCheckpointRecord(
            planId = progress.planId,
            rangeIndex = progress.rangeIndex,
            nextTimestampMillis = progress.nextTimestampMillis,
            sampledFrameCount = progress.sampledFrameCount,
            totalSampleCount = progress.totalSampleCount,
        )
    }
}
