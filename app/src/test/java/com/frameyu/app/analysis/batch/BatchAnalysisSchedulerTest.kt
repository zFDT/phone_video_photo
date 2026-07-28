package com.frameyu.app.analysis.batch

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import com.frameyu.app.FrameYuApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnalysisSchedulerTest {
    @Test
    fun `enqueue 使用固定唯一名称并携带 batch 与 generation`() = runBlocking {
        val workManager = FakeBatchSchedulerWorkManager()
        val scheduler = BatchAnalysisScheduler(workManager, RecordingLifecycleStore())

        val requestId = scheduler.enqueue(42L)

        assertEquals(requestId, workManager.enqueued.single().request.id)
        assertEquals("frameyu-batch-analysis", workManager.enqueued.single().uniqueWorkName)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, workManager.enqueued.single().policy)
        assertEquals(
            42L,
            workManager.enqueued.single().request.workSpec.input.getLong(
                BatchAnalysisWorker.KEY_BATCH_ID,
                -1L,
            ),
        )
        assertEquals(
            0L,
            workManager.enqueued.single().request.workSpec.input.getLong(
                BatchAnalysisWorker.KEY_EXECUTION_GENERATION,
                -1L,
            ),
        )
        assertEquals(
            setOf(
                "com.frameyu.app.analysis.batch.BatchAnalysisWorker",
                "frameyu-batch:42",
                "frameyu-batch-execution:42:0",
            ),
            workManager.enqueued.single().request.tags,
        )
    }

    @Test
    fun `pause 只持久化控制态且保留唯一链中所有 request`() = runBlocking {
        val events = mutableListOf<String>()
        val workManager = FakeBatchSchedulerWorkManager(events = events)
        val scheduler = BatchAnalysisScheduler(workManager, RecordingLifecycleStore(events))
        scheduler.enqueue(1L)
        scheduler.enqueue(2L)

        scheduler.pause(1L)
        scheduler.enqueue(3L)

        // WorkManager 会把单个依赖节点的取消传播给后续节点，因此暂停只能协作式停止。
        assertEquals(
            listOf(
                "work.enqueue:frameyu-batch-analysis:1",
                "work.enqueue:frameyu-batch-analysis:2",
                "store.pause:1",
                "work.enqueue:frameyu-batch-analysis:3",
            ),
            events,
        )
        assertEquals(listOf(1L, 2L, 3L), workManager.enqueued.map(EnqueuedBatchWork::batchId))
    }

    @Test
    fun `resume 先持久化再追加到唯一链`() = runBlocking {
        val events = mutableListOf<String>()
        val workManager = FakeBatchSchedulerWorkManager(events = events)
        val scheduler = BatchAnalysisScheduler(workManager, RecordingLifecycleStore(events))

        scheduler.resume(3L)

        assertEquals(
            listOf("store.resume:3", "work.enqueue:frameyu-batch-analysis:3"),
            events,
        )
        assertEquals(1L, workManager.enqueued.single().executionGeneration)
    }

    @Test
    fun `cancel 只持久化控制态且后续批次仍留在唯一链`() = runBlocking {
        val events = mutableListOf<String>()
        val workManager = FakeBatchSchedulerWorkManager(events = events)
        val scheduler = BatchAnalysisScheduler(workManager, RecordingLifecycleStore(events))
        scheduler.enqueue(4L)
        scheduler.enqueue(5L)

        scheduler.cancel(4L)
        scheduler.enqueue(6L)

        // 不取消目标 request，避免 WorkManager 把 CANCELLED 级联到依赖它的其他批次。
        assertEquals(
            listOf(
                "work.enqueue:frameyu-batch-analysis:4",
                "work.enqueue:frameyu-batch-analysis:5",
                "store.cancel:4",
                "work.enqueue:frameyu-batch-analysis:6",
            ),
            events,
        )
        assertEquals(listOf(4L, 5L, 6L), workManager.enqueued.map(EnqueuedBatchWork::batchId))
    }

    @Test
    fun `retryFailures 先持久化再追加到唯一链`() = runBlocking {
        val events = mutableListOf<String>()
        val scheduler = BatchAnalysisScheduler(
            FakeBatchSchedulerWorkManager(events = events),
            RecordingLifecycleStore(events),
        )

        scheduler.retryFailures(6L)

        assertEquals(
            listOf("store.retry:6", "work.enqueue:frameyu-batch-analysis:6"),
            events,
        )
    }

    @Test
    fun `所有操作拒绝非正 batchId`() = runBlocking {
        val scheduler = BatchAnalysisScheduler(
            FakeBatchSchedulerWorkManager(),
            RecordingLifecycleStore(),
        )

        assertTrue(runCatching { scheduler.enqueue(0L) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { scheduler.pause(0L) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { scheduler.resume(0L) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { scheduler.cancel(0L) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { scheduler.retryFailures(0L) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `Application 对批量 scheduler 保持 lazy 公开访问`() {
        val delegateField = FrameYuApplication::class.java.declaredFields
            .singleOrNull { field -> field.name == "batchAnalysisWorkScheduler\$delegate" }

        assertTrue(delegateField != null)
        assertEquals(Lazy::class.java, delegateField?.type)
        assertTrue(
            FrameYuApplication::class.java.methods.any { method ->
                method.name == "getBatchAnalysisWorkScheduler" &&
                    method.returnType == BatchAnalysisScheduler::class.java
            },
        )
    }

    @Test
    fun `enqueue 失败先持久化 NEEDS_ATTENTION 再向调用方传播`() = runBlocking {
        val events = mutableListOf<String>()
        val enqueueFailure = IllegalStateException("WorkManager 数据库不可写")
        val workManager = FakeBatchSchedulerWorkManager(events).apply {
            failure = enqueueFailure
        }
        val scheduler = BatchAnalysisScheduler(
            workManager,
            RecordingLifecycleStore(events),
        )

        val thrown = runCatching { scheduler.enqueue(9L) }.exceptionOrNull()

        assertEquals(enqueueFailure, thrown)
        assertEquals(
            listOf(
                "work.enqueue:frameyu-batch-analysis:9",
                "store.needs-attention:9:批量任务入队失败",
            ),
            events,
        )
    }

    @Test
    fun `reconcile 跳过已有 active request 并只补缺失批次`() = runBlocking {
        val workManager = FakeBatchSchedulerWorkManager().apply {
            activeExecutions += BatchExecutionKey(2L, 0L)
        }
        val store = RecordingLifecycleStore().apply {
            reconcilableExecutions += listOf(
                BatchExecutionKey(1L, 0L),
                BatchExecutionKey(2L, 0L),
                BatchExecutionKey(3L, 0L),
            )
        }
        val scheduler = BatchAnalysisScheduler(workManager, store)

        val result = scheduler.reconcilePendingBatches()

        assertEquals(listOf(1L, 3L), result.enqueued.keys.toList())
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf(1L, 3L), workManager.enqueued.map(EnqueuedBatchWork::batchId))
    }

    @Test
    fun `reconcile 不把旧 generation 的 active request 当作当前执行`() = runBlocking {
        val workManager = FakeBatchSchedulerWorkManager().apply {
            activeExecutions += BatchExecutionKey(2L, 0L)
        }
        val store = RecordingLifecycleStore().apply {
            setGeneration(2L, 2L)
            reconcilableExecutions += BatchExecutionKey(2L, 2L)
        }
        val scheduler = BatchAnalysisScheduler(workManager, store)

        val result = scheduler.reconcilePendingBatches()

        assertEquals(listOf(2L), result.enqueued.keys.toList())
        assertEquals(2L, workManager.enqueued.single().executionGeneration)
    }

    @Test
    fun `reconcile 隔离单批入队失败并继续补入后续批次`() = runBlocking {
        val workManager = FakeBatchSchedulerWorkManager().apply {
            failuresByBatch[2L] = IllegalStateException("第二批入队失败")
        }
        val store = RecordingLifecycleStore().apply {
            reconcilableExecutions += listOf(
                BatchExecutionKey(1L, 0L),
                BatchExecutionKey(2L, 0L),
                BatchExecutionKey(3L, 0L),
            )
        }
        val scheduler = BatchAnalysisScheduler(workManager, store)

        val result = scheduler.reconcilePendingBatches()

        assertEquals(listOf(1L, 3L), result.enqueued.keys.toList())
        assertEquals(listOf(2L), result.failures.keys.toList())
        assertEquals(
            listOf(1L, 2L, 3L),
            workManager.enqueued.map(EnqueuedBatchWork::batchId),
        )
    }

    @Test
    fun `重复 reconcile 对账保持幂等且不取消任何节点`() = runBlocking {
        val workManager = FakeBatchSchedulerWorkManager()
        val store = RecordingLifecycleStore().apply {
            reconcilableExecutions += listOf(
                BatchExecutionKey(4L, 0L),
                BatchExecutionKey(5L, 0L),
            )
        }
        val scheduler = BatchAnalysisScheduler(workManager, store)

        val first = scheduler.reconcilePendingBatches()
        val second = scheduler.reconcilePendingBatches()

        assertEquals(setOf(4L, 5L), first.enqueued.keys)
        assertTrue(first.failures.isEmpty())
        assertTrue(second.enqueued.isEmpty())
        assertTrue(second.failures.isEmpty())
        assertEquals(listOf(4L, 5L), workManager.enqueued.map(EnqueuedBatchWork::batchId))
    }

    @Test
    fun `状态提交后调用方取消仍完成入队再传播取消`() = runBlocking {
        val events = mutableListOf<String>()
        val workManager = FakeBatchSchedulerWorkManager(events).apply {
            beforeEnqueue = { yield() }
        }
        val store = RecordingLifecycleStore(
            events = events,
            afterResume = {
                currentCoroutineContext().cancel(CancellationException("调用方取消"))
            },
        )
        val scheduler = BatchAnalysisScheduler(workManager, store)

        val job = launch {
            scheduler.resume(7L)
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(
            listOf("store.resume:7", "work.enqueue:frameyu-batch-analysis:7"),
            events,
        )
    }

    @Test
    fun `直接 enqueue 在等待 Operation 时取消仍完成提交`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val workManager = FakeBatchSchedulerWorkManager().apply {
            beforeEnqueue = {
                entered.complete(Unit)
                release.await()
            }
        }
        val scheduler = BatchAnalysisScheduler(workManager, RecordingLifecycleStore())

        val job = launch {
            scheduler.enqueue(8L)
        }
        entered.await()
        job.cancel(CancellationException("调用方取消"))
        release.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf(8L), workManager.enqueued.map(EnqueuedBatchWork::batchId))
    }

    @Test
    fun `旧 generation 入队失败不能覆盖新控制代`() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingLifecycleStore(events)
        val workManager = FakeBatchSchedulerWorkManager(events).apply {
            failure = IllegalStateException("旧 request 入队失败")
            beforeEnqueue = {
                store.advanceGeneration(9L)
            }
        }
        val scheduler = BatchAnalysisScheduler(workManager, store)

        val failure = runCatching { scheduler.enqueue(9L) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1L, store.batchExecutionKey(9L).executionGeneration)
        assertTrue(events.none { event -> event.startsWith("store.needs-attention:9") })
    }
}

private data class EnqueuedBatchWork(
    val uniqueWorkName: String,
    val policy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest,
) {
    val batchId: Long
        get() = request.workSpec.input.getLong(BatchAnalysisWorker.KEY_BATCH_ID, -1L)

    val executionGeneration: Long
        get() = request.workSpec.input.getLong(
            BatchAnalysisWorker.KEY_EXECUTION_GENERATION,
            -1L,
        )
}

private class FakeBatchSchedulerWorkManager(
    private val events: MutableList<String> = mutableListOf(),
) : BatchSchedulerWorkManager {
    val enqueued = mutableListOf<EnqueuedBatchWork>()
    val activeExecutions = linkedSetOf<BatchExecutionKey>()
    var failure: Exception? = null
    val failuresByBatch = mutableMapOf<Long, Exception>()
    var beforeEnqueue: suspend () -> Unit = {}

    override suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        beforeEnqueue()
        enqueued += EnqueuedBatchWork(uniqueWorkName, existingWorkPolicy, request)
        val batchId = request.workSpec.input.getLong(BatchAnalysisWorker.KEY_BATCH_ID, -1L)
        events += "work.enqueue:$uniqueWorkName:$batchId"
        (failuresByBatch[batchId] ?: failure)?.let { throw it }
        activeExecutions += BatchExecutionKey(
            batchId = batchId,
            executionGeneration = request.workSpec.input.getLong(
                BatchAnalysisWorker.KEY_EXECUTION_GENERATION,
                -1L,
            ),
        )
    }

    override suspend fun activeExecutions(): Set<BatchExecutionKey> =
        activeExecutions.toSet()
}

private class RecordingLifecycleStore(
    private val events: MutableList<String> = mutableListOf(),
    private val afterResume: suspend () -> Unit = {},
) : BatchAnalysisLifecycleStore {
    val reconcilableExecutions = mutableListOf<BatchExecutionKey>()
    private val generations = mutableMapOf<Long, Long>()

    override suspend fun batchExecutionKey(batchId: Long): BatchExecutionKey =
        BatchExecutionKey(batchId, generations.getOrDefault(batchId, 0L))

    override suspend fun pauseBatch(batchId: Long) {
        events += "store.pause:$batchId"
        advanceGeneration(batchId)
    }

    override suspend fun resumeBatch(batchId: Long) {
        events += "store.resume:$batchId"
        advanceGeneration(batchId)
        afterResume()
    }

    override suspend fun cancelBatch(batchId: Long) {
        events += "store.cancel:$batchId"
        advanceGeneration(batchId)
    }

    override suspend fun retryFailures(batchId: Long) {
        events += "store.retry:$batchId"
        advanceGeneration(batchId)
    }

    override suspend fun markBatchNeedsAttention(
        batchId: Long,
        executionGeneration: Long,
        message: String,
    ): Boolean {
        if (generations.getOrDefault(batchId, 0L) != executionGeneration) return false
        events += "store.needs-attention:$batchId:$message"
        return true
    }

    override suspend fun reconcilableExecutions(): List<BatchExecutionKey> =
        reconcilableExecutions.toList()

    fun setGeneration(batchId: Long, generation: Long) {
        generations[batchId] = generation
    }

    fun advanceGeneration(batchId: Long) {
        generations[batchId] = generations.getOrDefault(batchId, 0L) + 1L
    }
}
