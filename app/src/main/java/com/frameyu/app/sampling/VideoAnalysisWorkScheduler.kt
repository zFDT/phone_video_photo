package com.frameyu.app.sampling

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

/** 使用唯一工作链串行执行大视频，避免多个硬件解码任务同时运行。 */
class VideoAnalysisWorkScheduler private constructor(
    private val workManager: WorkManager,
) {
    fun enqueue(taskId: Long): UUID {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        val request = requestFor(taskId)
        workManager.enqueueUniqueWork(
            VideoAnalysisWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return request.id
    }

    fun enqueueAll(taskIds: List<Long>): List<UUID> = taskIds.map(::enqueue)

    fun cancelAll() {
        workManager.cancelUniqueWork(VideoAnalysisWorker.UNIQUE_WORK_NAME)
    }

    private fun requestFor(taskId: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<VideoAnalysisWorker>()
            .setInputData(workDataOf(VideoAnalysisWorker.KEY_TASK_ID to taskId))
            .build()

    companion object {
        fun from(context: Context): VideoAnalysisWorkScheduler = VideoAnalysisWorkScheduler(
            WorkManager.getInstance(context.applicationContext),
        )
    }
}
