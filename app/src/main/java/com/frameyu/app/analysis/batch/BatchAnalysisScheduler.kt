package com.frameyu.app.analysis.batch

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

private const val BATCH_TAG_PREFIX = "frameyu-batch:"

/** 把所有批次追加到同一个唯一串行链，并只精确取消目标批次。 */
class BatchAnalysisScheduler internal constructor(
    private val workManager: BatchSchedulerWorkManager,
    private val store: BatchAnalysisLifecycleStore,
) {
    fun enqueue(batchId: Long): UUID {
        requireBatchId(batchId)
        val request = requestFor(batchId)
        workManager.enqueueUniqueWork(
            BatchAnalysisWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return request.id
    }

    suspend fun pause(batchId: Long) {
        requireBatchId(batchId)
        store.pauseBatch(batchId)
        cancelActiveWork(batchId)
    }

    suspend fun resume(batchId: Long): UUID {
        requireBatchId(batchId)
        store.resumeBatch(batchId)
        return enqueue(batchId)
    }

    suspend fun cancel(batchId: Long) {
        requireBatchId(batchId)
        store.cancelBatch(batchId)
        cancelActiveWork(batchId)
    }

    suspend fun retryFailures(batchId: Long): UUID {
        requireBatchId(batchId)
        store.retryFailures(batchId)
        return enqueue(batchId)
    }

    private suspend fun cancelActiveWork(batchId: Long) {
        workManager.getUniqueWork()
            .filter { snapshot ->
                snapshot.batchId == batchId && snapshot.state in ACTIVE_STATES
            }
            .forEach { snapshot -> workManager.cancelWorkById(snapshot.id) }
    }

    private fun requestFor(batchId: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BatchAnalysisWorker>()
            .setInputData(workDataOf(BatchAnalysisWorker.KEY_BATCH_ID to batchId))
            .addTag("$BATCH_TAG_PREFIX$batchId")
            .build()

    private fun requireBatchId(batchId: Long) {
        require(batchId > 0L) { "批次 ID 必须大于 0" }
    }

    companion object {
        private val ACTIVE_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED,
        )

        fun from(
            context: Context,
            store: BatchAnalysisLifecycleStore,
        ): BatchAnalysisScheduler = BatchAnalysisScheduler(
            WorkManagerBatchSchedulerWorkManager(
                WorkManager.getInstance(context.applicationContext),
            ),
            store,
        )
    }
}

internal data class BatchScheduledWorkSnapshot(
    val id: UUID,
    val state: WorkInfo.State,
    val batchId: Long,
)

internal interface BatchSchedulerWorkManager {
    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    suspend fun getUniqueWork(): List<BatchScheduledWorkSnapshot>

    fun cancelWorkById(id: UUID)

    fun cancelUniqueWork(uniqueWorkName: String)
}

private class WorkManagerBatchSchedulerWorkManager(
    private val workManager: WorkManager,
) : BatchSchedulerWorkManager {
    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(uniqueWorkName, existingWorkPolicy, request)
    }

    override suspend fun getUniqueWork(): List<BatchScheduledWorkSnapshot> =
        workManager.getWorkInfosForUniqueWork(BatchAnalysisWorker.UNIQUE_WORK_NAME)
            .get()
            .mapNotNull { workInfo ->
                val batchId = workInfo.tags
                    .firstOrNull { tag -> tag.startsWith(BATCH_TAG_PREFIX) }
                    ?.removePrefix(BATCH_TAG_PREFIX)
                    ?.toLongOrNull()
                    ?: return@mapNotNull null
                BatchScheduledWorkSnapshot(workInfo.id, workInfo.state, batchId)
            }

    override fun cancelWorkById(id: UUID) {
        workManager.cancelWorkById(id)
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        workManager.cancelUniqueWork(uniqueWorkName)
    }
}
