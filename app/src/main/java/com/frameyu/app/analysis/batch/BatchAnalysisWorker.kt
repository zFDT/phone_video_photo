package com.frameyu.app.analysis.batch

import android.content.Context
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.os.SystemClock
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

data class BatchAnalysisControlState(
    val status: BatchAnalysisStatus,
    val executionGeneration: Long,
)

data class BatchExecutionKey(
    val batchId: Long,
    val executionGeneration: Long,
) {
    init {
        require(batchId > 0L) { "批次 ID 必须大于 0" }
        require(executionGeneration >= 0L) { "执行代际不能为负数" }
    }
}

private fun BatchAnalysisControlState.key(batchId: Long): BatchExecutionKey =
    BatchExecutionKey(
        batchId = batchId,
        executionGeneration = executionGeneration,
    )

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
    suspend fun batchExecutionKey(batchId: Long): BatchExecutionKey

    suspend fun pauseBatch(batchId: Long)

    suspend fun resumeBatch(batchId: Long)

    suspend fun cancelBatch(batchId: Long)

    suspend fun retryFailures(batchId: Long)

    suspend fun markBatchNeedsAttention(
        batchId: Long,
        executionGeneration: Long,
        message: String,
    ): Boolean

    suspend fun reconcilableExecutions(): List<BatchExecutionKey>
}

interface BatchAnalysisStore : BatchAnalysisLifecycleStore {
    suspend fun recoverInterrupted(batchId: Long)

    suspend fun batchStatus(batchId: Long): BatchAnalysisStatus

    suspend fun batchControlState(batchId: Long): BatchAnalysisControlState

    suspend fun markBatchRunning(batchId: Long, executionGeneration: Long): Boolean

    suspend fun nextRunnableItems(
        batchId: Long,
        afterId: Long,
        limit: Int,
    ): List<AnalysisWorkItemEntity>

    suspend fun markRunning(itemId: Long): Boolean

    suspend fun dependenciesCompleted(item: AnalysisWorkItemEntity): Boolean

    suspend fun completeItem(itemId: Long): Boolean

    suspend fun failItem(itemId: Long, retryable: Boolean, message: String): Boolean

    suspend fun failActiveExecution(
        batchId: Long,
        executionGeneration: Long,
        message: String,
    ): Boolean

    suspend fun snapshot(batchId: Long): BatchAnalysisSnapshot

    suspend fun finishBatch(
        batchId: Long,
        executionGeneration: Long,
        status: BatchFinalStatus,
    ): Boolean
}

fun interface BatchWorkItemCoordinator {
    suspend fun analyze(item: AnalysisWorkItemEntity)
}

fun interface BatchWorkPacer {
    suspend fun awaitAfter(stage: String)
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

    override suspend fun batchControlState(batchId: Long): BatchAnalysisControlState {
        val control = dao.batchControlState(batchId)
        return BatchAnalysisControlState(
            status = BatchAnalysisStatus.fromPersisted(control.status),
            executionGeneration = control.executionGeneration,
        )
    }

    override suspend fun batchExecutionKey(batchId: Long): BatchExecutionKey =
        batchControlState(batchId).key(batchId)

    override suspend fun markBatchRunning(
        batchId: Long,
        executionGeneration: Long,
    ): Boolean = dao.markBatchRunning(batchId, executionGeneration, nowMillis())

    override suspend fun nextRunnableItems(
        batchId: Long,
        afterId: Long,
        limit: Int,
    ): List<AnalysisWorkItemEntity> = dao.nextRunnableItems(batchId, afterId, limit)

    override suspend fun markRunning(itemId: Long): Boolean =
        dao.markRunning(itemId, nowMillis())

    override suspend fun dependenciesCompleted(item: AnalysisWorkItemEntity): Boolean =
        item.stage != AnalysisStage.CONDITIONAL_DETECTION.name ||
            dao.classificationCompleted(item.batchId, item.mediaAssetId)

    override suspend fun completeItem(itemId: Long): Boolean =
        dao.completeItem(itemId, nowMillis())

    override suspend fun failItem(itemId: Long, retryable: Boolean, message: String): Boolean =
        dao.failItem(itemId, retryable, message, nowMillis())

    override suspend fun failActiveExecution(
        batchId: Long,
        executionGeneration: Long,
        message: String,
    ): Boolean = dao.failActiveExecution(batchId, executionGeneration, message, nowMillis())

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

    override suspend fun finishBatch(
        batchId: Long,
        executionGeneration: Long,
        status: BatchFinalStatus,
    ): Boolean = dao.finishBatch(batchId, executionGeneration, status.name, nowMillis())

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

    override suspend fun markBatchNeedsAttention(
        batchId: Long,
        executionGeneration: Long,
        message: String,
    ): Boolean = dao.markBatchNeedsAttention(
        batchId,
        executionGeneration,
        message,
        nowMillis(),
    )

    override suspend fun reconcilableExecutions(): List<BatchExecutionKey> =
        dao.reconcilableExecutions().map { execution ->
            BatchExecutionKey(
                batchId = execution.batchId,
                executionGeneration = execution.executionGeneration,
            )
        }

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
    private val coordinatorProvider: () -> BatchWorkItemCoordinator,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val pacer: BatchWorkPacer = BatchWorkPacer {},
) {
    constructor(
        store: BatchAnalysisStore,
        coordinator: BatchWorkItemCoordinator,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        pacer: BatchWorkPacer = BatchWorkPacer {},
    ) : this(store, { coordinator }, pageSize, pacer)

    init {
        require(pageSize > 0) { "分页大小必须大于 0" }
    }

    suspend fun run(
        batchId: Long,
        onProgress: suspend (BatchAnalysisProgress) -> Unit = {},
    ): BatchAnalysisResult {
        val execution = store.batchExecutionKey(batchId)
        return run(
            batchId = batchId,
            executionGeneration = execution.executionGeneration,
            onProgress = onProgress,
            onClaimed = {},
        )
    }

    internal suspend fun run(
        batchId: Long,
        executionGeneration: Long,
        onProgress: suspend (BatchAnalysisProgress) -> Unit,
        onClaimed: suspend () -> Unit,
    ): BatchAnalysisResult {
        require(batchId > 0L) { "批次 ID 必须大于 0" }
        require(executionGeneration >= 0L) { "执行代际不能为负数" }
        val initialControl = store.batchControlState(batchId)
        if (initialControl.executionGeneration != executionGeneration) {
            return controlledResult(
                batchId,
                paused = initialControl.status == BatchAnalysisStatus.PAUSED,
                cancelled = initialControl.status == BatchAnalysisStatus.CANCELLED,
            )
        }
        when (initialControl.status) {
            BatchAnalysisStatus.QUEUED,
            BatchAnalysisStatus.RUNNING,
            -> Unit
            BatchAnalysisStatus.PAUSED -> return controlledResult(batchId, paused = true)
            BatchAnalysisStatus.CANCELLED -> return controlledResult(batchId, cancelled = true)
            BatchAnalysisStatus.COMPLETED,
            BatchAnalysisStatus.NEEDS_ATTENTION,
            -> return controlledResult(batchId)
        }

        if (!store.markBatchRunning(batchId, executionGeneration)) {
            return resultAfterRejectedTransition(batchId, executionGeneration)
        }
        store.recoverInterrupted(batchId)
        onClaimed()

        var localSnapshot = store.snapshot(batchId)
        var coordinator: BatchWorkItemCoordinator? = null
        var afterId = 0L
        var currentStage = ""
        var currentMediaId = -1L
        while (true) {
            val page = store.nextRunnableItems(batchId, afterId, pageSize)
            if (page.isEmpty()) break

            for (item in page) {
                controlledResultForNonRunningState(
                    batchId,
                    executionGeneration,
                    currentStage,
                    currentMediaId,
                )?.let { return it }

                currentStage = item.stage
                currentMediaId = item.mediaAssetId
                onProgress(localSnapshot.toProgress(item))
                if (!store.markRunning(item.id)) {
                    return resultAfterRejectedTransition(
                        batchId,
                        executionGeneration,
                        currentStage = currentStage,
                        currentMediaId = currentMediaId,
                    )
                }
                localSnapshot = localSnapshot.afterMarkRunning(item)
                if (!store.dependenciesCompleted(item)) {
                    if (!store.failItem(item.id, retryable = true, DEPENDENCY_FAILURE_MESSAGE)) {
                        return resultAfterRejectedTransition(
                            batchId,
                            executionGeneration,
                            currentStage = currentStage,
                            currentMediaId = currentMediaId,
                        )
                    }
                    localSnapshot = localSnapshot.afterFailure(retryable = true)
                    onProgress(localSnapshot.toProgress(item))
                    continue
                }
                val itemCoordinator = coordinator
                    ?: coordinatorProvider().also { coordinator = it }

                try {
                    itemCoordinator.analyze(item)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (infrastructure: BatchAnalysisInfrastructureException) {
                    throw infrastructure
                } catch (error: Exception) {
                    val retryable = error.hasIOExceptionCause()
                    if (!store.failItem(item.id, retryable, error.failureMessage())) {
                        return resultAfterRejectedTransition(
                            batchId,
                            executionGeneration,
                            currentStage = currentStage,
                            currentMediaId = currentMediaId,
                        )
                    }
                    localSnapshot = localSnapshot.afterFailure(retryable)
                    onProgress(localSnapshot.toProgress(item))
                    paceAfterIfRunning(
                        batchId,
                        executionGeneration,
                        currentStage,
                        currentMediaId,
                    )?.let { return it }
                    continue
                }

                controlledResultForNonRunningState(
                    batchId,
                    executionGeneration,
                    currentStage,
                    currentMediaId,
                )?.let { return it }

                if (!store.completeItem(item.id)) {
                    return resultAfterRejectedTransition(
                        batchId,
                        executionGeneration,
                        currentStage = currentStage,
                        currentMediaId = currentMediaId,
                    )
                }
                localSnapshot = localSnapshot.afterCompletion()
                onProgress(localSnapshot.toProgress(item))
                paceAfterIfRunning(
                    batchId,
                    executionGeneration,
                    currentStage,
                    currentMediaId,
                )?.let { return it }
            }
            afterId = page.last().id
        }

        controlledResultForNonRunningState(
            batchId,
            executionGeneration,
            currentStage,
            currentMediaId,
        )?.let { return it }

        val snapshot = store.snapshot(batchId)
        val finalStatus = if (snapshot.failed > 0) {
            BatchFinalStatus.NEEDS_ATTENTION
        } else {
            BatchFinalStatus.COMPLETED
        }
        if (!store.finishBatch(batchId, executionGeneration, finalStatus)) {
            return resultAfterRejectedTransition(
                batchId,
                executionGeneration,
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
        executionGeneration: Long,
        message: String,
    ): BatchAnalysisResult {
        if (!store.failActiveExecution(batchId, executionGeneration, message)) {
            return resultAfterRejectedTransition(batchId, executionGeneration)
        }
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
     * generation 变化表示旧 WorkRequest 已失去所有权；同代转换未命中再按状态机处理。
     * 数据库异常仍由 Store 原样抛出。
     */
    private suspend fun resultAfterRejectedTransition(
        batchId: Long,
        executionGeneration: Long,
        currentStage: String = "",
        currentMediaId: Long = -1L,
    ): BatchAnalysisResult {
        val control = store.batchControlState(batchId)
        if (control.executionGeneration != executionGeneration) {
            return controlledResult(
                batchId,
                paused = control.status == BatchAnalysisStatus.PAUSED,
                cancelled = control.status == BatchAnalysisStatus.CANCELLED,
                currentStage = currentStage,
                currentMediaId = currentMediaId,
            )
        }
        return when (control.status) {
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
                "批次 id=$batchId generation=$executionGeneration 仍为 RUNNING，" +
                    "但受保护状态转换未命中",
            )
        }
    }

    private suspend fun controlledResultForNonRunningState(
        batchId: Long,
        executionGeneration: Long,
        currentStage: String,
        currentMediaId: Long,
    ): BatchAnalysisResult? {
        val control = store.batchControlState(batchId)
        if (control.executionGeneration != executionGeneration) {
            return controlledResult(
                batchId,
                paused = control.status == BatchAnalysisStatus.PAUSED,
                cancelled = control.status == BatchAnalysisStatus.CANCELLED,
                currentStage = currentStage,
                currentMediaId = currentMediaId,
            )
        }
        return when (control.status) {
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
    }

    private suspend fun paceAfterIfRunning(
        batchId: Long,
        executionGeneration: Long,
        currentStage: String,
        currentMediaId: Long,
    ): BatchAnalysisResult? {
        if (currentStage !in PACED_STAGES) return null
        controlledResultForNonRunningState(
            batchId,
            executionGeneration,
            currentStage,
            currentMediaId,
        )?.let { return it }
        pacer.awaitAfter(currentStage)
        return null
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 32
        const val DEPENDENCY_FAILURE_MESSAGE = "条件检测的当前批次分类依赖未完成"
        val PACED_STAGES = setOf(
            AnalysisStage.CLASSIFICATION.name,
            AnalysisStage.EMBEDDING.name,
            AnalysisStage.CONDITIONAL_DETECTION.name,
        )
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
        if (batchId <= 0L) return Result.failure(workerErrorData("缺少有效的批次 ID"))
        // v4 升级前已经持久化的 WorkRequest 没有该键，对应迁移后的 generation 0。
        val executionGeneration = inputData.getLong(KEY_EXECUTION_GENERATION, 0L)
        if (executionGeneration < 0L) {
            return Result.failure(workerErrorData("缺少有效的执行代际"))
        }

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
        val progressEmissionPolicy = BatchProgressEmissionPolicy(
            minIntervalMillis = PROGRESS_EMISSION_INTERVAL_MILLIS,
            nowMillis = SystemClock::elapsedRealtime,
        )
        val etaEstimator = BatchEtaEstimator()
        val notificationCoordinator = BatchNotificationEmissionCoordinator(
            shouldEmit = progressEmissionPolicy::shouldEmit,
            visibilityProvider = {
                (applicationContext as FrameYuApplication).appVisibilityTracker.visibility
            },
            nowMillis = SystemClock::elapsedRealtime,
            recordEta = etaEstimator::record,
        )
        return when (
            val outcome = execution.execute(
                batchId = batchId,
                executionGeneration = executionGeneration,
                runAttemptCount = runAttemptCount,
                onClaimed = {
                    val progress = BatchAnalysisProgress(0, 0, 0, "", -1L)
                    setForeground(
                        createForegroundInfo(
                            batchId,
                            progress,
                            notificationCoordinator.initialNotification(progress),
                        ),
                    )
                },
                onProgress = { progress ->
                    notificationCoordinator.notificationFor(progress)?.let { text ->
                        setProgress(progress.toData())
                        setForeground(
                            createForegroundInfo(
                                batchId = batchId,
                                progress = progress,
                                text = text,
                            ),
                        )
                    }
                },
            )
        ) {
            BatchExecutionOutcome.Retry -> Result.retry()
            is BatchExecutionOutcome.Success -> Result.success(outcome.toWorkerOutputData())
        }
    }

    private fun createForegroundInfo(
        batchId: Long,
        progress: BatchAnalysisProgress,
        text: BatchNotificationText,
    ): ForegroundInfo {
        val processed = progress.processed
        return ForegroundInfo(
            notificationId(batchId),
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(text.title)
                .setContentText(text.content)
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

    companion object {
        private const val PROGRESS_EMISSION_INTERVAL_MILLIS = 1_000L
        const val UNIQUE_WORK_NAME = "frameyu-batch-analysis"
        const val KEY_BATCH_ID = "batch_id"
        const val KEY_EXECUTION_GENERATION = "execution_generation"
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

private fun BatchAnalysisSnapshot.afterMarkRunning(
    item: AnalysisWorkItemEntity,
): BatchAnalysisSnapshot = when (WorkItemStatus.valueOf(item.status)) {
    WorkItemStatus.PENDING -> {
        check(pending > 0) { "PENDING 工作项计数不能小于零" }
        copy(pending = pending - 1, running = running + 1)
    }
    WorkItemStatus.RETRYABLE_FAILED -> {
        check(retryableFailed > 0) { "RETRYABLE_FAILED 工作项计数不能小于零" }
        copy(retryableFailed = retryableFailed - 1, running = running + 1)
    }
    else -> error("不可运行的工作项状态：${item.status}")
}

private fun BatchAnalysisSnapshot.afterCompletion(): BatchAnalysisSnapshot {
    check(running > 0) { "RUNNING 工作项计数不能小于零" }
    return copy(running = running - 1, completed = completed + 1)
}

private fun BatchAnalysisSnapshot.afterFailure(
    retryable: Boolean,
): BatchAnalysisSnapshot {
    check(running > 0) { "RUNNING 工作项计数不能小于零" }
    return if (retryable) {
        copy(running = running - 1, retryableFailed = retryableFailed + 1)
    } else {
        copy(running = running - 1, permanentFailed = permanentFailed + 1)
    }
}

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

internal fun BatchExecutionOutcome.Success.toWorkerOutputData(): Data {
    val executionResult = result
    if (executionResult == null) {
        return workerErrorData(errorMessage ?: "批量分析执行失败")
    }
    return Data.Builder()
        .putAll(executionResult.toData())
        .apply {
            errorMessage?.let { message ->
                putString(BatchAnalysisWorker.KEY_ERROR_MESSAGE, message)
            }
        }
        .build()
}

private fun workerErrorData(message: String): Data = Data.Builder()
    .putString(BatchAnalysisWorker.KEY_ERROR_MESSAGE, message)
    .build()

private fun Exception.failureMessage(): String = message ?: "批量分析失败"

private fun Throwable.hasDatabaseCause(): Boolean =
    generateSequence(this) { current -> current.cause }
        .any { current -> current is SQLException }
