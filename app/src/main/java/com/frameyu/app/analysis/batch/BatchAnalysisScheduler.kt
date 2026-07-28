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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val BATCH_TAG_PREFIX = "frameyu-batch:"
private const val BATCH_EXECUTION_TAG_PREFIX = "frameyu-batch-execution:"

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
        val outcome = withContext(NonCancellable) {
            val execution = store.batchExecutionKey(batchId)
            val request = requestFor(execution)
            try {
                workManager.enqueueUniqueWork(
                    BatchAnalysisWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                )
                EnqueueOutcome.Success(request.id)
            } catch (cancelled: CancellationException) {
                EnqueueOutcome.Failure(cancelled)
            } catch (error: Exception) {
                try {
                    store.markBatchNeedsAttention(
                        batchId = batchId,
                        executionGeneration = execution.executionGeneration,
                        message = ENQUEUE_FAILURE_MESSAGE,
                    )
                } catch (cancelled: CancellationException) {
                    return@withContext EnqueueOutcome.Failure(cancelled)
                } catch (persistenceError: Exception) {
                    error.addSuppressed(persistenceError)
                }
                EnqueueOutcome.Failure(error)
            }
        }
        return when (outcome) {
            is EnqueueOutcome.Success -> outcome.requestId
            is EnqueueOutcome.Failure -> throw outcome.error
        }
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

    suspend fun reconcilePendingBatches(): BatchReconciliationResult {
        val activeExecutions = workManager.activeExecutions()
        val enqueued = linkedMapOf<Long, UUID>()
        val failures = linkedMapOf<Long, Exception>()
        store.reconcilableExecutions()
            .distinct()
            .filterNot(activeExecutions::contains)
            .forEach { execution ->
                try {
                    enqueued[execution.batchId] = enqueue(execution.batchId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures[execution.batchId] = error
                }
            }
        return BatchReconciliationResult(
            enqueued = enqueued,
            failures = failures,
        )
    }

    data class BatchReconciliationResult(
        val enqueued: Map<Long, UUID>,
        val failures: Map<Long, Exception>,
    )

    private fun requestFor(execution: BatchExecutionKey): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BatchAnalysisWorker>()
            .setInputData(
                workDataOf(
                    BatchAnalysisWorker.KEY_BATCH_ID to execution.batchId,
                    BatchAnalysisWorker.KEY_EXECUTION_GENERATION to
                        execution.executionGeneration,
                ),
            )
            .addTag("$BATCH_TAG_PREFIX${execution.batchId}")
            .addTag(
                "$BATCH_EXECUTION_TAG_PREFIX${execution.batchId}:" +
                    execution.executionGeneration,
            )
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

private sealed interface EnqueueOutcome {
    data class Success(val requestId: UUID) : EnqueueOutcome

    data class Failure(val error: Exception) : EnqueueOutcome
}

internal interface BatchSchedulerWorkManager {
    suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    suspend fun activeExecutions(): Set<BatchExecutionKey>
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

    override suspend fun activeExecutions(): Set<BatchExecutionKey> = withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(BatchAnalysisWorker.UNIQUE_WORK_NAME)
            .get()
            .asSequence()
            .filter { workInfo -> !workInfo.state.isFinished }
            .mapNotNull(WorkInfo::batchExecution)
            .toSet()
    }
}

private fun WorkInfo.batchExecution(): BatchExecutionKey? = tags.asSequence()
    .filter { tag -> tag.startsWith(BATCH_EXECUTION_TAG_PREFIX) }
    .mapNotNull { tag ->
        val values = tag.removePrefix(BATCH_EXECUTION_TAG_PREFIX).split(':', limit = 2)
        val batchId = values.getOrNull(0)?.toLongOrNull()
        val generation = values.getOrNull(1)?.toLongOrNull()
        if (batchId != null && batchId > 0L && generation != null && generation >= 0L) {
            BatchExecutionKey(batchId, generation)
        } else {
            null
        }
    }
    .firstOrNull()
