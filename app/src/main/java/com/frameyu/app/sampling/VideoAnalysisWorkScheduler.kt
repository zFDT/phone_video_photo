package com.frameyu.app.sampling

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID

private const val TASK_TAG_PREFIX = "frameyu-analysis-task:"

/** 使用唯一工作链串行执行大视频，避免多个硬件解码任务同时运行。 */
class VideoAnalysisWorkScheduler internal constructor(
    private val workManager: SchedulerWorkManager,
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

    /** 取消唯一串行链中指定分析任务，不影响其他任务。 */
    suspend fun cancel(taskId: Long) {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        workManager.getUniqueWork()
            .firstOrNull { it.taskId == taskId }
            ?.let { workManager.cancelWorkById(it.id) }
    }

    /** 查询指定分析任务的 WorkManager 状态；不存在时返回 null。 */
    suspend fun state(taskId: Long): androidx.work.WorkInfo.State? {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        return workManager.getUniqueWork()
            .firstOrNull { it.taskId == taskId }
            ?.state
    }

    /** 将指定任务重新追加到现有唯一串行链。 */
    fun resume(taskIds: List<Long>): List<UUID> = enqueueAll(taskIds)

    fun cancelAll() {
        workManager.cancelUniqueWork(VideoAnalysisWorker.UNIQUE_WORK_NAME)
    }

    private fun requestFor(taskId: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<VideoAnalysisWorker>()
            .setInputData(workDataOf(VideoAnalysisWorker.KEY_TASK_ID to taskId))
            .addTag("$TASK_TAG_PREFIX$taskId")
            .build()

    companion object {
        fun from(context: Context): VideoAnalysisWorkScheduler = VideoAnalysisWorkScheduler(
            WorkManagerSchedulerWorkManager(WorkManager.getInstance(context.applicationContext)),
        )
    }
}

internal data class ScheduledWorkSnapshot(
    val id: UUID,
    val state: androidx.work.WorkInfo.State,
    val taskId: Long,
)

internal interface SchedulerWorkManager {
    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )
    fun cancelUniqueWork(uniqueWorkName: String)
    fun cancelWorkById(id: UUID)
    suspend fun getUniqueWork(): List<ScheduledWorkSnapshot>
}

private class WorkManagerSchedulerWorkManager(
    private val workManager: WorkManager,
) : SchedulerWorkManager {
    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            existingWorkPolicy,
            request,
        )
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        workManager.cancelUniqueWork(uniqueWorkName)
    }

    override fun cancelWorkById(id: UUID) {
        workManager.cancelWorkById(id)
    }

    override suspend fun getUniqueWork(): List<ScheduledWorkSnapshot> {
        val workInfos: List<WorkInfo> = workManager
            .getWorkInfosForUniqueWork(VideoAnalysisWorker.UNIQUE_WORK_NAME)
            .get()
        return workInfos.mapNotNull { workInfo ->
            val taskId = workInfo.tags
                .firstOrNull { it.startsWith(TASK_TAG_PREFIX) }
                ?.removePrefix(TASK_TAG_PREFIX)
                ?.toLongOrNull()
                ?: -1L
            if (taskId < 0L) null else ScheduledWorkSnapshot(workInfo.id, workInfo.state, taskId)
        }
    }
}
