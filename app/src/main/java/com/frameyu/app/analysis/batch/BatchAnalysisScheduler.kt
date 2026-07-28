package com.frameyu.app.analysis.batch

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
import kotlinx.coroutines.withContext

private const val BATCH_TAG_PREFIX = "frameyu-batch:"

/**
 * 把所有批次追加到同一个唯一串行链。
 *
 * WorkManager 会把节点取消传播给依赖它的后续 WorkRequest，因此暂停和取消只持久化控制态，
 * 由运行中 Runner 在工作项边界协作式停止，排队 Worker 启动后也会读取控制态并正常退出。
 */
class BatchAnalysisScheduler internal constructor(
    private val workManager: BatchSchedulerWorkManager,
    private val store: BatchAnalysisLifecycleStore,
) {
    suspend fun enqueue(batchId: Long): UUID {
        requireBatchId(batchId)
        val request = requestFor(batchId)
        try {
            workManager.enqueueUniqueWork(
                BatchAnalysisWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            try {
                store.markBatchNeedsAttention(batchId, ENQUEUE_FAILURE_MESSAGE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (persistenceError: Exception) {
                error.addSuppressed(persistenceError)
            }
            throw error
        }
        return request.id
    }

    suspend fun pause(batchId: Long) {
        requireBatchId(batchId)
        store.pauseBatch(batchId)
    }

    suspend fun resume(batchId: Long): UUID {
        requireBatchId(batchId)
        store.resumeBatch(batchId)
        return enqueue(batchId)
    }

    suspend fun cancel(batchId: Long) {
        requireBatchId(batchId)
        store.cancelBatch(batchId)
    }

    suspend fun retryFailures(batchId: Long): UUID {
        requireBatchId(batchId)
        store.retryFailures(batchId)
        return enqueue(batchId)
    }

    suspend fun reconcilePendingBatches(): Map<Long, UUID> {
        val activeBatchIds = workManager.activeBatchIds()
        return buildMap {
            store.reconcilableBatchIds()
                .distinct()
                .filterNot(activeBatchIds::contains)
                .forEach { batchId -> put(batchId, enqueue(batchId)) }
        }
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
        private const val ENQUEUE_FAILURE_MESSAGE = "批量任务入队失败"

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

internal interface BatchSchedulerWorkManager {
    suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    suspend fun activeBatchIds(): Set<Long>
}

private class WorkManagerBatchSchedulerWorkManager(
    private val workManager: WorkManager,
) : BatchSchedulerWorkManager {
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

    override suspend fun activeBatchIds(): Set<Long> = withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(BatchAnalysisWorker.UNIQUE_WORK_NAME)
            .get()
            .asSequence()
            .filter { workInfo -> !workInfo.state.isFinished }
            .mapNotNull(WorkInfo::batchId)
            .toSet()
    }
}

private fun WorkInfo.batchId(): Long? = tags.asSequence()
    .filter { tag -> tag.startsWith(BATCH_TAG_PREFIX) }
    .map { tag -> tag.removePrefix(BATCH_TAG_PREFIX).toLongOrNull() }
    .firstOrNull { batchId -> batchId != null && batchId > 0L }
