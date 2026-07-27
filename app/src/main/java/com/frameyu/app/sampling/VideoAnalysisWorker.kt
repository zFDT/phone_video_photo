package com.frameyu.app.sampling

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.frameyu.app.FrameYuApplication
import kotlinx.coroutines.CancellationException

fun interface VideoAnalysisWorkerRunner {
    suspend fun run(
        taskId: Long,
        onProgress: suspend (VideoAnalysisProgress) -> Unit,
    ): VideoAnalysisTaskResult
}

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
            ?: VideoAnalysisWorkerRunner { _, _ ->
                error("未配置视频分析执行器")
            },
    )

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId < 0L) return Result.failure(errorData("缺少有效的分析任务 ID"))

        return try {
            setForeground(createForegroundInfo(taskId, 0))
            when (val result = runner.run(taskId) { progress ->
                setProgress(progress.toData())
                setForeground(createForegroundInfo(taskId, progress.percent))
            }) {
                is VideoAnalysisTaskResult.Completed -> Result.success(result.progress.toData())
                is VideoAnalysisTaskResult.Cancelled -> Result.failure(
                    result.progress.toData().merge(errorData("分析任务已取消")),
                )
                is VideoAnalysisTaskResult.Failed -> Result.failure(
                    result.progress.toData().merge(errorData(result.error.message ?: "分析任务失败")),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(errorData(error.message ?: "分析任务异常"))
        }
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
        const val KEY_ERROR_MESSAGE = "error_message"
        const val CHANNEL_ID = "video-analysis"
        private const val NOTIFICATION_ID = 10_001
    }
}

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
