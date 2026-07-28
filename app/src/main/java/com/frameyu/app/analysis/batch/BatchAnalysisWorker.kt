package com.frameyu.app.analysis.batch

import android.content.Context
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.frameyu.app.FrameYuApplication
import com.frameyu.app.analysis.ImageAnalysisCoordinator
import com.frameyu.app.data.db.AnalysisWorkItemEntity
import com.frameyu.app.data.db.BatchAnalysisDao
import java.io.IOException
import kotlinx.coroutines.CancellationException

enum class BatchAnalysisStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    CANCELLED,
    COMPLETED,
    NEEDS_ATTENTION,
    ;

    companion object {
        fun fromPersisted(rawStatus: String): BatchAnalysisStatus = try {
            valueOf(rawStatus)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("未知批次状态：$rawStatus", error)
        }
    }
}

enum class BatchFinalStatus {
    COMPLETED,
    NEEDS_ATTENTION,
}

data class BatchAnalysisSnapshot(
    val total: Int,
    val completed: Int,
    val retryableFailed: Int,
    val permanentFailed: Int,
    val pending: Int,
    val running: Int,
    val paused: Int,
    val cancelled: Int,
) {
    val failed: Int
        get() = retryableFailed + permanentFailed
}

data class BatchAnalysisProgress(
    val completed: Int,
    val failed: Int,
    val total: Int,
    val currentStage: String,
    val currentMediaId: Long,
)

data class BatchAnalysisResult(
    val completed: Int,
    val failed: Int,
    val total: Int,
    val retryableFailed: Int,
    val permanentFailed: Int,
    val paused: Boolean,
    val cancelled: Boolean,
    val currentStage: String,
    val currentMediaId: Long,
)

interface BatchAnalysisLifecycleStore {
    suspend fun pauseBatch(batchId: Long)

    suspend fun resumeBatch(batchId: Long)

    suspend fun cancelBatch(batchId: Long)

    suspend fun retryFailures(batchId: Long)

    suspend fun markBatchNeedsAttention(batchId: Long, message: String)

    suspend fun reconcilableBatchIds(): List<Long>
}

interface BatchAnalysisStore : BatchAnalysisLifecycleStore {
    suspend fun recoverInterrupted(batchId: Long)

    suspend fun batchStatus(batchId: Long): BatchAnalysisStatus

    suspend fun markBatchRunning(batchId: Long): Boolean

    suspend fun nextRunnableItems(
        batchId: Long,
        afterId: Long,
        limit: Int,
    ): List<AnalysisWorkItemEntity>

    suspend fun markRunning(itemId: Long): Boolean

    suspend fun completeItem(itemId: Long): Boolean

    suspend fun failItem(itemId: Long, retryable: Boolean, message: String): Boolean

    suspend fun failActiveExecution(batchId: Long, message: String): Boolean

    suspend fun snapshot(batchId: Long): BatchAnalysisSnapshot

    suspend fun finishBatch(batchId: Long, status: BatchFinalStatus): Boolean
}

fun interface BatchWorkItemCoordinator {
    suspend fun analyze(item: AnalysisWorkItemEntity)
}

/** Room 与单张图片协调器的生产适配边界。 */
class RoomBatchAnalysisStore(
    private val dao: BatchAnalysisDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : BatchAnalysisStore {
    override suspend fun recoverInterrupted(batchId: Long) {
        dao.recoverInterruptedItems(batchId, INTERRUPTED_MESSAGE, nowMillis())
    }

    override suspend fun batchStatus(batchId: Long): BatchAnalysisStatus =
        BatchAnalysisStatus.fromPersisted(dao.batchStatus(batchId))

    override suspend fun markBatchRunning(batchId: Long): Boolean =
        dao.markBatchRunning(batchId, nowMillis())

    override suspend fun nextRunnableItems(
        batchId: Long,
        afterId: Long,
        limit: Int,
    ): List<AnalysisWorkItemEntity> = dao.nextRunnableItems(batchId, afterId, limit)

    override suspend fun markRunning(itemId: Long): Boolean =
        dao.markRunning(itemId, nowMillis())

    override suspend fun completeItem(itemId: Long): Boolean =
        dao.completeItem(itemId, nowMillis())

    override suspend fun failItem(itemId: Long, retryable: Boolean, message: String): Boolean =
        dao.failItem(itemId, retryable, message, nowMillis())

    override suspend fun failActiveExecution(batchId: Long, message: String): Boolean =
        dao.failActiveExecution(batchId, message, nowMillis())

    override suspend fun snapshot(batchId: Long): BatchAnalysisSnapshot {
        val counts = dao.batchCounts(batchId)
        return BatchAnalysisSnapshot(
            total = counts.total,
            completed = counts.completed,
            retryableFailed = counts.retryableFailed,
            permanentFailed = counts.permanentFailed,
            pending = counts.pending,
            running = counts.running,
            paused = counts.paused,
            cancelled = counts.cancelled,
        )
    }

    override suspend fun finishBatch(batchId: Long, status: BatchFinalStatus): Boolean =
        dao.finishBatch(batchId, status.name, nowMillis())

    override suspend fun pauseBatch(batchId: Long) {
        dao.pauseBatch(batchId, nowMillis())
    }

    override suspend fun resumeBatch(batchId: Long) {
        dao.resumeBatch(batchId, INTERRUPTED_MESSAGE, nowMillis())
    }

    override suspend fun cancelBatch(batchId: Long) {
        dao.cancelBatch(batchId, nowMillis())
    }

    override suspend fun retryFailures(batchId: Long) {
        dao.retryFailures(batchId, nowMillis())
    }

    override suspend fun markBatchNeedsAttention(batchId: Long, message: String) {
        dao.markBatchNeedsAttention(batchId, message, nowMillis())
    }

    override suspend fun reconcilableBatchIds(): List<Long> = dao.reconcilableBatchIds()

    private companion object {
        const val INTERRUPTED_MESSAGE = "批量分析进程中断"
    }
}

class ImageBatchWorkItemCoordinator(
    private val analyzeItem: suspend (AnalysisWorkItemEntity) -> Unit,
) : BatchWorkItemCoordinator {
    constructor(coordinator: ImageAnalysisCoordinator) : this(coordinator::analyze)

    override suspend fun analyze(item: AnalysisWorkItemEntity) {
        try {
            analyzeItem(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error.hasDatabaseCause()) {
                throw BatchAnalysisInfrastructureException(
                    "图片分析基础设施失败，工作项 id=${item.id}",
                    error,
                )
            }
            throw error
        }
    }
}

/** 不依赖 Room、Bitmap 或 WorkManager 的可恢复批次执行核心。 */
class BatchAnalysisRunner(
    private val store: BatchAnalysisStore,
    private val coordinator: BatchWorkItemCoordinator,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize > 0) { "分页大小必须大于 0" }
    }

    suspend fun run(
        batchId: Long,
        onProgress: suspend (BatchAnalysisProgress) -> Unit = {},
    ): BatchAnalysisResult {
        require(batchId > 0L) { "批次 ID 必须大于 0" }
        when (store.batchStatus(batchId)) {
            BatchAnalysisStatus.QUEUED,
            BatchAnalysisStatus.RUNNING,
            -> Unit
            BatchAnalysisStatus.PAUSED -> return controlledResult(batchId, paused = true)
            BatchAnalysisStatus.CANCELLED -> return controlledResult(batchId, cancelled = true)
            BatchAnalysisStatus.COMPLETED,
            BatchAnalysisStatus.NEEDS_ATTENTION,
            -> return controlledResult(batchId)
        }

        store.recoverInterrupted(batchId)
        if (!store.markBatchRunning(batchId)) {
            return resultAfterRejectedTransition(batchId)
        }

        var afterId = 0L
        var currentStage = ""
        var currentMediaId = -1L
        while (true) {
            val page = store.nextRunnableItems(batchId, afterId, pageSize)
            if (page.isEmpty()) break

            for (item in page) {
                controlledResultForNonRunningState(
                    batchId,
                    currentStage,
                    currentMediaId,
                )?.let { return it }

                currentStage = item.stage
                currentMediaId = item.mediaAssetId
                onProgress(store.snapshot(batchId).toProgress(item))
                if (!store.markRunning(item.id)) {
                    return resultAfterRejectedTransition(
                        batchId,
                        currentStage = currentStage,
                        currentMediaId = currentMediaId,
                    )
                }

                try {
                    coordinator.analyze(item)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (infrastructure: BatchAnalysisInfrastructureException) {
                    throw infrastructure
                } catch (error: Exception) {
                    val retryable = error.hasIOExceptionCause()
                    if (!store.failItem(item.id, retryable, error.failureMessage())) {
                        return resultAfterRejectedTransition(
                            batchId,
                            currentStage = currentStage,
                            currentMediaId = currentMediaId,
                        )
                    }
                    onProgress(store.snapshot(batchId).toProgress(item))
                    continue
                }

                controlledResultForNonRunningState(
                    batchId,
                    currentStage,
                    currentMediaId,
                )?.let { return it }

                if (!store.completeItem(item.id)) {
                    return resultAfterRejectedTransition(
                        batchId,
                        currentStage = currentStage,
                        currentMediaId = currentMediaId,
                    )
                }
                onProgress(store.snapshot(batchId).toProgress(item))
            }
            afterId = page.last().id
        }

        controlledResultForNonRunningState(
            batchId,
            currentStage,
            currentMediaId,
        )?.let { return it }

        val snapshot = store.snapshot(batchId)
        val finalStatus = if (snapshot.failed > 0) {
            BatchFinalStatus.NEEDS_ATTENTION
        } else {
            BatchFinalStatus.COMPLETED
        }
        if (!store.finishBatch(batchId, finalStatus)) {
            return resultAfterRejectedTransition(
                batchId,
                currentStage = currentStage,
                currentMediaId = currentMediaId,
            )
        }
        return snapshot.toResult(
            currentStage = currentStage,
            currentMediaId = currentMediaId,
        )
    }

    internal suspend fun failActiveExecution(
        batchId: Long,
        message: String,
    ): BatchAnalysisResult {
        store.failActiveExecution(batchId, message)
        return store.snapshot(batchId).toResult(
            currentStage = "",
            currentMediaId = -1L,
        )
    }

    private suspend fun controlledResult(
        batchId: Long,
        paused: Boolean = false,
        cancelled: Boolean = false,
        currentStage: String = "",
        currentMediaId: Long = -1L,
    ): BatchAnalysisResult = store.snapshot(batchId).toResult(
        paused = paused,
        cancelled = cancelled,
        currentStage = currentStage,
        currentMediaId = currentMediaId,
    )

    /**
     * 条件转换未命中后，只有 QUEUED 才表示旧执行器已被新调度取代。
     * 数据库异常仍由 Store 原样抛出。
     */
    private suspend fun resultAfterRejectedTransition(
        batchId: Long,
        currentStage: String = "",
        currentMediaId: Long = -1L,
    ): BatchAnalysisResult = when (store.batchStatus(batchId)) {
        BatchAnalysisStatus.PAUSED -> controlledResult(
            batchId,
            paused = true,
            currentStage = currentStage,
            currentMediaId = currentMediaId,
        )
        BatchAnalysisStatus.CANCELLED -> controlledResult(
            batchId,
            cancelled = true,
            currentStage = currentStage,
            currentMediaId = currentMediaId,
        )
        BatchAnalysisStatus.QUEUED,
        BatchAnalysisStatus.COMPLETED,
        BatchAnalysisStatus.NEEDS_ATTENTION,
        -> controlledResult(
            batchId,
            currentStage = currentStage,
            currentMediaId = currentMediaId,
        )
        BatchAnalysisStatus.RUNNING -> error(
            "批次 id=$batchId 仍为 RUNNING，但受保护状态转换未命中",
        )
    }

    private suspend fun controlledResultForNonRunningState(
        batchId: Long,
        currentStage: String,
        currentMediaId: Long,
    ): BatchAnalysisResult? = when (store.batchStatus(batchId)) {
        BatchAnalysisStatus.RUNNING -> null
        BatchAnalysisStatus.PAUSED -> controlledResult(
            batchId,
            paused = true,
            currentStage = currentStage,
            currentMediaId = currentMediaId,
        )
        BatchAnalysisStatus.CANCELLED -> controlledResult(
            batchId,
            cancelled = true,
            currentStage = currentStage,
            currentMediaId = currentMediaId,
        )
        BatchAnalysisStatus.QUEUED,
        BatchAnalysisStatus.COMPLETED,
        BatchAnalysisStatus.NEEDS_ATTENTION,
        -> controlledResult(
            batchId,
            currentStage = currentStage,
            currentMediaId = currentMediaId,
        )
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 32
    }
}

/** WorkManager 前台任务，只管理批次生命周期、进度和结果映射。 */
class BatchAnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val runner: BatchAnalysisRunner,
) : CoroutineWorker(appContext, workerParams) {
    constructor(appContext: Context, workerParams: WorkerParameters) : this(
        appContext,
        workerParams,
        (appContext.applicationContext as? FrameYuApplication)?.batchAnalysisRunner
            ?: error("未配置批量分析执行器"),
    )

    override suspend fun doWork(): Result {
        val batchId = inputData.getLong(KEY_BATCH_ID, -1L)
        if (batchId <= 0L) return Result.failure(errorData("缺少有效的批次 ID"))

        setForeground(createForegroundInfo(batchId, BatchAnalysisProgress(0, 0, 0, "", -1L)))
        val execution = BatchAnalysisExecution(
            runBatch = runner::run,
            failActiveExecution = runner::failActiveExecution,
            logger = BatchExecutionLogger { failedBatchId, attempt, error, persistenceError ->
                Log.e(
                    TAG,
                    "批量执行失败 batchId=$failedBatchId runAttemptCount=$attempt",
                    error,
                )
                if (persistenceError != null) {
                    Log.e(
                        TAG,
                        "批量失败状态持久化失败 batchId=$failedBatchId runAttemptCount=$attempt",
                        persistenceError,
                    )
                }
            },
        )
        return when (
            val outcome = execution.execute(batchId, runAttemptCount) { progress ->
                setProgress(progress.toData())
                setForeground(createForegroundInfo(batchId, progress))
            }
        ) {
            BatchExecutionOutcome.Retry -> Result.retry()
            is BatchExecutionOutcome.Success -> {
                val output = outcome.result?.toData()
                    ?: errorData(outcome.errorMessage ?: "批量分析执行失败")
                Result.success(output)
            }
        }
    }

    private fun createForegroundInfo(
        batchId: Long,
        progress: BatchAnalysisProgress,
    ): ForegroundInfo {
        val processed = progress.completed + progress.failed
        val stage = progress.currentStage.ifBlank { "准备中" }
        val media = if (progress.currentMediaId > 0L) {
            "，媒体 ${progress.currentMediaId}"
        } else {
            ""
        }
        return ForegroundInfo(
            notificationId(batchId),
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("帧遇正在批量分析")
                .setContentText("$stage$media：$processed/${progress.total}")
                .setProgress(
                    progress.total.coerceAtLeast(0),
                    processed.coerceAtLeast(0),
                    progress.total <= 0,
                )
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun errorData(message: String): Data = Data.Builder()
        .putString(KEY_ERROR_MESSAGE, message)
        .build()

    companion object {
        const val UNIQUE_WORK_NAME = "frameyu-batch-analysis"
        const val KEY_BATCH_ID = "batch_id"
        const val KEY_COMPLETED = "completed"
        const val KEY_FAILED = "failed"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT_STAGE = "current_stage"
        const val KEY_CURRENT_MEDIA_ID = "current_media_id"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val CHANNEL_ID = "batch-analysis"
        private const val TAG = "BatchAnalysisWorker"
        private const val NOTIFICATION_ID_BASE = 20_000

        internal fun notificationId(batchId: Long): Int {
            val folded = (batchId xor (batchId ushr Integer.SIZE)).toInt()
            return NOTIFICATION_ID_BASE + (folded and 0x3FFF_FFFF)
        }
    }
}

class BatchAnalysisWorkerFactory(
    private val runnerProvider: () -> BatchAnalysisRunner,
) : WorkerFactory() {
    constructor(runner: BatchAnalysisRunner) : this({ runner })

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = if (workerClassName == BatchAnalysisWorker::class.java.name) {
        BatchAnalysisWorker(appContext, workerParameters, runnerProvider())
    } else {
        null
    }
}

private fun BatchAnalysisSnapshot.toProgress(
    item: AnalysisWorkItemEntity,
): BatchAnalysisProgress = BatchAnalysisProgress(
    completed = completed,
    failed = failed,
    total = total,
    currentStage = item.stage,
    currentMediaId = item.mediaAssetId,
)

private fun BatchAnalysisSnapshot.toResult(
    paused: Boolean = false,
    cancelled: Boolean = false,
    currentStage: String,
    currentMediaId: Long,
): BatchAnalysisResult = BatchAnalysisResult(
    completed = completed,
    failed = failed,
    total = total,
    retryableFailed = retryableFailed,
    permanentFailed = permanentFailed,
    paused = paused,
    cancelled = cancelled,
    currentStage = currentStage,
    currentMediaId = currentMediaId,
)

private fun BatchAnalysisProgress.toData(): Data = Data.Builder()
    .putInt(BatchAnalysisWorker.KEY_COMPLETED, completed)
    .putInt(BatchAnalysisWorker.KEY_FAILED, failed)
    .putInt(BatchAnalysisWorker.KEY_TOTAL, total)
    .putString(BatchAnalysisWorker.KEY_CURRENT_STAGE, currentStage)
    .putLong(BatchAnalysisWorker.KEY_CURRENT_MEDIA_ID, currentMediaId)
    .build()

private fun BatchAnalysisResult.toData(): Data = Data.Builder()
    .putInt(BatchAnalysisWorker.KEY_COMPLETED, completed)
    .putInt(BatchAnalysisWorker.KEY_FAILED, failed)
    .putInt(BatchAnalysisWorker.KEY_TOTAL, total)
    .putString(BatchAnalysisWorker.KEY_CURRENT_STAGE, currentStage)
    .putLong(BatchAnalysisWorker.KEY_CURRENT_MEDIA_ID, currentMediaId)
    .putBoolean("paused", paused)
    .putBoolean("cancelled", cancelled)
    .build()

private fun Exception.failureMessage(): String = message ?: "批量分析失败"

private fun Throwable.hasDatabaseCause(): Boolean =
    generateSequence(this) { current -> current.cause }
        .any { current -> current is SQLException }
