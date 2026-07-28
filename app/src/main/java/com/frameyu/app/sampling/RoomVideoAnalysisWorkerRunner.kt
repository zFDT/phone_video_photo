package com.frameyu.app.sampling

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.frameyu.app.analysis.FrameAnalysisContext
import com.frameyu.app.analysis.FrameSample
import com.frameyu.app.analysis.RuleBasedFrameAnalyzer
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.db.RoomSamplingCheckpointStore
import com.frameyu.app.data.db.SamplingCheckpointRecord
import com.frameyu.app.domain.model.AnalysisTask
import com.frameyu.app.system.ThermalMonitor
import com.frameyu.app.system.ThermalState
import com.frameyu.app.system.ThermalStatus
import com.frameyu.app.vision.FrameModelAnalyzer
import kotlinx.coroutines.delay

/** 使用 Room 媒体索引、规则评分和本地模型执行视频分析。 */
class RoomVideoAnalysisWorkerRunner(
    private val context: Context,
    private val database: FrameYuDatabase,
    private val frameModelAnalyzerProvider: () -> FrameModelAnalyzer,
    private val frameModelStoreProvider: () -> RoomVideoFrameModelStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val concertModeEnabledProvider: () -> Boolean = { false },
    private val thermalStatusProvider: () -> ThermalStatus = { ThermalStatus.NOMINAL },
) : VideoAnalysisWorkerRunner {
    override suspend fun run(
        taskId: Long,
        onProgress: suspend (VideoAnalysisProgress) -> Unit,
    ): VideoAnalysisTaskResult {
        val task = database.analysisTaskDao().getTaskProgress(taskId)
            ?: error("分析任务不存在：$taskId")
        val media = database.mediaAssetDao().getById(task.mediaAssetId)
            ?: error("媒体索引不存在：${task.mediaAssetId}")
        require(media.durationMillis > 0L) { "视频没有有效时长" }

        val coarseFramesPerSecond = if (concertModeEnabledProvider()) 2.0 else 1.0
        val plan = SamplingPlan.create(
            videoDurationMillis = media.durationMillis,
            coarseFramesPerSecond = coarseFramesPerSecond,
        )
        val checkpoint = task.planId?.let { planId ->
            if (planId == plan.planId) {
                SamplingCheckpointRecord(
                    planId = planId,
                    rangeIndex = task.rangeIndex,
                    nextTimestampMillis = task.currentTimestampMillis,
                    sampledFrameCount = task.sampledFrameCount,
                    totalSampleCount = task.totalSampleCount,
                ).toCheckpoint()
            } else {
                null
            }
        }
        database.analysisTaskDao().updateTaskStatus(taskId, AnalysisTask.Status.RUNNING.name, null, nowMillis())

        val retriever = MediaMetadataRetriever()
        var previousSample: FrameSample? = null
        var frameIndex = checkpoint?.sampledFrameCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        val analyzer = RuleBasedFrameAnalyzer()
        val runner = VideoAnalysisTask(
            RoomSamplingCheckpointStore(
                taskId = taskId,
                dao = database.analysisTaskDao(),
                nowMillis = nowMillis,
            ),
        )

        return try {
            val frameModelStore = frameModelStoreProvider()
            val frameModelOrchestrator = VideoFrameModelOrchestrator(
                analyzerProvider = {
                    val frameModelAnalyzer = frameModelAnalyzerProvider()
                    VideoFrameModelOperation<Bitmap> { bitmap, timestampMillis, highValue ->
                        frameModelAnalyzer.analyze(bitmap, timestampMillis, highValue)
                    }
                },
                store = frameModelStore,
            )
            retriever.setDataSource(context, Uri.parse(media.uri))
            val result = runner.run(
                plan = plan,
                checkpoint = checkpoint,
                onProgress = onProgress,
            ) { timestampMillis ->
                awaitThermalWindow()
                val bitmap = retriever.getFrameAtTime(
                    timestampMillis * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: error("无法读取时间 ${timestampMillis}ms 的视频帧")
                try {
                    val analysisBitmap = bitmap.scaleForAnalysis()
                    try {
                        val sample = FrameSample(
                            frameIndex = frameIndex++,
                            timestampMillis = timestampMillis,
                            width = analysisBitmap.width,
                            height = analysisBitmap.height,
                            pixels = IntArray(analysisBitmap.width * analysisBitmap.height).also {
                                analysisBitmap.getPixels(it, 0, analysisBitmap.width, 0, 0, analysisBitmap.width, analysisBitmap.height)
                            },
                        )
                        val score = analyzer.analyze(sample, FrameAnalysisContext(previousSample))
                        frameModelOrchestrator.process(
                            mediaId = media.id,
                            candidate = AnalysisCandidateMapper.toEntity(media.id, score),
                            frame = analysisBitmap,
                        )
                        previousSample = sample
                    } finally {
                        if (analysisBitmap !== bitmap) analysisBitmap.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            if (result is VideoAnalysisTaskResult.Completed) {
                VideoClassificationAggregationCoordinator(frameModelStore).rebuildAndSave(
                    mediaId = media.id,
                    modelSha256 = frameModelStore.classificationSha256,
                    plannedTimestampsMillis = plan.sampleTimestamps(),
                )
            }
            val status = when (result) {
                is VideoAnalysisTaskResult.Completed -> AnalysisTask.Status.COMPLETED
                is VideoAnalysisTaskResult.Cancelled -> AnalysisTask.Status.CANCELLED
                is VideoAnalysisTaskResult.Failed -> AnalysisTask.Status.FAILED
            }
            val errorMessage = (result as? VideoAnalysisTaskResult.Failed)?.error?.message
            database.analysisTaskDao().updateTaskStatus(taskId, status.name, errorMessage, nowMillis())
            result
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            database.analysisTaskDao().updateTaskStatus(
                taskId,
                AnalysisTask.Status.FAILED.name,
                error.message ?: "视频分析失败",
                nowMillis(),
            )
            throw error
        } finally {
            retriever.release()
        }
    }

    /** 温控暂停期间等待设备恢复；临界温度保留协程取消语义并向 Worker 传播。 */
    private suspend fun awaitThermalWindow() {
        val monitor = ThermalMonitor()
        while (true) {
            when (monitor.stateFor(thermalStatusProvider())) {
                ThermalState.RUNNING -> return
                ThermalState.PAUSED -> delay(THERMAL_POLL_INTERVAL_MILLIS)
                ThermalState.STOPPED -> throw kotlinx.coroutines.CancellationException("设备温度过高，已暂停分析")
            }
        }
    }

    private fun Bitmap.scaleForAnalysis(): Bitmap {
        val maxEdge = maxOf(width, height)
        if (maxEdge <= MAX_ANALYSIS_EDGE) return this
        val scale = MAX_ANALYSIS_EDGE.toFloat() / maxEdge.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val MAX_ANALYSIS_EDGE = 1_024
        const val THERMAL_POLL_INTERVAL_MILLIS = 5_000L
    }
}
