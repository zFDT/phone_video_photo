package com.frameyu.app.sampling

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.frameyu.app.FrameYuApplication

fun interface VideoAnalysisWorkerRunner {
    suspend fun run(
        taskId: Long,
        executionGeneration: Long,
        onProgress: suspend (VideoAnalysisProgress) -> Unit,
    ): VideoAnalysisTaskResult

    suspend fun markFailed(
        taskId: Long,
        executionGeneration: Long,
        message: String,
    ): Boolean = false
}

suspend fun VideoAnalysisWorkerRunner.run(
    taskId: Long,
    onProgress: suspend (VideoAnalysisProgress) -> Unit,
): VideoAnalysisTaskResult = run(taskId, 0L, onProgress)

/** WorkManager 前台任务，只负责生命周期、通知和结果映射。 */
class VideoAnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val runner: VideoAnalysisWorkerRunner,
) : CoroutineWorker(appContext, workerParams) {
    constructor(appContext: Context, workerParams: WorkerParameters) : this(
        appContext,
        workerParams,
        (appContext.applicationContext as? FrameYuApplication)?.videoAnalysisWorkerRunner
            ?: VideoAnalysisWorkerRunner { _, _, _ ->
                error("未配置视频分析执行器")
            },
    )

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId < 0L) return Result.success(errorData("缺少有效的分析任务 ID"))
        // v7 升级前的 WorkRequest 没有 generation；迁移已将旧活动任务提升到 generation 1。
        val executionGeneration = inputData.getLong(KEY_EXECUTION_GENERATION, 0L)
        if (executionGeneration < 0L) {
            return Result.success(errorData("缺少有效的执行代际"))
        }
        val executionKey = VideoAnalysisExecutionKey(taskId, executionGeneration)
        val execution = VideoAnalysisExecution(
            runAnalysis = { runTaskId, generation, onProgress ->
                setForeground(createForegroundInfo(runTaskId, 0))
                runner.run(runTaskId, generation) { progress ->
                    onProgress(progress)
                }
            },
            markFailed = { failedExecution, message ->
                runner.markFailed(
                    failedExecution.taskId,
                    failedExecution.executionGeneration,
                    message,
                )
            },
        )
        val outcome = execution.execute(executionKey) { progress ->
            setProgress(progress.toData())
            setForeground(createForegroundInfo(taskId, progress.percent))
        }
        if (outcome.persistenceError != null) {
            Log.e(
                TAG,
                "视频失败状态持久化失败 taskId=$taskId generation=$executionGeneration",
                outcome.persistenceError,
            )
        }
        return outcome.toWorkerResult()
    }

    private fun createForegroundInfo(taskId: Long, percent: Int): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("帧遇正在分析视频")
            .setContentText("任务 $taskId：$percent%")
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .build(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun errorData(message: String): Data = Data.Builder()
        .putString(KEY_ERROR_MESSAGE, message)
        .build()

    companion object {
        const val UNIQUE_WORK_NAME = "frameyu-video-analysis"
        const val KEY_TASK_ID = "task_id"
        const val KEY_EXECUTION_GENERATION = "execution_generation"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val CHANNEL_ID = "video-analysis"
        private const val TAG = "VideoAnalysisWorker"
        private const val NOTIFICATION_ID = 10_001
    }
}

internal fun VideoAnalysisExecutionOutcome.toWorkerResult(): ListenableWorker.Result =
    ListenableWorker.Result.success(
        when (val taskResult = result) {
            is VideoAnalysisTaskResult.Completed -> taskResult.progress.toData()
            is VideoAnalysisTaskResult.Cancelled -> taskResult.progress.toData()
                .merge(workerErrorData("分析任务已取消"))
            is VideoAnalysisTaskResult.Failed -> taskResult.progress.toData()
                .merge(workerErrorData(taskResult.error.message ?: "分析任务失败"))
            VideoAnalysisTaskResult.Skipped, null -> Data.EMPTY
        }
            .let { data ->
                errorMessage?.let { message -> data.merge(workerErrorData(message)) }
                    ?: data
            },
    )

private fun workerErrorData(message: String): Data = Data.Builder()
    .putString(VideoAnalysisWorker.KEY_ERROR_MESSAGE, message)
    .build()

class VideoAnalysisWorkerFactory(
    private val runnerProvider: () -> VideoAnalysisWorkerRunner,
) : WorkerFactory() {
    constructor(runner: VideoAnalysisWorkerRunner) : this({ runner })

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == VideoAnalysisWorker::class.java.name) {
        VideoAnalysisWorker(appContext, workerParameters, runnerProvider())
    } else {
        null
    }
}

private fun VideoAnalysisProgress.toData(): Data = Data.Builder()
    .putString("plan_id", planId)
    .putInt("range_index", rangeIndex)
    .putLong("next_timestamp_millis", nextTimestampMillis)
    .putLong("sampled_frame_count", sampledFrameCount)
    .putLong("total_sample_count", totalSampleCount)
    .putInt("progress_percent", percent)
    .build()

private fun Data.merge(other: Data): Data = Data.Builder()
    .putAll(this)
    .putAll(other)
    .build()

private val VideoAnalysisProgress.percent: Int
    get() = if (totalSampleCount <= 0L) 0 else {
        (sampledFrameCount * 100L / totalSampleCount).toInt().coerceIn(0, 100)
    }
