package com.frameyu.app.sampling

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TASK_TAG_PREFIX = "frameyu-analysis-task:"
private const val EXECUTION_TAG_PREFIX = "frameyu-analysis-execution:"

/** 使用唯一工作链串行执行大视频，避免多个硬件解码任务同时运行。 */
class VideoAnalysisWorkScheduler internal constructor(
    private val workManager: SchedulerWorkManager,
    private val lifecycleStore: VideoAnalysisLifecycleStore,
) {
    private val enqueueMutex = Mutex()

    suspend fun enqueue(taskId: Long): UUID {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        return withContext(NonCancellable) {
            enqueueMutex.withLock {
                val execution = lifecycleStore.prepareExecution(taskId)
                var activeLookupError: Exception? = null
                val activeRequestId = try {
                    activeRequestId(execution)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    activeLookupError = error
                    null
                }
                activeRequestId?.let { return@withLock it }
                val request = requestFor(execution)
                try {
                    workManager.enqueueUniqueWork(
                        VideoAnalysisWorker.UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        request,
                    )
                    request.id
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val racedRequestId = try {
                        activeRequestId(execution)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (lookupError: Exception) {
                        error.addSuppressed(lookupError)
                        null
                    }
                    if (racedRequestId != null) return@withLock racedRequestId
                    activeLookupError?.let(error::addSuppressed)
                    try {
                        lifecycleStore.markEnqueueFailed(execution, ENQUEUE_FAILURE_MESSAGE)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (persistenceError: Exception) {
                        error.addSuppressed(persistenceError)
                    }
                    throw error
                }
            }
        }
    }

    suspend fun enqueueAll(taskIds: List<Long>): List<UUID> =
        taskIds.map { taskId -> enqueue(taskId) }

    suspend fun cancel(taskId: Long) {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        lifecycleStore.cancel(taskId)
    }

    suspend fun cancelAll() {
        lifecycleStore.cancelAll()
    }

    suspend fun state(taskId: Long): WorkInfo.State? {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        return workManager.getUniqueWork()
            .filter { snapshot -> snapshot.taskId == taskId }
            .maxByOrNull(ScheduledWorkSnapshot::executionGeneration)
            ?.state
    }

    suspend fun resume(taskIds: List<Long>): List<UUID> = enqueueAll(taskIds)

    private suspend fun activeRequestId(execution: VideoAnalysisExecutionKey): UUID? =
        workManager.getUniqueWork()
            .firstOrNull { snapshot ->
                !snapshot.state.isFinished &&
                    snapshot.taskId == execution.taskId &&
                    snapshot.executionGeneration == execution.executionGeneration
            }
            ?.id

    private fun requestFor(execution: VideoAnalysisExecutionKey): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<VideoAnalysisWorker>()
            .setInputData(
                workDataOf(
                    VideoAnalysisWorker.KEY_TASK_ID to execution.taskId,
                    VideoAnalysisWorker.KEY_EXECUTION_GENERATION to
                        execution.executionGeneration,
                ),
            )
            .addTag("$TASK_TAG_PREFIX${execution.taskId}")
            .addTag(
                "$EXECUTION_TAG_PREFIX${execution.taskId}:" +
                    execution.executionGeneration,
            )
            .build()

    companion object {
        private const val ENQUEUE_FAILURE_MESSAGE = "视频分析任务入队失败"

        fun from(
            context: Context,
            lifecycleStore: VideoAnalysisLifecycleStore,
        ): VideoAnalysisWorkScheduler = VideoAnalysisWorkScheduler(
            WorkManagerSchedulerWorkManager(
                WorkManager.getInstance(context.applicationContext),
            ),
            lifecycleStore,
        )
    }
}

internal data class ScheduledWorkSnapshot(
    val id: UUID,
    val state: WorkInfo.State,
    val taskId: Long,
    val executionGeneration: Long,
)

internal interface SchedulerWorkManager {
    suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    suspend fun getUniqueWork(): List<ScheduledWorkSnapshot>
}

private class WorkManagerSchedulerWorkManager(
    private val workManager: WorkManager,
) : SchedulerWorkManager {
    override suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork(uniqueWorkName, existingWorkPolicy, request)
                .result
                .get()
        }
    }

    override suspend fun getUniqueWork(): List<ScheduledWorkSnapshot> =
        withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(VideoAnalysisWorker.UNIQUE_WORK_NAME)
                .get()
                .mapNotNull(WorkInfo::videoExecutionSnapshot)
        }
}

private fun WorkInfo.videoExecutionSnapshot(): ScheduledWorkSnapshot? {
    val execution = tags.asSequence()
        .filter { tag -> tag.startsWith(EXECUTION_TAG_PREFIX) }
        .mapNotNull { tag ->
            val values = tag.removePrefix(EXECUTION_TAG_PREFIX).split(':', limit = 2)
            val taskId = values.getOrNull(0)?.toLongOrNull()
            val generation = values.getOrNull(1)?.toLongOrNull()
            if (taskId != null && taskId >= 0L && generation != null && generation >= 0L) {
                VideoAnalysisExecutionKey(taskId, generation)
            } else {
                null
            }
        }
        .firstOrNull()
        ?: return null
    return ScheduledWorkSnapshot(id, state, execution.taskId, execution.executionGeneration)
}
