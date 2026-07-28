package com.frameyu.app.analysis.batch

import android.database.sqlite.SQLiteException
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
    fun `NEEDS_ATTENTION 不自动重试且显式重试会重置全部失败项`() = runBlocking {
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
        store.retryFailures(BATCH_ID)
        val third = runner.run(BATCH_ID)

        assertEquals(0, first.completed)
        assertEquals(2, first.failed)
        assertEquals(0, second.completed)
        assertEquals(2, second.failed)
        assertEquals(mapOf(1L to 2, 2L to 2), attempts)
        assertEquals(1, third.completed)
        assertEquals(1, third.failed)
        assertEquals(0, third.retryableFailed)
        assertEquals(1, third.permanentFailed)
        assertEquals(WorkItemStatus.COMPLETED, store.status(1L))
        assertEquals(WorkItemStatus.PERMANENT_FAILED, store.status(2L))
        assertEquals(BatchFinalStatus.NEEDS_ATTENTION, store.finalStatus)
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
        val store = FakeBatchAnalysisStore(
            items(
                1L to WorkItemStatus.PENDING,
                2L to WorkItemStatus.PERMANENT_FAILED,
            ),
        )
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )
        store.beforeFinishBatch = {
            store.forceBatchStatus(BatchAnalysisStatus.NEEDS_ATTENTION)
            store.retryFailures(BATCH_ID)
        }

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
    fun `批次仍为 RUNNING 时工作项转换未命中必须抛错`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )
        store.beforeCompleteItem = {
            store.forceItemStatus(1L, WorkItemStatus.PENDING)
        }

        val thrown = runCatching { runner.run(BATCH_ID) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(WorkItemStatus.PENDING, store.status(1L))
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

    @Test
    fun `图片协调器将 SQLite cause chain 提升到 Runner 边界`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val roomError = IllegalStateException(
            "Room 查询失败",
            SQLiteException("数据库文件不可读"),
        )
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = ImageBatchWorkItemCoordinator { throw roomError },
        )

        val thrown = runCatching { runner.run(BATCH_ID) }.exceptionOrNull()

        assertTrue(thrown is BatchAnalysisInfrastructureException)
        assertSame(roomError, thrown?.cause)
        assertTrue(store.failedItemIds.isEmpty())
        assertEquals(WorkItemStatus.RUNNING, store.status(1L))
    }

    @Test
    fun `图片协调器保留包装 IOException 的可重试语义`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = ImageBatchWorkItemCoordinator {
                throw IllegalArgumentException(
                    "解码器包装了媒体读取失败",
                    IOException("媒体读取失败"),
                )
            },
        )

        val result = runner.run(BATCH_ID)

        assertEquals(1, result.retryableFailed)
        assertEquals(0, result.permanentFailed)
        assertEquals(WorkItemStatus.RETRYABLE_FAILED, store.status(1L))
    }

    @Test
    fun `普通模型或内容异常不会误判为基础设施失败`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = ImageBatchWorkItemCoordinator {
                throw IllegalArgumentException("图片内容不符合模型输入")
            },
        )

        val result = runner.run(BATCH_ID)

        assertEquals(0, result.retryableFailed)
        assertEquals(1, result.permanentFailed)
        assertEquals(WorkItemStatus.PERMANENT_FAILED, store.status(1L))
    }

    @Test
    fun `进度使用本地状态转换且只在启动和结束读取权威快照`() = runBlocking {
        val store = FakeBatchAnalysisStore(
            items(
                1L to WorkItemStatus.PENDING,
                2L to WorkItemStatus.RETRYABLE_FAILED,
                3L to WorkItemStatus.PENDING,
            ),
        )
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator { item ->
                when (item.id) {
                    2L -> throw IOException("仍可重试")
                    3L -> throw IllegalArgumentException("内容无效")
                }
            },
        )
        val progress = mutableListOf<BatchAnalysisProgress>()

        val result = runner.run(BATCH_ID, progress::add)

        assertEquals(2, store.snapshotCalls)
        assertEquals(
            listOf(
                0 to 1,
                1 to 1,
                1 to 1,
                1 to 1,
                1 to 1,
                1 to 2,
            ),
            progress.map { it.completed to it.failed },
        )
        assertEquals(1, result.completed)
        assertEquals(1, result.retryableFailed)
        assertEquals(1, result.permanentFailed)
    }

    @Test
    fun `协调器 provider 只在首个真实工作项创建一次`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L, 2L))
        var providerCalls = 0
        val runner = BatchAnalysisRunner(
            store = store,
            coordinatorProvider = {
                providerCalls += 1
                BatchWorkItemCoordinator {}
            },
        )

        runner.run(BATCH_ID)

        assertEquals(1, providerCalls)
    }

    @Test
    fun `空批次和暂停批次不会创建协调器`() = runBlocking {
        var providerCalls = 0
        val provider = {
            providerCalls += 1
            BatchWorkItemCoordinator {}
        }
        val emptyRunner = BatchAnalysisRunner(
            store = FakeBatchAnalysisStore(emptyList()),
            coordinatorProvider = provider,
        )
        val pausedStore = FakeBatchAnalysisStore(items(1L)).apply {
            forceBatchStatus(BatchAnalysisStatus.PAUSED)
        }
        val pausedRunner = BatchAnalysisRunner(
            store = pausedStore,
            coordinatorProvider = provider,
        )

        emptyRunner.run(BATCH_ID)
        pausedRunner.run(BATCH_ID)

        assertEquals(0, providerCalls)
    }

    @Test
    fun `beforeRun 最终失败会收敛尚未启动的 QUEUED 批次`() = runBlocking {
        val store = FakeBatchAnalysisStore(items(1L))
        val runner = BatchAnalysisRunner(
            store = store,
            coordinator = BatchWorkItemCoordinator {},
        )
        val execution = BatchAnalysisExecution(
            runBatch = runner::run,
            failActiveExecution = runner::failActiveExecution,
            logger = BatchExecutionLogger { _, _, _, _ -> },
        )

        val outcome = execution.execute(
            batchId = BATCH_ID,
            runAttemptCount = 2,
            beforeRun = { throw IllegalStateException("首次前台任务启动失败") },
        )

        assertTrue(outcome is BatchExecutionOutcome.Success)
        val result = (outcome as BatchExecutionOutcome.Success).result
        assertEquals(1, result?.retryableFailed)
        assertEquals(BatchAnalysisStatus.NEEDS_ATTENTION, store.batchStatus(BATCH_ID))
        assertEquals(WorkItemStatus.RETRYABLE_FAILED, store.status(1L))
    }

    @Test
    fun `失败收敛条件未命中时按穷举批次状态退场`() = runBlocking {
        val normalExitStatuses = listOf(
            BatchAnalysisStatus.QUEUED,
            BatchAnalysisStatus.PAUSED,
            BatchAnalysisStatus.CANCELLED,
            BatchAnalysisStatus.COMPLETED,
            BatchAnalysisStatus.NEEDS_ATTENTION,
        )
        normalExitStatuses.forEach { status ->
            val store = FakeBatchAnalysisStore(items(1L)).apply {
                forceBatchStatus(status)
                failActiveExecutionResult = false
            }
            val runner = BatchAnalysisRunner(
                store = store,
                coordinator = BatchWorkItemCoordinator {},
            )

            val result = runner.failActiveExecution(BATCH_ID, "状态竞态")

            assertEquals(status == BatchAnalysisStatus.PAUSED, result.paused)
            assertEquals(status == BatchAnalysisStatus.CANCELLED, result.cancelled)
        }

        val runningStore = FakeBatchAnalysisStore(items(1L)).apply {
            forceBatchStatus(BatchAnalysisStatus.RUNNING)
            failActiveExecutionResult = false
        }
        val runningRunner = BatchAnalysisRunner(
            store = runningStore,
            coordinator = BatchWorkItemCoordinator {},
        )

        val thrown = runCatching {
            runningRunner.failActiveExecution(BATCH_ID, "状态不变量损坏")
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
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
    var snapshotCalls = 0
        private set
    var finalStatus: BatchFinalStatus? = null
        private set
    val failedItemIds = mutableListOf<Long>()
    val completedItemIds = mutableListOf<Long>()
    val requestedCursors = mutableListOf<Long>()
    var beforeMarkBatchRunning: (suspend () -> Unit)? = null
    var beforeCompleteItem: (suspend () -> Unit)? = null
    var beforeFinishBatch: (suspend () -> Unit)? = null
    var failActiveExecutionResult: Boolean? = null

    fun status(id: Long): WorkItemStatus = WorkItemStatus.valueOf(items.getValue(id).status)

    fun forceItemStatus(id: Long, status: WorkItemStatus) {
        items[id] = items.getValue(id).copy(status = status.name)
    }

    fun forceBatchStatus(status: BatchAnalysisStatus) {
        batchStatus = status.name
    }

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

    override suspend fun batchStatus(batchId: Long): BatchAnalysisStatus {
        requireBatch(batchId)
        return BatchAnalysisStatus.fromPersisted(batchStatus)
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
        failActiveExecutionResult?.let { return it }
        if (batchStatus !in setOf("QUEUED", "RUNNING")) return false
        items.replaceAll { _, item ->
            if (item.status in setOf(
                    WorkItemStatus.PENDING.name,
                    WorkItemStatus.RUNNING.name,
                    WorkItemStatus.RETRYABLE_FAILED.name,
                )
            ) {
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
        snapshotCalls += 1
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
        if (batchStatus == BatchAnalysisStatus.PAUSED.name) return
        check(batchStatus in setOf(
            BatchAnalysisStatus.QUEUED.name,
            BatchAnalysisStatus.RUNNING.name,
        ))
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
        if (batchStatus == BatchAnalysisStatus.QUEUED.name) return
        check(batchStatus == BatchAnalysisStatus.PAUSED.name)
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
        if (batchStatus == BatchAnalysisStatus.CANCELLED.name) return
        check(batchStatus in setOf(
            BatchAnalysisStatus.QUEUED.name,
            BatchAnalysisStatus.RUNNING.name,
            BatchAnalysisStatus.PAUSED.name,
            BatchAnalysisStatus.NEEDS_ATTENTION.name,
        ))
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
        check(batchStatus == BatchAnalysisStatus.NEEDS_ATTENTION.name)
        check(items.values.any {
            it.status in setOf(
                WorkItemStatus.RETRYABLE_FAILED.name,
                WorkItemStatus.PERMANENT_FAILED.name,
            )
        })
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

    override suspend fun markBatchNeedsAttention(batchId: Long, message: String) {
        requireBatch(batchId)
        if (batchStatus == BatchAnalysisStatus.NEEDS_ATTENTION.name) return
        check(batchStatus in setOf(
            BatchAnalysisStatus.QUEUED.name,
            BatchAnalysisStatus.RUNNING.name,
        ))
        items.replaceAll { _, item ->
            if (item.status in setOf(
                    WorkItemStatus.PENDING.name,
                    WorkItemStatus.RUNNING.name,
                    WorkItemStatus.PAUSED.name,
                )
            ) {
                item.copy(
                    status = WorkItemStatus.RETRYABLE_FAILED.name,
                    errorMessage = message,
                )
            } else {
                item
            }
        }
        batchStatus = BatchAnalysisStatus.NEEDS_ATTENTION.name
    }

    override suspend fun reconcilableBatchIds(): List<Long> =
        if (batchStatus in setOf(
                BatchAnalysisStatus.QUEUED.name,
                BatchAnalysisStatus.RUNNING.name,
            ) &&
            items.values.any {
                it.status !in setOf(
                    WorkItemStatus.COMPLETED.name,
                    WorkItemStatus.CANCELLED.name,
                )
            }
        ) {
            listOf(BATCH_ID)
        } else {
            emptyList()
        }

    private fun requireBatch(batchId: Long) {
        require(batchId == BATCH_ID)
    }

    private companion object {
        const val BATCH_ID = 7L
    }
}
