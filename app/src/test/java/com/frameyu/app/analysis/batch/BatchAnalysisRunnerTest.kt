package com.frameyu.app.analysis.batch

import com.frameyu.app.data.db.AnalysisWorkItemEntity
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnalysisRunnerTest {
    @Test
    fun `单项可重试失败不终止后续工作项`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L, 2L, 3L))
        val analyzed = mutableListOf<Long>()
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator { item ->
                analyzed += item.id
                if (item.id == 2L) throw IOException("读取失败")
            },
            pageSize = 2,
        )
        val progress = mutableListOf<BatchAnalysisProgress>()

        val result = runner.run(BATCH_ID, progress::add)

        assertEquals(listOf(1L, 2L, 3L), analyzed)
        assertEquals(2, result.completed)
        assertEquals(1, result.failed)
        assertEquals(1, result.retryableFailed)
        assertEquals(0, result.permanentFailed)
        assertEquals(3, result.total)
        assertEquals(WorkItemStatus.COMPLETED, store.status(1L))
        assertEquals(WorkItemStatus.RETRYABLE_FAILED, store.status(2L))
        assertEquals(WorkItemStatus.COMPLETED, store.status(3L))
        assertEquals(BatchFinalStatus.NEEDS_ATTENTION, store.finalStatus)
        assertEquals(3L, progress.last().currentMediaId)
        assertEquals("QUALITY", progress.last().currentStage)
    }

    @Test
    fun `同一次运行不会立即重试刚失败的低 ID 工作项`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L, 2L))
        val attempts = mutableMapOf<Long, Int>()
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator { item ->
                attempts[item.id] = attempts.getOrDefault(item.id, 0) + 1
                if (item.id == 1L) throw IOException("稍后重试")
            },
            pageSize = 1,
        )

        runner.run(BATCH_ID)

        assertEquals(mapOf(1L to 1, 2L to 1), attempts)
        assertEquals(WorkItemStatus.RETRYABLE_FAILED, store.status(1L))
        assertEquals(WorkItemStatus.COMPLETED, store.status(2L))
    }

    @Test
    fun `第二次运行只重试 retryable 工作项`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L, 2L))
        val attempts = mutableMapOf<Long, Int>()
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator { item ->
                val attempt = attempts.getOrDefault(item.id, 0) + 1
                attempts[item.id] = attempt
                when {
                    item.id == 1L && attempt == 1 -> throw IOException("临时失败")
                    item.id == 2L -> throw IllegalStateException("永久失败")
                }
            },
            pageSize = 1,
        )

        val first = runner.run(BATCH_ID)
        val second = runner.run(BATCH_ID)

        assertEquals(0, first.completed)
        assertEquals(2, first.failed)
        assertEquals(mapOf(1L to 2, 2L to 1), attempts)
        assertEquals(1, second.completed)
        assertEquals(1, second.failed)
        assertEquals(0, second.retryableFailed)
        assertEquals(1, second.permanentFailed)
        assertEquals(WorkItemStatus.COMPLETED, store.status(1L))
        assertEquals(WorkItemStatus.PERMANENT_FAILED, store.status(2L))
        assertEquals(BatchFinalStatus.COMPLETED, store.finalStatus)
    }

    @Test
    fun `协程取消原样传播且不标记失败`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val cancellation = CancellationException("用户取消")
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator { throw cancellation },
        )

        val thrown = runCatching { runner.run(BATCH_ID) }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(WorkItemStatus.RUNNING, store.status(1L))
        assertTrue(store.failedItemIds.isEmpty())
    }

    @Test
    fun `启动时恢复旧 RUNNING 工作项并继续处理`() = runBlocking {
        val store = FakeBatchAnalysisStore(
            items(
                1L to WorkItemStatus.RUNNING,
                2L to WorkItemStatus.PENDING,
            ),
        )
        val analyzed = mutableListOf<Long>()
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator { analyzed += it.id },
            pageSize = 1,
        )

        val result = runner.run(BATCH_ID)

        assertEquals(1, store.recoverCalls)
        assertEquals(listOf(1L, 2L), analyzed)
        assertEquals(2, result.completed)
    }

    @Test
    fun `分页游标不会让低 ID 失败项阻塞后续页`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L, 2L, 3L, 4L, 5L))
        val analyzed = mutableListOf<Long>()
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator { item ->
                analyzed += item.id
                if (item.id == 1L) throw IOException("低 ID 失败")
            },
            pageSize = 2,
        )

        val result = runner.run(BATCH_ID)

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), analyzed)
        assertEquals(4, result.completed)
        assertEquals(1, result.failed)
        assertTrue(store.requestedCursors.containsAll(listOf(0L, 2L, 4L)))
    }

    @Test
    fun `暂停批次会在下一项前停止`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L, 2L))
        val analyzed = mutableListOf<Long>()
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator { item ->
                analyzed += item.id
                if (item.id == 1L) store.pauseBatch(BATCH_ID)
            },
        )

        val result = runner.run(BATCH_ID)

        assertEquals(listOf(1L), analyzed)
        assertTrue(result.paused)
        assertFalse(result.cancelled)
        assertEquals(WorkItemStatus.RUNNING, store.status(1L))
        assertEquals(WorkItemStatus.PAUSED, store.status(2L))
    }

    @Test
    fun `分析期间取消不会把当前项错误完成`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L, 2L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {
                store.cancelBatch(BATCH_ID)
            },
        )

        val result = runner.run(BATCH_ID)

        assertTrue(result.cancelled)
        assertEquals(WorkItemStatus.CANCELLED, store.status(1L))
        assertEquals(WorkItemStatus.CANCELLED, store.status(2L))
        assertTrue(store.completedItemIds.isEmpty())
    }

    @Test
    fun `最后一项完成后发生暂停不会被最终状态覆盖`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )

        val result = runner.run(BATCH_ID) { progress ->
            if (progress.completed == 1) store.pauseBatch(BATCH_ID)
        }

        assertTrue(result.paused)
        assertEquals(null, store.finalStatus)
        assertEquals(WorkItemStatus.COMPLETED, store.status(1L))
    }

    @Test
    fun `最终检查后发生暂停不会被完成状态覆盖`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )
        store.beforeFinishBatch = { store.pauseBatch(BATCH_ID) }

        val result = runner.run(BATCH_ID)

        assertTrue(result.paused)
        assertFalse(result.cancelled)
        assertEquals(null, store.finalStatus)
        assertEquals(WorkItemStatus.COMPLETED, store.status(1L))
    }

    @Test
    fun `最终检查后发生取消不会被完成状态覆盖`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )
        store.beforeFinishBatch = { store.cancelBatch(BATCH_ID) }

        val result = runner.run(BATCH_ID)

        assertTrue(result.cancelled)
        assertFalse(result.paused)
        assertEquals(null, store.finalStatus)
        assertEquals(WorkItemStatus.COMPLETED, store.status(1L))
    }

    @Test
    fun `启动检查后暂停导致批次运行转换未命中时正常退场`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {
                error("暂停批次不应进入分析")
            },
        )
        store.beforeMarkBatchRunning = { store.pauseBatch(BATCH_ID) }

        val result = runner.run(BATCH_ID)

        assertTrue(result.paused)
        assertFalse(result.cancelled)
        assertEquals(WorkItemStatus.PAUSED, store.status(1L))
    }

    @Test
    fun `分析期间取消后 IOException 的失败转换未命中时正常退场`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L, 2L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {
                store.cancelBatch(BATCH_ID)
                throw IOException("取消后的读取失败")
            },
        )

        val result = runner.run(BATCH_ID)

        assertTrue(result.cancelled)
        assertFalse(result.paused)
        assertEquals(0, result.failed)
        assertTrue(store.failedItemIds.isEmpty())
        assertEquals(WorkItemStatus.CANCELLED, store.status(1L))
        assertEquals(WorkItemStatus.CANCELLED, store.status(2L))
    }

    @Test
    fun `分析后检查与完成转换之间取消时正常退场`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )
        store.beforeCompleteItem = { store.cancelBatch(BATCH_ID) }

        val result = runner.run(BATCH_ID)

        assertTrue(result.cancelled)
        assertFalse(result.paused)
        assertTrue(store.completedItemIds.isEmpty())
        assertEquals(WorkItemStatus.CANCELLED, store.status(1L))
    }

    @Test
    fun `最终写入前暂停后立即恢复时旧执行器正常退场`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )
        store.beforeFinishBatch = {
            store.pauseBatch(BATCH_ID)
            store.resumeBatch(BATCH_ID)
        }

        val result = runner.run(BATCH_ID)

        assertFalse(result.paused)
        assertFalse(result.cancelled)
        assertEquals(1, result.completed)
        assertEquals(null, store.finalStatus)
    }

    @Test
    fun `最终写入前重试把批次改回排队时旧执行器正常退场`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )
        store.beforeFinishBatch = { store.retryFailures(BATCH_ID) }

        val result = runner.run(BATCH_ID)

        assertFalse(result.paused)
        assertFalse(result.cancelled)
        assertEquals(1, result.completed)
        assertEquals(null, store.finalStatus)
    }

    @Test
    fun `不存在批次仍抛出编程错误`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )

        val thrown = runCatching { runner.run(BATCH_ID + 1L) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `Store 数据库异常仍原样传播`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val databaseError = IllegalStateException("数据库不可用")
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )
        store.beforeMarkBatchRunning = { throw databaseError }

        val thrown = runCatching { runner.run(BATCH_ID) }.exceptionOrNull()

        assertSame(databaseError, thrown)
    }

    @Test
    fun `基础设施异常提升到 Runner 边界且不写永久失败`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val infrastructureError = BatchAnalysisInfrastructureException(
            "Room 读取失败",
            IllegalStateException("数据库关闭"),
        )
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator { throw infrastructureError },
        )

        val thrown = runCatching { runner.run(BATCH_ID) }.exceptionOrNull()

        assertSame(infrastructureError, thrown)
        assertTrue(store.failedItemIds.isEmpty())
        assertEquals(WorkItemStatus.RUNNING, store.status(1L))
    }

    @Test
    fun `cause chain 中的 IOException 标记工作项为可重试`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {
                throw IllegalStateException("解码失败", IOException("媒体读取失败"))
            },
        )

        val result = runner.run(BATCH_ID)

        assertEquals(1, result.retryableFailed)
        assertEquals(0, result.permanentFailed)
        assertEquals(WorkItemStatus.RETRYABLE_FAILED, store.status(1L))
    }

    private fun items(vararg ids: Long): List<AnalysisWorkItemEntity> =
        ids.map { id -> item(id, WorkItemStatus.PENDING) }

    private fun items(
        vararg values: Pair<Long, WorkItemStatus>,
    ): List<AnalysisWorkItemEntity> = values.map { (id, status) -> item(id, status) }

    private fun item(id: Long, status: WorkItemStatus) = AnalysisWorkItemEntity(
        id = id,
        batchId = BATCH_ID,
        mediaAssetId = id,
        stage = "QUALITY",
        status = status.name,
        analyzerVersion = "quality-v1",
        updatedAtMillis = 0L,
    )

    private companion object {
        const val BATCH_ID = 7L
    }
}

private class FakeBatchAnalysisStore(
    items: List<AnalysisWorkItemEntity>,
) : BatchAnalysisStore {
    private val items = items.associateByTo(linkedMapOf(), AnalysisWorkItemEntity::id)
    private var batchStatus = "QUEUED"

    var recoverCalls = 0
        private set
    var finalStatus: BatchFinalStatus? = null
        private set
    val failedItemIds = mutableListOf<Long>()
    val completedItemIds = mutableListOf<Long>()
    val requestedCursors = mutableListOf<Long>()
    var beforeMarkBatchRunning: (suspend () -> Unit)? = null
    var beforeCompleteItem: (suspend () -> Unit)? = null
    var beforeFinishBatch: (suspend () -> Unit)? = null

    fun status(id: Long): WorkItemStatus = WorkItemStatus.valueOf(items.getValue(id).status)

    override suspend fun recoverInterrupted(batchId: Long) {
        requireBatch(batchId)
        recoverCalls += 1
        items.replaceAll { _, item ->
            if (item.status == WorkItemStatus.RUNNING.name) {
                item.copy(status = WorkItemStatus.RETRYABLE_FAILED.name)
            } else {
                item
            }
        }
    }

    override suspend fun controlState(batchId: Long): BatchControlState {
        requireBatch(batchId)
        return when (batchStatus) {
            "PAUSED" -> BatchControlState.PAUSED
            "CANCELLED" -> BatchControlState.CANCELLED
            else -> BatchControlState.RUNNABLE
        }
    }

    override suspend fun markBatchRunning(batchId: Long): Boolean {
        requireBatch(batchId)
        beforeMarkBatchRunning?.invoke()
        if (batchStatus in setOf("PAUSED", "CANCELLED")) return false
        batchStatus = "RUNNING"
        return true
    }

    override suspend fun nextRunnableItems(
        batchId: Long,
        afterId: Long,
        limit: Int,
    ): List<AnalysisWorkItemEntity> {
        requireBatch(batchId)
        requestedCursors += afterId
        return items.values
            .filter {
                it.id > afterId &&
                    it.status in setOf(
                        WorkItemStatus.PENDING.name,
                        WorkItemStatus.RETRYABLE_FAILED.name,
                    )
            }
            .sortedBy(AnalysisWorkItemEntity::id)
            .take(limit)
    }

    override suspend fun markRunning(itemId: Long): Boolean {
        val item = items.getValue(itemId)
        if (item.status !in setOf(
                WorkItemStatus.PENDING.name,
                WorkItemStatus.RETRYABLE_FAILED.name,
            )
        ) {
            return false
        }
        items[itemId] = item.copy(
            status = WorkItemStatus.RUNNING.name,
            attempts = item.attempts + 1,
        )
        return true
    }

    override suspend fun completeItem(itemId: Long): Boolean {
        beforeCompleteItem?.invoke()
        val item = items.getValue(itemId)
        if (item.status != WorkItemStatus.RUNNING.name) return false
        completedItemIds += itemId
        items[itemId] = item.copy(status = WorkItemStatus.COMPLETED.name)
        return true
    }

    override suspend fun failItem(
        itemId: Long,
        retryable: Boolean,
        message: String,
    ): Boolean {
        val item = items.getValue(itemId)
        if (item.status != WorkItemStatus.RUNNING.name) return false
        failedItemIds += itemId
        items[itemId] = item.copy(
            status = if (retryable) {
                WorkItemStatus.RETRYABLE_FAILED.name
            } else {
                WorkItemStatus.PERMANENT_FAILED.name
            },
            errorMessage = message,
        )
        return true
    }

    override suspend fun failActiveExecution(batchId: Long, message: String): Boolean {
        requireBatch(batchId)
        if (batchStatus != "RUNNING") return false
        items.replaceAll { _, item ->
            if (item.status == WorkItemStatus.RUNNING.name) {
                item.copy(
                    status = WorkItemStatus.RETRYABLE_FAILED.name,
                    errorMessage = message,
                )
            } else {
                item
            }
        }
        batchStatus = BatchFinalStatus.NEEDS_ATTENTION.name
        return true
    }

    override suspend fun snapshot(batchId: Long): BatchAnalysisSnapshot {
        requireBatch(batchId)
        fun count(status: WorkItemStatus): Int = items.values.count { it.status == status.name }
        return BatchAnalysisSnapshot(
            total = items.size,
            completed = count(WorkItemStatus.COMPLETED),
            retryableFailed = count(WorkItemStatus.RETRYABLE_FAILED),
            permanentFailed = count(WorkItemStatus.PERMANENT_FAILED),
            pending = count(WorkItemStatus.PENDING),
            running = count(WorkItemStatus.RUNNING),
            paused = count(WorkItemStatus.PAUSED),
            cancelled = count(WorkItemStatus.CANCELLED),
        )
    }

    override suspend fun finishBatch(batchId: Long, status: BatchFinalStatus): Boolean {
        requireBatch(batchId)
        beforeFinishBatch?.invoke()
        if (batchStatus != "RUNNING") return false
        finalStatus = status
        batchStatus = status.name
        return true
    }

    override suspend fun pauseBatch(batchId: Long) {
        requireBatch(batchId)
        batchStatus = "PAUSED"
        items.replaceAll { _, item ->
            if (item.status in setOf(
                    WorkItemStatus.PENDING.name,
                    WorkItemStatus.RETRYABLE_FAILED.name,
                )
            ) {
                item.copy(status = WorkItemStatus.PAUSED.name)
            } else {
                item
            }
        }
    }

    override suspend fun resumeBatch(batchId: Long) {
        requireBatch(batchId)
        batchStatus = "QUEUED"
        items.replaceAll { _, item ->
            when (item.status) {
                WorkItemStatus.PAUSED.name -> item.copy(status = WorkItemStatus.PENDING.name)
                WorkItemStatus.RUNNING.name -> item.copy(status = WorkItemStatus.RETRYABLE_FAILED.name)
                else -> item
            }
        }
    }

    override suspend fun cancelBatch(batchId: Long) {
        requireBatch(batchId)
        batchStatus = "CANCELLED"
        items.replaceAll { _, item ->
            if (item.status in setOf(
                    WorkItemStatus.PENDING.name,
                    WorkItemStatus.RUNNING.name,
                    WorkItemStatus.PAUSED.name,
                    WorkItemStatus.RETRYABLE_FAILED.name,
                )
            ) {
                item.copy(status = WorkItemStatus.CANCELLED.name)
            } else {
                item
            }
        }
    }

    override suspend fun retryFailures(batchId: Long) {
        requireBatch(batchId)
        batchStatus = "QUEUED"
        items.replaceAll { _, item ->
            if (item.status in setOf(
                    WorkItemStatus.RETRYABLE_FAILED.name,
                    WorkItemStatus.PERMANENT_FAILED.name,
                )
            ) {
                item.copy(status = WorkItemStatus.PENDING.name)
            } else {
                item
            }
        }
    }

    private fun requireBatch(batchId: Long) {
        require(batchId == BATCH_ID)
    }

    private companion object {
        const val BATCH_ID = 7L
    }
}
