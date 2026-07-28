package com.frameyu.app.analysis.batch

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import com.frameyu.app.FrameYuApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnalysisSchedulerTest {
    @Test
    fun `enqueue 使用固定唯一名称追加并只传 batch_id`() = runBlocking {
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
            setOf("com.frameyu.app.analysis.batch.BatchAnalysisWorker", "frameyu-batch:42"),
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
        val scheduler = BatchAnalysisScheduler(
            FakeBatchSchedulerWorkManager(events = events),
            RecordingLifecycleStore(events),
        )

        scheduler.resume(3L)

        assertEquals(
            listOf("store.resume:3", "work.enqueue:frameyu-batch-analysis:3"),
            events,
        )
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
            activeBatchIds += 2L
        }
        val store = RecordingLifecycleStore().apply {
            reconcilableBatchIds += listOf(1L, 2L, 3L)
        }
        val scheduler = BatchAnalysisScheduler(workManager, store)

        val enqueued = scheduler.reconcilePendingBatches()

        assertEquals(listOf(1L, 3L), enqueued.keys.toList())
        assertEquals(listOf(1L, 3L), workManager.enqueued.map(EnqueuedBatchWork::batchId))
    }

    @Test
    fun `重复 reconcile 对账保持幂等且不取消任何节点`() = runBlocking {
        val workManager = FakeBatchSchedulerWorkManager()
        val store = RecordingLifecycleStore().apply {
            reconcilableBatchIds += listOf(4L, 5L)
        }
        val scheduler = BatchAnalysisScheduler(workManager, store)

        val first = scheduler.reconcilePendingBatches()
        val second = scheduler.reconcilePendingBatches()

        assertEquals(setOf(4L, 5L), first.keys)
        assertTrue(second.isEmpty())
        assertEquals(listOf(4L, 5L), workManager.enqueued.map(EnqueuedBatchWork::batchId))
    }
}

private data class EnqueuedBatchWork(
    val uniqueWorkName: String,
    val policy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest,
) {
    val batchId: Long
        get() = request.workSpec.input.getLong(BatchAnalysisWorker.KEY_BATCH_ID, -1L)
}

private class FakeBatchSchedulerWorkManager(
    private val events: MutableList<String> = mutableListOf(),
) : BatchSchedulerWorkManager {
    val enqueued = mutableListOf<EnqueuedBatchWork>()
    val activeBatchIds = linkedSetOf<Long>()
    var failure: Exception? = null

    override suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        enqueued += EnqueuedBatchWork(uniqueWorkName, existingWorkPolicy, request)
        val batchId = request.workSpec.input.getLong(BatchAnalysisWorker.KEY_BATCH_ID, -1L)
        events += "work.enqueue:$uniqueWorkName:$batchId"
        failure?.let { throw it }
        activeBatchIds += batchId
    }

    override suspend fun activeBatchIds(): Set<Long> = activeBatchIds.toSet()
}

private class RecordingLifecycleStore(
    private val events: MutableList<String> = mutableListOf(),
) : BatchAnalysisLifecycleStore {
    val reconcilableBatchIds = mutableListOf<Long>()

    override suspend fun pauseBatch(batchId: Long) {
        events += "store.pause:$batchId"
    }

    override suspend fun resumeBatch(batchId: Long) {
        events += "store.resume:$batchId"
    }

    override suspend fun cancelBatch(batchId: Long) {
        events += "store.cancel:$batchId"
    }

    override suspend fun retryFailures(batchId: Long) {
        events += "store.retry:$batchId"
    }

    override suspend fun markBatchNeedsAttention(batchId: Long, message: String) {
        events += "store.needs-attention:$batchId:$message"
    }

    override suspend fun reconcilableBatchIds(): List<Long> = reconcilableBatchIds.toList()
}
