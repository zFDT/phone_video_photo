package com.frameyu.app.analysis.batch

import com.frameyu.app.data.db.AnalysisBatchEntity
import com.frameyu.app.data.db.AnalysisWorkItemEntity
import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        val store = FakeCreationStore(existing = existing, media = listOf(media(1L, "image/jpeg")))
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
            store = FakeCreationStore(existing = existing),
            enqueue = { batchId -> enqueued += batchId },
            analyzerVersions = versions(),
        )

        assertEquals(6L, coordinator.openOrCreateBatch())
        assertEquals(listOf(6L), enqueued)
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

    private fun versions(): Map<AnalysisStage, String> = mapOf(
        AnalysisStage.QUALITY to "quality-v1",
        AnalysisStage.CLASSIFICATION to "classification-v1",
        AnalysisStage.EMBEDDING to "embedding-v1",
        AnalysisStage.CONDITIONAL_DETECTION to "detection-v1",
    )

    private class FakeCreationStore(
        private val existing: AnalysisBatchEntity? = null,
        private val media: List<MediaAssetEntity> = emptyList(),
    ) : BatchAnalysisCreationStore {
        var createdBatch: AnalysisBatchEntity? = null
        var createdItems: List<AnalysisWorkItemEntity> = emptyList()

        override suspend fun latestOpenBatch(): AnalysisBatchEntity? = existing

        override suspend fun analyzableMedia(): List<MediaAssetEntity> = media

        override suspend fun createBatch(
            batch: AnalysisBatchEntity,
            items: List<AnalysisWorkItemEntity>,
        ): Long {
            createdBatch = batch
            createdItems = items
            return 9L
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
