package com.frameyu.app.analysis.batch

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnalysisSchedulerTest {
    @Test
    fun `enqueue 使用固定唯一名称追加并只传 batch_id`() {
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
    fun `pause 先持久化再精确取消对应 active work`() = runBlocking {
        val events = mutableListOf<String>()
        val firstId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        val finishedId = UUID.randomUUID()
        val workManager = FakeBatchSchedulerWorkManager(
            events = events,
            snapshots = listOf(
                BatchScheduledWorkSnapshot(firstId, WorkInfo.State.RUNNING, 1L),
                BatchScheduledWorkSnapshot(otherId, WorkInfo.State.ENQUEUED, 2L),
                BatchScheduledWorkSnapshot(finishedId, WorkInfo.State.SUCCEEDED, 1L),
            ),
        )
        val scheduler = BatchAnalysisScheduler(workManager, RecordingLifecycleStore(events))

        scheduler.pause(1L)

        assertEquals(
            listOf("store.pause:1", "work.list", "work.cancel:$firstId"),
            events,
        )
        assertFalse(workManager.cancelledIds.contains(otherId))
        assertFalse(workManager.cancelledIds.contains(finishedId))
        assertTrue(workManager.cancelledUniqueNames.isEmpty())
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
    fun `cancel 先持久化且不取消其他批次`() = runBlocking {
        val events = mutableListOf<String>()
        val targetId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        val workManager = FakeBatchSchedulerWorkManager(
            events = events,
            snapshots = listOf(
                BatchScheduledWorkSnapshot(targetId, WorkInfo.State.BLOCKED, 4L),
                BatchScheduledWorkSnapshot(otherId, WorkInfo.State.RUNNING, 5L),
            ),
        )
        val scheduler = BatchAnalysisScheduler(workManager, RecordingLifecycleStore(events))

        scheduler.cancel(4L)

        assertEquals(
            listOf("store.cancel:4", "work.list", "work.cancel:$targetId"),
            events,
        )
        assertFalse(workManager.cancelledIds.contains(otherId))
        assertTrue(workManager.cancelledUniqueNames.isEmpty())
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
}

private data class EnqueuedBatchWork(
    val uniqueWorkName: String,
    val policy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest,
)

private class FakeBatchSchedulerWorkManager(
    private val events: MutableList<String> = mutableListOf(),
    private val snapshots: List<BatchScheduledWorkSnapshot> = emptyList(),
) : BatchSchedulerWorkManager {
    val enqueued = mutableListOf<EnqueuedBatchWork>()
    val cancelledIds = mutableListOf<UUID>()
    val cancelledUniqueNames = mutableListOf<String>()

    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        enqueued += EnqueuedBatchWork(uniqueWorkName, existingWorkPolicy, request)
        val batchId = request.workSpec.input.getLong(BatchAnalysisWorker.KEY_BATCH_ID, -1L)
        events += "work.enqueue:$uniqueWorkName:$batchId"
    }

    override suspend fun getUniqueWork(): List<BatchScheduledWorkSnapshot> {
        events += "work.list"
        return snapshots
    }

    override fun cancelWorkById(id: UUID) {
        cancelledIds += id
        events += "work.cancel:$id"
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        cancelledUniqueNames += uniqueWorkName
        events += "work.cancelUnique:$uniqueWorkName"
    }
}

private class RecordingLifecycleStore(
    private val events: MutableList<String> = mutableListOf(),
) : BatchAnalysisLifecycleStore {
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
}
