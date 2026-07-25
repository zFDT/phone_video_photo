package com.frameyu.app.sampling

import androidx.work.WorkInfo
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class VideoAnalysisWorkSchedulerTest {
    @Test
    fun `取消单个任务只取消对应的 work`() = runBlocking {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val workManager = FakeSchedulerWorkManager(
            mutableListOf(
                ScheduledWorkSnapshot(firstId, WorkInfo.State.ENQUEUED, 1L),
                ScheduledWorkSnapshot(secondId, WorkInfo.State.RUNNING, 2L),
            ),
        )
        val scheduler = VideoAnalysisWorkScheduler(workManager)

        scheduler.cancel(1L)

        assertEquals(listOf(firstId), workManager.cancelledWorkIds)
        assertTrue(workManager.cancelledUniqueWorkNames.isEmpty())
    }

    @Test
    fun `查询未知任务返回 null`() = runBlocking {
        val scheduler = VideoAnalysisWorkScheduler(FakeSchedulerWorkManager())

        assertNull(scheduler.state(99L))
    }

    @Test
    fun `查询任务状态返回唯一链中对应任务状态`() = runBlocking {
        val workId = UUID.randomUUID()
        val workManager = FakeSchedulerWorkManager(
            mutableListOf(ScheduledWorkSnapshot(workId, WorkInfo.State.RUNNING, 7L)),
        )
        val scheduler = VideoAnalysisWorkScheduler(workManager)

        assertEquals(WorkInfo.State.RUNNING, scheduler.state(7L))
    }

    @Test
    fun `恢复排队将指定任务重新追加到唯一工作链`() = runBlocking {
        val workManager = FakeSchedulerWorkManager()
        val scheduler = VideoAnalysisWorkScheduler(workManager)

        val requestIds = scheduler.resume(listOf(3L, 4L))

        assertEquals(requestIds, workManager.enqueuedRequests.map { it.id })
        assertEquals(
            listOf("frameyu-analysis-task:3", "frameyu-analysis-task:4"),
            workManager.enqueuedRequests.flatMap { it.tags.filter { tag -> tag.startsWith("frameyu-analysis-task:") } },
        )
        assertEquals(
            listOf(ExistingWorkPolicy.APPEND_OR_REPLACE, ExistingWorkPolicy.APPEND_OR_REPLACE),
            workManager.enqueuedPolicies,
        )
        assertEquals(2, workManager.enqueuedRequests.size)
    }
}

private class FakeSchedulerWorkManager(
    private val snapshots: MutableList<ScheduledWorkSnapshot> = mutableListOf(),
) : SchedulerWorkManager {
    val enqueuedRequests = mutableListOf<androidx.work.OneTimeWorkRequest>()
    val enqueuedPolicies = mutableListOf<ExistingWorkPolicy>()
    val cancelledWorkIds = mutableListOf<UUID>()
    val cancelledUniqueWorkNames = mutableListOf<String>()

    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        request: androidx.work.OneTimeWorkRequest,
    ) {
        enqueuedPolicies += existingWorkPolicy
        enqueuedRequests += request
    }

    override fun cancelUniqueWork(uniqueWorkName: String) {
        cancelledUniqueWorkNames += uniqueWorkName
    }

    override fun cancelWorkById(id: UUID) {
        cancelledWorkIds += id
    }

    override suspend fun getUniqueWork(): List<ScheduledWorkSnapshot> = snapshots
}
