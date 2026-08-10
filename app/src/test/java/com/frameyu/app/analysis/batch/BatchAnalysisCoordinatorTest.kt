package com.frameyu.app.analysis.batch

import com.frameyu.app.data.db.AnalysisBatchEntity
import com.frameyu.app.data.db.AnalysisWorkItemEntity
import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnalysisCoordinatorTest {
    @Test
    fun `创建批次只包含图片支持阶段并立即入队`() = runBlocking {
        val store = FakeCreationStore(
            media = listOf(media(1L, "image/jpeg"), media(2L, "video/mp4")),
        )
        val enqueued = mutableListOf<Long>()
        val coordinator = BatchAnalysisCoordinator(
            store = store,
            enqueue = { batchId -> enqueued += batchId },
            analyzerVersions = versions(),
            nowMillis = { 123L },
        )

        val batchId = coordinator.openOrCreateBatch()

        assertEquals(9L, batchId)
        assertEquals(listOf(9L), enqueued)
        assertEquals("ALL_IMAGES", store.createdBatch?.scope)
        assertEquals(4, store.createdItems.size)
        assertEquals(
            listOf("QUALITY", "CLASSIFICATION", "EMBEDDING", "CONDITIONAL_DETECTION"),
            store.createdItems.map { it.stage },
        )
        assertTrue(store.createdItems.all { it.mediaAssetId == 1L && it.status == "PENDING" })
    }

    @Test
    fun `已有未结束批次会直接复用且不会重复创建`() = runBlocking {
        val existing = AnalysisBatchEntity(
            id = 5L,
            status = "PAUSED",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        val store = FakeCreationStore(
            existing = existing,
            existingItems = completeItems(existing.id, mediaIds = listOf(1L)),
            media = listOf(media(1L, "image/jpeg")),
        )
        var enqueueCount = 0
        val coordinator = BatchAnalysisCoordinator(
            store = store,
            enqueue = { enqueueCount += 1 },
            analyzerVersions = versions(),
        )

        assertEquals(5L, coordinator.openOrCreateBatch())
        assertEquals(0, enqueueCount)
        assertEquals(null, store.createdBatch)
    }

    @Test
    fun `已有排队批次会复用调度器幂等入队`() = runBlocking {
        val existing = AnalysisBatchEntity(
            id = 6L,
            status = "QUEUED",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        val enqueued = mutableListOf<Long>()
        val coordinator = BatchAnalysisCoordinator(
            store = FakeCreationStore(
                existing = existing,
                existingItems = completeItems(existing.id, mediaIds = listOf(1L)),
            ),
            enqueue = { batchId -> enqueued += batchId },
            analyzerVersions = versions(),
        )

        assertEquals(6L, coordinator.openOrCreateBatch())
        assertEquals(listOf(6L), enqueued)
    }

    @Test
    fun `两个并发调用只创建并入队一次且返回相同批次`() = runBlocking {
        val store = FakeCreationStore(media = listOf(media(1L, "image/jpeg")))
        val firstEnqueueEntered = CompletableDeferred<Unit>()
        val releaseFirstEnqueue = CompletableDeferred<Unit>()
        var enqueueCount = 0
        val coordinator = BatchAnalysisCoordinator(
            store = store,
            enqueue = {
                enqueueCount += 1
                if (enqueueCount == 1) {
                    firstEnqueueEntered.complete(Unit)
                    releaseFirstEnqueue.await()
                }
            },
            analyzerVersions = versions(),
        )

        val first = async { coordinator.openOrCreateBatch() }
        withTimeout(1_000L) { firstEnqueueEntered.await() }
        val second = async { coordinator.openOrCreateBatch() }
        yield()
        releaseFirstEnqueue.complete(Unit)

        val batchIds = withTimeout(1_000L) { listOf(first.await(), second.await()) }

        assertEquals(listOf(9L, 9L), batchIds)
        assertEquals(1, store.createCount)
        assertEquals(1, enqueueCount)
    }

    @Test
    fun `并发入队失败会释放互斥锁且后续调用可以继续`() = runBlocking {
        val store = FakeCreationStore(media = listOf(media(1L, "image/jpeg")))
        val firstEnqueueEntered = CompletableDeferred<Unit>()
        val releaseFirstEnqueue = CompletableDeferred<Unit>()
        var enqueueCount = 0
        var secondEnteredBeforeRelease = false
        val coordinator = BatchAnalysisCoordinator(
            store = store,
            enqueue = {
                enqueueCount += 1
                if (enqueueCount == 1) {
                    firstEnqueueEntered.complete(Unit)
                    releaseFirstEnqueue.await()
                    error("首次入队失败")
                } else {
                    secondEnteredBeforeRelease = !releaseFirstEnqueue.isCompleted
                }
            },
            analyzerVersions = versions(),
        )

        val first = async { runCatching { coordinator.openOrCreateBatch() } }
        withTimeout(1_000L) { firstEnqueueEntered.await() }
        val second = async { coordinator.openOrCreateBatch() }
        yield()
        releaseFirstEnqueue.complete(Unit)

        val firstError = withTimeout(1_000L) { first.await().exceptionOrNull() }
        val secondBatchId = withTimeout(1_000L) { second.await() }

        assertEquals("首次入队失败", firstError?.message)
        assertEquals(9L, secondBatchId)
        assertFalse(secondEnteredBeforeRelease)
        assertEquals(1, store.createCount)
        assertEquals(2, enqueueCount)
    }

    @Test
    fun `入队取消异常保持原样传播`() = runBlocking {
        val cancellation = CancellationException("调用方取消")
        val coordinator = BatchAnalysisCoordinator(
            store = FakeCreationStore(media = listOf(media(1L, "image/jpeg"))),
            enqueue = { throw cancellation },
            analyzerVersions = versions(),
        )

        val thrown = runCatching { coordinator.openOrCreateBatch() }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun `PAUSED 旧版本批次被原子退役后创建新批次`() = runBlocking {
        val existing = AnalysisBatchEntity(
            id = 5L,
            status = "PAUSED",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        val staleItems = completeItems(existing.id, mediaIds = listOf(1L)).map { item ->
            if (item.stage == AnalysisStage.CLASSIFICATION.name) {
                item.copy(analyzerVersion = "classification-old", status = "COMPLETED")
            } else {
                item.copy(status = "PAUSED")
            }
        }
        val store = FakeCreationStore(
            existing = existing,
            existingItems = staleItems,
            media = listOf(media(1L, "image/jpeg")),
        )
        val enqueued = mutableListOf<Long>()
        val coordinator = BatchAnalysisCoordinator(
            store = store,
            enqueue = { enqueued += it },
            analyzerVersions = versions(),
            nowMillis = { 123L },
        )

        val batchId = coordinator.openOrCreateBatch()

        assertEquals(9L, batchId)
        assertEquals(listOf(5L), store.retiredBatchIds)
        assertEquals(1, store.replaceCount)
        assertEquals(listOf(9L), enqueued)
    }

    @Test
    fun `NEEDS_ATTENTION 部分完成旧版本批次不会被复用`() = runBlocking {
        val existing = AnalysisBatchEntity(
            id = 7L,
            status = "NEEDS_ATTENTION",
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        val store = FakeCreationStore(
            existing = existing,
            existingItems = completeItems(existing.id, mediaIds = listOf(1L)).map { item ->
                when (item.stage) {
                    AnalysisStage.QUALITY.name -> item.copy(status = "COMPLETED")
                    AnalysisStage.CONDITIONAL_DETECTION.name -> item.copy(
                        status = "RETRYABLE_FAILED",
                        analyzerVersion = "conditional-old",
                    )
                    else -> item.copy(status = "PERMANENT_FAILED")
                }
            },
            media = listOf(media(1L, "image/jpeg")),
        )
        val coordinator = BatchAnalysisCoordinator(
            store = store,
            enqueue = {},
            analyzerVersions = versions(),
        )

        assertEquals(9L, coordinator.openOrCreateBatch())
        assertEquals(listOf(7L), store.retiredBatchIds)
        assertEquals(1, store.replaceCount)
    }

    @Test
    fun `未知缺失或重复阶段的开放批次都会被退役`() = runBlocking {
        val valid = completeItems(batchId = 8L, mediaIds = listOf(1L))
        val malformedCases = listOf(
            valid + valid.first().copy(id = 99L, stage = "UNKNOWN_STAGE"),
            valid.filterNot { it.stage == AnalysisStage.EMBEDDING.name },
            valid + valid.first().copy(id = 99L),
        )

        malformedCases.forEach { malformedItems ->
            val store = FakeCreationStore(
                existing = AnalysisBatchEntity(
                    id = 8L,
                    status = "PAUSED",
                    createdAtMillis = 1L,
                    updatedAtMillis = 1L,
                ),
                existingItems = malformedItems,
                media = listOf(media(1L, "image/jpeg")),
            )
            val coordinator = BatchAnalysisCoordinator(
                store = store,
                enqueue = {},
                analyzerVersions = versions(),
            )

            assertEquals(9L, coordinator.openOrCreateBatch())
            assertEquals(listOf(8L), store.retiredBatchIds)
        }
    }

    @Test
    fun `新批次入队失败会向调用方传播且保留已创建批次`() = runBlocking {
        val store = FakeCreationStore(media = listOf(media(1L, "image/jpeg")))
        val coordinator = BatchAnalysisCoordinator(
            store = store,
            enqueue = { error("入队失败") },
            analyzerVersions = versions(),
        )

        val error = runCatching { coordinator.openOrCreateBatch() }.exceptionOrNull()

        assertEquals("入队失败", error?.message)
        assertEquals("QUEUED", store.createdBatch?.status)
        assertEquals(4, store.createdItems.size)
    }

    @Test
    fun `没有可分析图片时拒绝创建空批次`() = runBlocking {
        val coordinator = BatchAnalysisCoordinator(
            store = FakeCreationStore(media = listOf(media(2L, "video/mp4"))),
            enqueue = {},
            analyzerVersions = versions(),
        )

        val error = runCatching { coordinator.openOrCreateBatch() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    private fun versions(): Map<AnalysisStage, String> = CURRENT_VERSIONS

    private class FakeCreationStore(
        existing: AnalysisBatchEntity? = null,
        existingItems: List<AnalysisWorkItemEntity> = emptyList(),
        private val media: List<MediaAssetEntity> = emptyList(),
    ) : BatchAnalysisCreationStore {
        private var openBatch = existing
        private val itemsByBatchId = mutableMapOf<Long, List<AnalysisWorkItemEntity>>().apply {
            existing?.let { batch -> put(batch.id, existingItems) }
        }
        var createdBatch: AnalysisBatchEntity? = null
        var createdItems: List<AnalysisWorkItemEntity> = emptyList()
        var createCount = 0
        var replaceCount = 0
        val retiredBatchIds = mutableListOf<Long>()

        override suspend fun latestOpenBatch(): AnalysisBatchEntity? = openBatch

        override suspend fun openBatchItems(batchId: Long): List<AnalysisWorkItemEntity> =
            itemsByBatchId[batchId].orEmpty()

        override suspend fun analyzableMedia(): List<MediaAssetEntity> = media

        override suspend fun createBatch(
            batch: AnalysisBatchEntity,
            items: List<AnalysisWorkItemEntity>,
        ): Long {
            createCount += 1
            createdBatch = batch
            createdItems = items
            openBatch = batch.copy(id = 9L)
            itemsByBatchId[9L] = items.map { it.copy(batchId = 9L) }
            return 9L
        }

        override suspend fun replaceOpenBatch(
            openBatchId: Long,
            batch: AnalysisBatchEntity,
            items: List<AnalysisWorkItemEntity>,
        ): Long {
            replaceCount += 1
            retiredBatchIds += openBatchId
            return createBatch(batch, items)
        }
    }

    private companion object {
        val CURRENT_VERSIONS = mapOf(
            AnalysisStage.QUALITY to "quality-v1",
            AnalysisStage.CLASSIFICATION to "classification-v1",
            AnalysisStage.EMBEDDING to "embedding-v1",
            AnalysisStage.CONDITIONAL_DETECTION to "detection-v1",
        )

        fun completeItems(
            batchId: Long,
            mediaIds: List<Long>,
        ): List<AnalysisWorkItemEntity> = mediaIds.flatMap { mediaId ->
            BatchAnalysisCoordinator.SUPPORTED_IMAGE_STAGES.mapIndexed { index, stage ->
                AnalysisWorkItemEntity(
                    id = mediaId * 10L + index,
                    batchId = batchId,
                    mediaAssetId = mediaId,
                    stage = stage.name,
                    status = WorkItemStatus.PENDING.name,
                    analyzerVersion = CURRENT_VERSIONS.getValue(stage),
                    updatedAtMillis = 1L,
                )
            }
        }
    }

    private fun media(id: Long, mimeType: String) = MediaAssetEntity(
        id = id,
        uri = "content://media/$id",
        mimeType = mimeType,
        takenAtMillis = 0L,
        durationMillis = if (mimeType.startsWith("video/")) 1_000L else 0L,
        width = 100,
        height = 100,
        rotationDegrees = 0,
        sizeBytes = 1L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )
}
