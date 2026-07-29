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
import java.io.Closeable
import kotlinx.coroutines.delay

/** 视频帧解码薄边界，便于在不读取真实媒体文件时验证生产 runner。 */
interface VideoFrameSource : Closeable {
    fun setDataSource(context: Context, uri: Uri)

    fun getFrameAt(timestampMillis: Long): Bitmap?
}

private class MediaMetadataRetrieverFrameSource : VideoFrameSource {
    private val retriever = MediaMetadataRetriever()

    override fun setDataSource(context: Context, uri: Uri) {
        retriever.setDataSource(context, uri)
    }

    override fun getFrameAt(timestampMillis: Long): Bitmap? =
        retriever.getFrameAtTime(
            timestampMillis * 1_000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
        )

    override fun close() {
        retriever.release()
    }
}

/** 使用 Room 媒体索引、规则评分和本地模型执行视频分析。 */
class RoomVideoAnalysisWorkerRunner(
    private val context: Context,
    private val database: FrameYuDatabase,
    private val lifecycleStore: VideoAnalysisRunnerStore = RoomVideoAnalysisLifecycleStore(database),
    private val frameModelAnalyzerProvider: () -> FrameModelAnalyzer,
    private val frameModelStoreProvider: () -> RoomVideoFrameModelStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val concertModeEnabledProvider: () -> Boolean = { false },
    private val thermalStatusProvider: () -> ThermalStatus = { ThermalStatus.NOMINAL },
    private val thermalPollIntervalMillis: Long = THERMAL_POLL_INTERVAL_MILLIS,
    private val frameSourceProvider: () -> VideoFrameSource = {
        MediaMetadataRetrieverFrameSource()
    },
) : VideoAnalysisWorkerRunner {
    override suspend fun run(
        taskId: Long,
        executionGeneration: Long,
        onProgress: suspend (VideoAnalysisProgress) -> Unit,
    ): VideoAnalysisTaskResult {
        val execution = VideoAnalysisExecutionKey(taskId, executionGeneration)
        var frameSource: VideoFrameSource? = null
        return runTaskLifecycle(
            taskId = taskId,
            markCancelled = { cancelledTaskId, cancelled ->
                lifecycleStore.markCancelled(
                    VideoAnalysisExecutionKey(cancelledTaskId, executionGeneration),
                    cancelled.message ?: "视频分析已取消",
                )
            },
            markFailed = { failedTaskId, error ->
                lifecycleStore.markFailed(
                    VideoAnalysisExecutionKey(failedTaskId, executionGeneration),
                    error.message ?: "视频分析失败",
                )
            },
            closeResource = { frameSource?.close() },
        ) {
            val claimResult = lifecycleStore.claim(execution)
            if (claimResult == VideoAnalysisClaimResult.STALE) {
                return@runTaskLifecycle VideoAnalysisTaskResult.Skipped
            }
            runAnalysis(execution, claimResult, onProgress) { createdSource ->
                frameSource = createdSource
            }
        }
    }

    override suspend fun markFailed(
        taskId: Long,
        executionGeneration: Long,
        message: String,
    ): Boolean = lifecycleStore.markFailed(
        VideoAnalysisExecutionKey(taskId, executionGeneration),
        message,
    )

    private suspend fun runAnalysis(
        execution: VideoAnalysisExecutionKey,
        claimResult: VideoAnalysisClaimResult,
        onProgress: suspend (VideoAnalysisProgress) -> Unit,
        registerFrameSource: (VideoFrameSource) -> Unit,
    ): VideoAnalysisTaskResult {
        val taskId = execution.taskId
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
        var previousSample: FrameSample? = null
        var prewarmTimestampMillis = if (claimResult == VideoAnalysisClaimResult.RESUME) {
            checkpoint?.let(plan::previousCanonicalTimestamp)
        } else {
            null
        }
        var frameIndex = checkpoint?.sampledFrameCount?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        val analyzer = RuleBasedFrameAnalyzer()
        val runner = VideoAnalysisTask(
            RoomSamplingCheckpointStore(
                taskId = taskId,
                dao = database.analysisTaskDao(),
                nowMillis = nowMillis,
                executionGeneration = execution.executionGeneration,
            ),
        )

        return kotlin.run {
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
            val activeFrameSource = frameSourceProvider()
            registerFrameSource(activeFrameSource)
            activeFrameSource.setDataSource(context, Uri.parse(media.uri))
            val result = runner.run(
                plan = plan,
                checkpoint = checkpoint,
                onProgress = onProgress,
                shouldContinue = {
                    lifecycleStore.controlState(taskId)?.let { control ->
                        control.status == AnalysisTask.Status.RUNNING.name &&
                            control.executionGeneration == execution.executionGeneration
                    } == true
                },
            ) { timestampMillis ->
                prewarmTimestampMillis?.let { prewarmTimestamp ->
                    awaitThermalWindow(execution)
                    val prewarmBitmap = activeFrameSource.getFrameAt(prewarmTimestamp)
                        ?: error("无法读取时间 ${prewarmTimestamp}ms 的视频帧")
                    try {
                        val analysisBitmap = prewarmBitmap.scaleForAnalysis()
                        try {
                            previousSample = FrameSample(
                                frameIndex = (frameIndex - 1).coerceAtLeast(0),
                                timestampMillis = prewarmTimestamp,
                                width = analysisBitmap.width,
                                height = analysisBitmap.height,
                                pixels = IntArray(analysisBitmap.width * analysisBitmap.height).also {
                                    analysisBitmap.getPixels(
                                        it,
                                        0,
                                        analysisBitmap.width,
                                        0,
                                        0,
                                        analysisBitmap.width,
                                        analysisBitmap.height,
                                    )
                                },
                            )
                        } finally {
                            if (analysisBitmap !== prewarmBitmap) analysisBitmap.recycle()
                        }
                    } finally {
                        prewarmBitmap.recycle()
                    }
                    prewarmTimestampMillis = null
                }
                awaitThermalWindow(execution)
                val bitmap = activeFrameSource.getFrameAt(timestampMillis)
                    ?: error("无法读取时间 ${timestampMillis}ms 的视频帧")
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
                VideoAnalysisTaskResult.Skipped -> return@run result
            }
            val errorMessage = (result as? VideoAnalysisTaskResult.Failed)?.error?.message
            when (status) {
                AnalysisTask.Status.COMPLETED -> lifecycleStore.markCompleted(execution)
                AnalysisTask.Status.CANCELLED -> lifecycleStore.markCancelled(
                    execution,
                    "视频分析已取消",
                )
                AnalysisTask.Status.FAILED -> lifecycleStore.markFailed(
                    execution,
                    errorMessage ?: "视频分析失败",
                )
                else -> Unit
            }
            result
        }
    }

    /** 温控暂停期间等待设备恢复；临界温度转为可持久化普通失败。 */
    private suspend fun awaitThermalWindow(execution: VideoAnalysisExecutionKey) {
        val monitor = ThermalMonitor()
        while (true) {
            val control = lifecycleStore.controlState(execution.taskId)
            if (
                control?.status != AnalysisTask.Status.RUNNING.name ||
                control.executionGeneration != execution.executionGeneration
            ) {
                throw VideoAnalysisControlChangedException()
            }
            when (monitor.stateFor(thermalStatusProvider())) {
                ThermalState.RUNNING -> return
                ThermalState.PAUSED -> delay(thermalPollIntervalMillis)
                ThermalState.STOPPED -> throw ThermalProtectionException(
                    "设备温度过高，已暂停分析",
                )
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

private class ThermalProtectionException(message: String) : Exception(message)
