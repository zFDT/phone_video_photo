package com.frameyu.app.sampling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class VideoAnalysisProgress(
    val planId: String,
    val rangeIndex: Int,
    val nextTimestampMillis: Long,
    val sampledFrameCount: Long,
    val totalSampleCount: Long,
)

sealed interface VideoAnalysisTaskResult {
    data class Completed(val progress: VideoAnalysisProgress) : VideoAnalysisTaskResult
    data class Cancelled(val progress: VideoAnalysisProgress) : VideoAnalysisTaskResult
    data class Failed(val progress: VideoAnalysisProgress, val error: Throwable) : VideoAnalysisTaskResult
}

fun interface SamplingCheckpointStore {
    suspend fun save(progress: VideoAnalysisProgress)
}

/** 长视频采样核心，不持有 Bitmap、解码器或音频副本，可被 WorkManager 调用。 */
class VideoAnalysisTask(
    private val checkpointStore: SamplingCheckpointStore,
) {
    suspend fun run(
        plan: SamplingPlan,
        checkpoint: SamplingCheckpoint? = null,
        onProgress: suspend (VideoAnalysisProgress) -> Unit = {},
        onTimestamp: suspend (Long) -> Unit,
    ): VideoAnalysisTaskResult {
        var rangeIndex = checkpoint?.rangeIndex ?: 0
        var nextTimestamp = checkpoint?.nextTimestampMillis ?: plan.ranges.first().startMillis
        var sampledCount = checkpoint?.sampledFrameCount ?: 0L
        val totalSampleCount = plan.sampleTimestamps().size.toLong()
        val planId = plan.planId

        return try {
            require(checkpoint == null || checkpoint.planId == planId) { "断点不属于当前采样计划" }
            while (rangeIndex < plan.ranges.size) {
                val range = plan.ranges[rangeIndex]
                val timestamps = plan.sampleTimestampsForRange(range, nextTimestamp)
                for (timestamp in timestamps) {
                    currentCoroutineContext().ensureActive()
                    onTimestamp(timestamp)
                    sampledCount++
                    nextTimestamp = timestamp + (1_000.0 / range.framesPerSecond).toLong().coerceAtLeast(1L)
                    val progress = VideoAnalysisProgress(planId, rangeIndex, nextTimestamp, sampledCount, totalSampleCount)
                    checkpointStore.save(progress)
                    onProgress(progress)
                }
                rangeIndex++
                if (rangeIndex < plan.ranges.size) nextTimestamp = plan.ranges[rangeIndex].startMillis
            }
            VideoAnalysisTaskResult.Completed(VideoAnalysisProgress(planId, plan.ranges.size, plan.videoDurationMillis, sampledCount, totalSampleCount))
        } catch (cancelled: CancellationException) {
            VideoAnalysisTaskResult.Cancelled(VideoAnalysisProgress(planId, rangeIndex, nextTimestamp, sampledCount, totalSampleCount))
        } catch (error: Throwable) {
            VideoAnalysisTaskResult.Failed(VideoAnalysisProgress(planId, rangeIndex, nextTimestamp, sampledCount, totalSampleCount), error)
        }
    }

    private fun SamplingPlan.sampleTimestampsForRange(range: SampleRange, fromMillis: Long): List<Long> {
        val start = fromMillis.coerceAtLeast(range.startMillis)
        val step = (1_000.0 / range.framesPerSecond).toLong().coerceAtLeast(1L)
        return generateSequence(start) { it + step }.takeWhile { it < range.endMillis }.toList()
    }
}
