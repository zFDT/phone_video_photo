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
    data object Skipped : VideoAnalysisTaskResult
}

fun interface SamplingCheckpointStore {
    suspend fun save(progress: VideoAnalysisProgress)
}

/** 数据库协作控制在帧处理期间发生变化，不属于协程取消。 */
internal class VideoAnalysisControlChangedException : Exception()

/** 长视频采样核心，不持有 Bitmap、解码器或音频副本，可被 WorkManager 调用。 */
class VideoAnalysisTask(
    private val checkpointStore: SamplingCheckpointStore,
) {
    suspend fun run(
        plan: SamplingPlan,
        checkpoint: SamplingCheckpoint? = null,
        onProgress: suspend (VideoAnalysisProgress) -> Unit = {},
        shouldContinue: suspend () -> Boolean = { true },
        onCheckpointCommitted: suspend (VideoAnalysisProgress) -> Unit = {},
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
                val timestamps = plan.sampleTimestamps(rangeIndex)
                val startIndex = if (nextTimestamp == range.endMillis) {
                    timestamps.size
                } else {
                    timestamps.indexOf(nextTimestamp).also {
                        require(it >= 0) { "断点时间不是当前采样算法生成的时间戳" }
                    }
                }
                for (timestampIndex in startIndex until timestamps.size) {
                    if (!shouldContinue()) {
                        return VideoAnalysisTaskResult.Cancelled(
                            VideoAnalysisProgress(
                                planId,
                                rangeIndex,
                                nextTimestamp,
                                sampledCount,
                                totalSampleCount,
                            ),
                        )
                    }
                    val timestamp = timestamps[timestampIndex]
                    currentCoroutineContext().ensureActive()
                    onTimestamp(timestamp)
                    sampledCount++
                    nextTimestamp = timestamps.getOrNull(timestampIndex + 1) ?: range.endMillis
                    val progress = VideoAnalysisProgress(planId, rangeIndex, nextTimestamp, sampledCount, totalSampleCount)
                    checkpointStore.save(progress)
                    onProgress(progress)
                    onCheckpointCommitted(progress)
                }
                rangeIndex++
                if (rangeIndex < plan.ranges.size) nextTimestamp = plan.ranges[rangeIndex].startMillis
            }
            VideoAnalysisTaskResult.Completed(VideoAnalysisProgress(planId, plan.ranges.size, plan.videoDurationMillis, sampledCount, totalSampleCount))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: VideoAnalysisControlChangedException) {
            VideoAnalysisTaskResult.Cancelled(
                VideoAnalysisProgress(
                    planId,
                    rangeIndex,
                    nextTimestamp,
                    sampledCount,
                    totalSampleCount,
                ),
            )
        } catch (error: Exception) {
            VideoAnalysisTaskResult.Failed(VideoAnalysisProgress(planId, rangeIndex, nextTimestamp, sampledCount, totalSampleCount), error)
        }
    }
}
