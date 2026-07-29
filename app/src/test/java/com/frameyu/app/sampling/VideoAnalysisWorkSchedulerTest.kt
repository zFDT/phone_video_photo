package com.frameyu.app.sampling

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAnalysisWorkSchedulerTest {
    @Test
    fun `enqueue 同时写入 taskId generation 和执行 tag`() = runBlocking {
        val store = RecordingVideoLifecycleStore(generation = 3L)
        val workManager = FakeSchedulerWorkManager()
        val scheduler = VideoAnalysisWorkScheduler(workManager, store)

        scheduler.enqueue(7L)

        val request = workManager.enqueuedRequests.single()
        assertEquals(7L, request.workSpec.input.getLong(VideoAnalysisWorker.KEY_TASK_ID, -1L))
        assertEquals(
            3L,
            request.workSpec.input.getLong(VideoAnalysisWorker.KEY_EXECUTION_GENERATION, -1L),
        )
        assertTrue(request.tags.contains("frameyu-analysis-task:7"))
        assertTrue(request.tags.contains("frameyu-analysis-execution:7:3"))
        assertEquals(listOf(ExistingWorkPolicy.APPEND_OR_REPLACE), workManager.enqueuedPolicies)
    }

    @Test
    fun `重复 enqueue 复用同一活动代 requestId`() = runBlocking {
        val execution = VideoAnalysisExecutionKey(7L, 2L)
        val activeRequestId = UUID.randomUUID()
        val workManager = FakeSchedulerWorkManager(
            mutableListOf(
                ScheduledWorkSnapshot(
                    activeRequestId,
                    WorkInfo.State.RUNNING,
                    execution.taskId,
                    execution.executionGeneration,
                ),
            ),
        )
        val scheduler = VideoAnalysisWorkScheduler(
            workManager,
            RecordingVideoLifecycleStore(generation = 2L),
        )

        val requestId = scheduler.enqueue(7L)

        assertEquals(activeRequestId, requestId)
        assertTrue(workManager.enqueuedRequests.isEmpty())
    }

    @Test
    fun `双协程同时 enqueue 同一活动代只创建一个节点`() = runBlocking {
        withTimeout(3_000L) {
            val enteredEnqueue = CompletableDeferred<Unit>()
            val releaseEnqueue = CompletableDeferred<Unit>()
            val workManager = FakeSchedulerWorkManager().apply {
                beforeEnqueue = {
                    enteredEnqueue.complete(Unit)
                    releaseEnqueue.await()
                }
            }
            val scheduler = VideoAnalysisWorkScheduler(
                workManager,
                RecordingVideoLifecycleStore(generation = 2L),
            )

            val first = async { scheduler.enqueue(7L) }
            enteredEnqueue.await()
            val second = async { scheduler.enqueue(7L) }
            releaseEnqueue.complete(Unit)

            assertEquals(first.await(), second.await())
            assertEquals(1, workManager.enqueuedRequests.size)
        }
    }

    @Test
    fun `入队异常后没有竞态节点则同代落 FAILED 并传播异常`() = runBlocking {
        val failure = IllegalStateException("WorkManager 数据库不可写")
        val store = RecordingVideoLifecycleStore(generation = 4L)
        val workManager = FakeSchedulerWorkManager().apply {
            enqueueFailure = failure
        }
        val scheduler = VideoAnalysisWorkScheduler(workManager, store)

        val actual = runCatching { scheduler.enqueue(9L) }.exceptionOrNull()

        assertTrue(actual is IllegalStateException)
        assertEquals(failure.message, actual?.message)
        assertEquals(listOf(VideoAnalysisExecutionKey(9L, 4L)), store.failedExecutions)
    }

    @Test
    fun `入队异常后复用竞态成功的同代节点`() = runBlocking {
        val execution = VideoAnalysisExecutionKey(9L, 4L)
        val racedRequestId = UUID.randomUUID()
        val workManager = FakeSchedulerWorkManager().apply {
            enqueueFailure = IllegalStateException("重复入队竞态")
            afterFailedEnqueue = {
                snapshots += ScheduledWorkSnapshot(
                    racedRequestId,
                    WorkInfo.State.ENQUEUED,
                    execution.taskId,
                    execution.executionGeneration,
                )
            }
        }
        val store = RecordingVideoLifecycleStore(generation = 4L)
        val scheduler = VideoAnalysisWorkScheduler(workManager, store)

        val requestId = scheduler.enqueue(9L)

        assertEquals(racedRequestId, requestId)
        assertTrue(store.failedExecutions.isEmpty())
    }

    @Test
    fun `首次 active lookup 异常仍继续完成入队`() = runBlocking {
        val store = RecordingVideoLifecycleStore(generation = 4L)
        val workManager = FakeSchedulerWorkManager().apply {
            lookupFailuresRemaining = 1
        }
        val scheduler = VideoAnalysisWorkScheduler(workManager, store)

        val requestId = scheduler.enqueue(9L)

        assertEquals(workManager.enqueuedRequests.single().id, requestId)
        assertTrue(store.failedExecutions.isEmpty())
    }

    @Test
    fun `active lookup 与入队均异常时同代落 FAILED`() = runBlocking {
        val store = RecordingVideoLifecycleStore(generation = 4L)
        val workManager = FakeSchedulerWorkManager().apply {
            lookupFailuresRemaining = 2
            enqueueFailure = IllegalStateException("入队失败")
        }
        val scheduler = VideoAnalysisWorkScheduler(workManager, store)

        val actual = runCatching { scheduler.enqueue(9L) }.exceptionOrNull()

        assertTrue(actual is IllegalStateException)
        assertEquals(listOf(VideoAnalysisExecutionKey(9L, 4L)), store.failedExecutions)
        assertEquals(2, workManager.lookupCalls)
    }

    @Test
    fun `取消单个任务只协作更新 lifecycle 不调用 WorkManager cancel`() = runBlocking {
        val store = RecordingVideoLifecycleStore()
        val workManager = FakeSchedulerWorkManager()
        val scheduler = VideoAnalysisWorkScheduler(workManager, store)

        scheduler.cancel(1L)

        assertEquals(listOf(1L), store.cancelledTaskIds)
        assertEquals(0, workManager.workManagerCancelCalls)
    }

    @Test
    fun `取消全部任务只协作更新 lifecycle 不调用 WorkManager cancel`() = runBlocking {
        val store = RecordingVideoLifecycleStore()
        val workManager = FakeSchedulerWorkManager()
        val scheduler = VideoAnalysisWorkScheduler(workManager, store)

        scheduler.cancelAll()

        assertEquals(1, store.cancelAllCalls)
        assertEquals(0, workManager.workManagerCancelCalls)
    }
}

private class FakeSchedulerWorkManager(
    val snapshots: MutableList<ScheduledWorkSnapshot> = mutableListOf(),
) : SchedulerWorkManager {
    val enqueuedRequests = mutableListOf<OneTimeWorkRequest>()
    val enqueuedPolicies = mutableListOf<ExistingWorkPolicy>()
    var enqueueFailure: Exception? = null
    var afterFailedEnqueue: () -> Unit = {}
    var workManagerCancelCalls = 0
    var lookupFailuresRemaining = 0
    var lookupCalls = 0
    var beforeEnqueue: suspend () -> Unit = {}

    override suspend fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        beforeEnqueue()
        enqueuedPolicies += existingWorkPolicy
        enqueuedRequests += request
        enqueueFailure?.let {
            afterFailedEnqueue()
            throw it
        }
        snapshots += ScheduledWorkSnapshot(
            request.id,
            WorkInfo.State.ENQUEUED,
            request.workSpec.input.getLong(VideoAnalysisWorker.KEY_TASK_ID, -1L),
            request.workSpec.input.getLong(
                VideoAnalysisWorker.KEY_EXECUTION_GENERATION,
                -1L,
            ),
        )
    }

    override suspend fun getUniqueWork(): List<ScheduledWorkSnapshot> {
        lookupCalls += 1
        if (lookupFailuresRemaining > 0) {
            lookupFailuresRemaining -= 1
            throw IllegalStateException("active lookup 失败")
        }
        return snapshots.toList()
    }
}

private class RecordingVideoLifecycleStore(
    private val generation: Long = 0L,
) : VideoAnalysisLifecycleStore {
    val cancelledTaskIds = mutableListOf<Long>()
    var cancelAllCalls = 0
    val failedExecutions = mutableListOf<VideoAnalysisExecutionKey>()

    override suspend fun prepareExecution(taskId: Long): VideoAnalysisExecutionKey =
        VideoAnalysisExecutionKey(taskId, generation)

    override suspend fun cancel(taskId: Long) {
        cancelledTaskIds += taskId
    }

    override suspend fun cancelAll() {
        cancelAllCalls += 1
    }

    override suspend fun markEnqueueFailed(
        execution: VideoAnalysisExecutionKey,
        message: String,
    ): Boolean {
        failedExecutions += execution
        return true
    }
}
