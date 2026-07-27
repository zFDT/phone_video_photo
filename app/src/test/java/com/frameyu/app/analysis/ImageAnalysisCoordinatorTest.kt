package com.frameyu.app.analysis

import com.frameyu.app.analysis.batch.AnalysisStage
import com.frameyu.app.data.db.AnalysisWorkItemEntity
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.data.db.MediaClassificationEntity
import com.frameyu.app.data.db.MediaDetectionEntity
import com.frameyu.app.data.db.MediaFeatureReferenceEntity
import com.frameyu.app.data.db.MediaQualityResultEntity
import com.frameyu.app.storage.AtomicReplacingFeatureCacheMover
import com.frameyu.app.storage.FeatureCache
import com.frameyu.app.storage.FeatureCacheInputStreamOpener
import com.frameyu.app.storage.FeatureCacheMover
import com.frameyu.app.vision.DetectionObservation
import com.frameyu.app.vision.LabelScore
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageAnalysisCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `四个阶段结果可复用且策略不触发时不调用检测器`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor().apply {
            shouldDetectResult = false
        }
        val coordinator = coordinator(store, executor)

        val firstItems = imageStages.map { stage -> workItem(stage) }
        firstItems.forEach { item ->
            coordinator.analyze(item)
            store.markCompleted(item)
        }
        imageStages.forEach { stage ->
            coordinator.analyze(workItem(stage, id = stage.ordinal + 101L, batchId = 2L))
        }

        assertEquals(1, executor.qualityCalls)
        assertEquals(1, executor.classificationCalls)
        assertEquals(1, executor.embeddingCalls)
        assertEquals(1, executor.policyCalls)
        assertEquals(0, executor.detectionCalls)
        assertEquals(listOf(0, 1), store.classifications.values.single().map { it.rank })
        assertTrue(store.detections.isEmpty())
        assertEquals(1, store.qualityResults.size)
        assertEquals(1, store.featureReferences.size)
    }

    @Test
    fun `空分类由同版本已完成工作项复用且新版本重新分析`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor().apply {
            classificationResult = emptyList()
        }
        val coordinator = coordinator(store, executor)
        val first = workItem(
            AnalysisStage.CLASSIFICATION,
            analyzerVersion = "classification-empty-v1",
            id = 11L,
        )

        coordinator.analyze(first)
        store.markCompleted(first)
        coordinator.analyze(first.copy(id = 12L, batchId = 2L))
        coordinator.analyze(
            first.copy(
                id = 13L,
                batchId = 3L,
                analyzerVersion = "classification-empty-v2",
            ),
        )

        assertEquals(2, executor.classificationCalls)
        assertTrue(store.classifications.isEmpty())
    }

    @Test
    fun `空检测由同版本已完成工作项复用`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor().apply {
            shouldDetectResult = true
            detectionResult = emptyList()
        }
        val coordinator = coordinator(store, executor)
        val classification = workItem(AnalysisStage.CLASSIFICATION)
        coordinator.analyze(classification)
        store.markCompleted(classification)
        val first = workItem(
            AnalysisStage.CONDITIONAL_DETECTION,
            analyzerVersion = "detection-empty-v1",
            id = 21L,
        )

        coordinator.analyze(first)
        store.markCompleted(first)
        coordinator.analyze(first.copy(id = 22L, batchId = 2L))

        assertEquals(1, executor.policyCalls)
        assertEquals(1, executor.detectionCalls)
        assertTrue(store.detections.isEmpty())
    }

    @Test
    fun `检测策略版本变化时重新评估并清除同模型旧结果`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor().apply {
            shouldDetectResult = true
            detectionResult = listOf(
                DetectionObservation("stage", 0.7f, 0.1f, 0.2f, 0.8f, 0.9f),
            )
        }
        val coordinator = coordinator(store, executor)
        val classification = workItem(AnalysisStage.CLASSIFICATION)
        coordinator.analyze(classification)
        store.markCompleted(classification)
        val first = workItem(
            AnalysisStage.CONDITIONAL_DETECTION,
            analyzerVersion = "detection-policy-v1",
            id = 31L,
        )
        coordinator.analyze(first)
        store.markCompleted(first)

        executor.shouldDetectResult = false
        coordinator.analyze(
            first.copy(
                id = 32L,
                batchId = 2L,
                analyzerVersion = "detection-policy-v2",
            ),
        )

        assertEquals(2, executor.policyCalls)
        assertEquals(1, executor.detectionCalls)
        assertTrue(store.detections.isEmpty())
    }

    @Test
    fun `只更换对应模型 SHA 和阶段版本时只重跑对应阶段`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor().apply {
            shouldDetectResult = true
            detectionResult = listOf(
                DetectionObservation("stage", 0.7f, 0.1f, 0.2f, 0.8f, 0.9f),
            )
        }
        val base = coordinator(store, executor)
        imageStages.map { stage -> workItem(stage) }.forEach { item ->
            base.analyze(item)
            store.markCompleted(item)
        }
        assertEquals(1, executor.detectionCalls)

        coordinator(
            store,
            executor,
            modelShas.copy(classification = changedClassificationSha),
        ).analyze(
            workItem(
                AnalysisStage.CLASSIFICATION,
                analyzerVersion = "classification-model-v2",
                id = 101L,
            ),
        )
        assertEquals(2, executor.classificationCalls)
        assertEquals(1, executor.embeddingCalls)
        assertEquals(1, executor.detectionCalls)

        coordinator(
            store,
            executor,
            modelShas.copy(embedding = changedEmbeddingSha),
        ).analyze(workItem(AnalysisStage.EMBEDDING, id = 102L))
        assertEquals(2, executor.embeddingCalls)
        assertEquals(2, executor.classificationCalls)
        assertEquals(1, executor.detectionCalls)

        coordinator(
            store,
            executor,
            modelShas.copy(detection = changedDetectionSha),
        ).analyze(
            workItem(
                AnalysisStage.CONDITIONAL_DETECTION,
                analyzerVersion = "detection-model-v2",
                id = 103L,
            ),
        )
        assertEquals(2, executor.detectionCalls)
        assertEquals(listOf(0), store.detections.values.last().map { it.rank })

        base.analyze(workItem(AnalysisStage.QUALITY, analyzerVersion = "quality-v2", id = 104L))
        assertEquals(2, executor.qualityCalls)
    }

    @Test
    fun `媒体修改时间变新后四个阶段都重新分析`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor().apply {
            shouldDetectResult = true
            detectionResult = listOf(
                DetectionObservation("stage", 0.7f, 0.1f, 0.2f, 0.8f, 0.9f),
            )
        }
        val coordinator = coordinator(store, executor)
        val firstItems = imageStages.map { stage -> workItem(stage) }
        firstItems.forEach { item ->
            coordinator.analyze(item)
            store.markCompleted(item, completedAtMillis = 10L)
        }

        store.assets[imageId] = image(lastModifiedMillis = 20L)
        imageStages.forEach { stage ->
            coordinator.analyze(workItem(stage, id = stage.ordinal + 201L, batchId = 2L))
        }

        assertEquals(2, executor.qualityCalls)
        assertEquals(2, executor.classificationCalls)
        assertEquals(2, executor.embeddingCalls)
        assertEquals(2, executor.policyCalls)
        assertEquals(2, executor.detectionCalls)
    }

    @Test
    fun `新分类完成后相同检测版本也会重新评估`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor().apply {
            shouldDetectResult = true
            detectionResult = listOf(
                DetectionObservation("stage", 0.7f, 0.1f, 0.2f, 0.8f, 0.9f),
            )
        }
        val coordinator = coordinator(store, executor)
        val classificationV1 = workItem(AnalysisStage.CLASSIFICATION, id = 301L)
        coordinator.analyze(classificationV1)
        store.markCompleted(classificationV1, completedAtMillis = 10L)
        val detectionV1 = workItem(AnalysisStage.CONDITIONAL_DETECTION, id = 302L)
        coordinator.analyze(detectionV1)
        store.markCompleted(detectionV1, completedAtMillis = 20L)

        coordinator.analyze(detectionV1.copy(id = 303L, batchId = 2L))
        val classificationV2 = workItem(
            AnalysisStage.CLASSIFICATION,
            analyzerVersion = "classification-v2",
            id = 304L,
            batchId = 3L,
        )
        coordinator.analyze(classificationV2)
        store.markCompleted(classificationV2, completedAtMillis = 30L)
        coordinator.analyze(detectionV1.copy(id = 305L, batchId = 4L))

        assertEquals(2, executor.classificationCalls)
        assertEquals(2, executor.policyCalls)
        assertEquals(2, executor.detectionCalls)
    }

    @Test
    fun `当前工作项的完成标记必须被排除`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor()
        val coordinator = coordinator(store, executor)
        val item = workItem(AnalysisStage.QUALITY, id = 401L)

        coordinator.analyze(item)
        store.markCompleted(item, completedAtMillis = 10L)
        coordinator.analyze(item)

        assertEquals(2, executor.qualityCalls)
    }

    @Test
    fun `资源在 IO 线程获取关闭并在推理线程使用`() = runBlocking {
        val ioDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "task6-io")
        }.asCoroutineDispatcher()
        val inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "task6-inference")
        }.asCoroutineDispatcher()
        var acquireThread = ""
        var useThread = ""
        var closeThread = ""

        try {
            val result = withDispatchedCloseable(
                ioDispatcher = ioDispatcher,
                inferenceDispatcher = inferenceDispatcher,
                acquire = {
                    acquireThread = Thread.currentThread().name
                    RecordingCloseable { closeThread = Thread.currentThread().name }
                },
                block = {
                    useThread = Thread.currentThread().name
                    "完成"
                },
            )

            assertEquals("完成", result)
            assertTrue(acquireThread.startsWith("task6-io"))
            assertTrue(useThread.startsWith("task6-inference"))
            assertTrue(closeThread.startsWith("task6-io"))
        } finally {
            ioDispatcher.close()
            inferenceDispatcher.close()
        }
    }

    @Test
    fun `推理取消或异常时仍关闭已获取资源`() = runBlocking {
        val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val inferenceDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        var cancellationClosed = false
        var failureClosed = false

        try {
            assertSuspendThrows<CancellationException> {
                withDispatchedCloseable(
                    ioDispatcher,
                    inferenceDispatcher,
                    { RecordingCloseable { cancellationClosed = true } },
                ) {
                    throw CancellationException("测试取消")
                }
            }
            assertSuspendThrows<IllegalStateException> {
                withDispatchedCloseable(
                    ioDispatcher,
                    inferenceDispatcher,
                    { RecordingCloseable { failureClosed = true } },
                ) {
                    error("测试异常")
                }
            }
        } finally {
            ioDispatcher.close()
            inferenceDispatcher.close()
        }

        assertTrue(cancellationClosed)
        assertTrue(failureClosed)
    }

    @Test
    fun `特征缓存读写切换到缓存线程`() = runBlocking {
        val cacheDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "task6-cache")
        }.asCoroutineDispatcher()
        var writeThread = ""
        var readThread = ""
        val delegateMover = AtomicReplacingFeatureCacheMover()
        val cache = FeatureCache(
            temporaryFolder.root,
            FeatureCacheMover { source, target ->
                writeThread = Thread.currentThread().name
                delegateMover.move(source, target)
            },
            FeatureCacheInputStreamOpener { file ->
                readThread = Thread.currentThread().name
                FileInputStream(file)
            },
        )
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor()
        val coordinator = coordinator(
            store = store,
            executor = executor,
            cache = cache,
            cacheDispatcher = cacheDispatcher,
        )
        val first = workItem(AnalysisStage.EMBEDDING, id = 501L)

        try {
            coordinator.analyze(first)
            store.markCompleted(first, completedAtMillis = 10L)
            coordinator.analyze(first.copy(id = 502L, batchId = 2L))
        } finally {
            cacheDispatcher.close()
        }

        assertTrue(writeThread.startsWith("task6-cache"))
        assertTrue(readThread.startsWith("task6-cache"))
        assertEquals(1, executor.embeddingCalls)
    }

    @Test
    fun `Room 特征引用存在但缓存损坏时重新计算向量`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor()
        val cache = FeatureCache(temporaryFolder.root)
        val coordinator = coordinator(store, executor, cache = cache)
        val item = workItem(AnalysisStage.EMBEDDING)

        coordinator.analyze(item)
        val reference = store.featureReferences.values.single()
        File(reference.cachePath).writeBytes(byteArrayOf(1, 2, 3))
        coordinator.analyze(item)

        assertEquals(2, executor.embeddingCalls)
        val repaired = store.featureReferences.values.single()
        assertNotNull(cache.read(modelShas.embedding, imageId))
        assertArrayEquals(executor.embeddingResult, cache.read(modelShas.embedding, imageId), 0f)
        assertEquals(executor.embeddingResult.size, repaired.dimension)
    }

    @Test
    fun `阶段抛错不提交当前结果且不影响此前已提交阶段`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor()
        val coordinator = coordinator(store, executor)

        coordinator.analyze(workItem(AnalysisStage.QUALITY))
        executor.classificationError = IllegalStateException("分类失败")
        assertSuspendThrows<IllegalStateException> {
            coordinator.analyze(workItem(AnalysisStage.CLASSIFICATION))
        }

        assertEquals(1, store.qualityResults.size)
        assertTrue(store.classifications.isEmpty())
    }

    @Test
    fun `CancellationException 原样传播且不提交结果`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val cancellation = CancellationException("用户取消")
        val executor = FakeImageAnalysisStageExecutor().apply {
            embeddingError = cancellation
        }
        val coordinator = coordinator(store, executor)

        val thrown = assertSuspendThrows<CancellationException> {
            coordinator.analyze(workItem(AnalysisStage.EMBEDDING))
        }

        assertSame(cancellation, thrown)
        assertTrue(store.featureReferences.isEmpty())
    }

    @Test
    fun `媒体阶段版本和模型 SHA 输入不合法时明确失败`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val coordinator = coordinator(store, FakeImageAnalysisStageExecutor())

        assertSuspendThrows<IllegalArgumentException> {
            coordinator.analyze(workItem(AnalysisStage.QUALITY).copy(mediaAssetId = 999L))
        }
        store.assets[videoId] = image(id = videoId, mimeType = "video/mp4")
        assertSuspendThrows<IllegalArgumentException> {
            coordinator.analyze(workItem(AnalysisStage.QUALITY).copy(mediaAssetId = videoId))
        }
        assertSuspendThrows<IllegalArgumentException> {
            coordinator.analyze(workItem(AnalysisStage.QUALITY).copy(stage = "UNKNOWN"))
        }
        assertSuspendThrows<IllegalArgumentException> {
            coordinator.analyze(workItem(AnalysisStage.QUALITY, analyzerVersion = "   "))
        }
        assertThrows<IllegalArgumentException> {
            ImageAnalysisModelShas("bad", modelShas.embedding, modelShas.detection)
        }
        Unit
    }

    private fun coordinator(
        store: FakeImageAnalysisStore,
        executor: FakeImageAnalysisStageExecutor,
        shas: ImageAnalysisModelShas = modelShas,
        cache: FeatureCache = FeatureCache(temporaryFolder.root),
        cacheDispatcher: kotlinx.coroutines.CoroutineDispatcher =
            kotlinx.coroutines.Dispatchers.IO,
    ) = ImageAnalysisCoordinator(
        store = store,
        featureCache = cache,
        stageExecutor = executor,
        modelShas = shas,
        cacheDispatcher = cacheDispatcher,
    )

    private fun workItem(
        stage: AnalysisStage,
        analyzerVersion: String = "${stage.name.lowercase()}-v1",
        id: Long = stage.ordinal.toLong() + 1L,
        batchId: Long = 1L,
    ) = AnalysisWorkItemEntity(
        id = id,
        batchId = batchId,
        mediaAssetId = imageId,
        stage = stage.name,
        status = "RUNNING",
        analyzerVersion = analyzerVersion,
        updatedAtMillis = 1L,
    )

    private fun image(
        id: Long = imageId,
        mimeType: String = "image/jpeg",
        lastModifiedMillis: Long = 1L,
    ) = MediaAssetEntity(
        id = id,
        uri = "content://media/images/$id",
        mimeType = mimeType,
        takenAtMillis = 1L,
        durationMillis = 0L,
        width = 100,
        height = 80,
        rotationDegrees = 0,
        sizeBytes = 1_024L,
        isHdr = false,
        relativePath = "DCIM",
        lastModifiedMillis = lastModifiedMillis,
    )

    private suspend inline fun <reified T : Throwable> assertSuspendThrows(
        noinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("预期 ${T::class.java.simpleName}，实际 ${error::class.java.simpleName}", error is T)
            return error as T
        }
        throw AssertionError("预期抛出 ${T::class.java.simpleName}")
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("预期 ${T::class.java.simpleName}，实际 ${error::class.java.simpleName}", error is T)
            return error as T
        }
        throw AssertionError("预期抛出 ${T::class.java.simpleName}")
    }

    private companion object {
        const val imageId = 42L
        const val videoId = 43L
        val classificationSha = "a".repeat(64)
        val embeddingSha = "b".repeat(64)
        val detectionSha = "c".repeat(64)
        val changedClassificationSha = "d".repeat(64)
        val changedEmbeddingSha = "e".repeat(64)
        val changedDetectionSha = "f".repeat(64)
        val modelShas = ImageAnalysisModelShas(classificationSha, embeddingSha, detectionSha)
        val imageStages = listOf(
            AnalysisStage.QUALITY,
            AnalysisStage.CLASSIFICATION,
            AnalysisStage.EMBEDDING,
            AnalysisStage.CONDITIONAL_DETECTION,
        )
    }
}

private class RecordingCloseable(
    private val onClose: () -> Unit,
) : Closeable {
    override fun close() = onClose()
}

private class FakeImageAnalysisStore(initialAsset: MediaAssetEntity) : ImageAnalysisStore {
    val assets = mutableMapOf(initialAsset.id to initialAsset)
    val classifications = linkedMapOf<ResultKey, List<MediaClassificationEntity>>()
    val detections = linkedMapOf<ResultKey, List<MediaDetectionEntity>>()
    val featureReferences = linkedMapOf<ResultKey, MediaFeatureReferenceEntity>()
    val qualityResults = linkedMapOf<ResultKey, MediaQualityResultEntity>()
    private val completedStages = mutableListOf<CompletedStage>()

    fun markCompleted(
        item: AnalysisWorkItemEntity,
        completedAtMillis: Long = item.updatedAtMillis,
    ) {
        completedStages += CompletedStage(
            itemId = item.id,
            mediaAssetId = item.mediaAssetId,
            stage = item.stage,
            analyzerVersion = item.analyzerVersion,
            completedAtMillis = completedAtMillis,
        )
    }

    override suspend fun getMedia(mediaAssetId: Long): MediaAssetEntity? = assets[mediaAssetId]

    override suspend fun latestCompletedStageAt(
        mediaAssetId: Long,
        stage: String,
        analyzerVersion: String,
        excludingItemId: Long,
    ): Long? = completedStages.asSequence()
        .filter { completed ->
            completed.itemId != excludingItemId &&
            completed.mediaAssetId == mediaAssetId &&
            completed.stage == stage &&
            completed.analyzerVersion == analyzerVersion
        }
        .maxOfOrNull(CompletedStage::completedAtMillis)

    override suspend fun latestCompletedClassificationAt(
        mediaAssetId: Long,
        excludingItemId: Long,
    ): Long? = completedStages.asSequence()
        .filter { completed ->
            completed.itemId != excludingItemId &&
                completed.mediaAssetId == mediaAssetId &&
                completed.stage == AnalysisStage.CLASSIFICATION.name
        }
        .maxOfOrNull(CompletedStage::completedAtMillis)

    override suspend fun getClassifications(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): List<MediaClassificationEntity> =
        classifications[ResultKey(mediaAssetId, timestampMillis, modelSha256)].orEmpty()

    override suspend fun upsertClassifications(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        results: List<MediaClassificationEntity>,
    ) {
        val key = ResultKey(mediaAssetId, timestampMillis, modelSha256)
        if (results.isEmpty()) {
            classifications.remove(key)
        } else {
            classifications[key] = results
        }
    }

    override suspend fun getDetections(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): List<MediaDetectionEntity> =
        detections[ResultKey(mediaAssetId, timestampMillis, modelSha256)].orEmpty()

    override suspend fun upsertDetections(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        results: List<MediaDetectionEntity>,
    ) {
        val key = ResultKey(mediaAssetId, timestampMillis, modelSha256)
        if (results.isEmpty()) {
            detections.remove(key)
        } else {
            detections[key] = results
        }
    }

    override suspend fun getFeatureReference(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): MediaFeatureReferenceEntity? =
        featureReferences[ResultKey(mediaAssetId, timestampMillis, modelSha256)]

    override suspend fun upsertFeatureReference(result: MediaFeatureReferenceEntity) {
        featureReferences[ResultKey(result.mediaAssetId, result.timestampMillis, result.modelSha256)] = result
    }

    override suspend fun getQualityResult(
        mediaAssetId: Long,
        timestampMillis: Long,
        analyzerVersion: String,
    ): MediaQualityResultEntity? =
        qualityResults[ResultKey(mediaAssetId, timestampMillis, analyzerVersion)]

    override suspend fun upsertQualityResult(result: MediaQualityResultEntity) {
        qualityResults[ResultKey(result.mediaAssetId, result.timestampMillis, result.analyzerVersion)] = result
    }
}

private class FakeImageAnalysisStageExecutor : ImageAnalysisStageExecutor {
    var qualityCalls = 0
    var classificationCalls = 0
    var embeddingCalls = 0
    var policyCalls = 0
    var detectionCalls = 0
    var shouldDetectResult = false
    var classificationError: Throwable? = null
    var embeddingError: Throwable? = null
    var detectionError: Throwable? = null
    val embeddingResult = floatArrayOf(0.1f, 0.2f, 0.3f)
    var classificationResult = listOf(
        LabelScore("stage", 0.8f),
        LabelScore("concert", 0.6f),
    )
    var detectionResult = emptyList<DetectionObservation>()

    override suspend fun analyzeQuality(media: MediaAssetEntity): ImageQualityScores {
        qualityCalls++
        return ImageQualityScores(
            total = 0.8f,
            sharpness = 0.7f,
            exposure = 0.6f,
            noise = 0.5f,
            composition = 0.5f,
        )
    }

    override suspend fun classify(media: MediaAssetEntity): List<LabelScore> {
        classificationCalls++
        classificationError?.let { throw it }
        return classificationResult
    }

    override suspend fun embed(media: MediaAssetEntity): FloatArray {
        embeddingCalls++
        embeddingError?.let { throw it }
        return embeddingResult.copyOf()
    }

    override fun shouldDetect(
        media: MediaAssetEntity,
        classifications: List<LabelScore>,
    ): Boolean {
        policyCalls++
        return shouldDetectResult
    }

    override suspend fun detect(media: MediaAssetEntity): List<DetectionObservation> {
        detectionCalls++
        detectionError?.let { throw it }
        return detectionResult
    }
}

private data class ResultKey(
    val mediaAssetId: Long,
    val timestampMillis: Long,
    val version: String,
)

private data class CompletedStage(
    val itemId: Long,
    val mediaAssetId: Long,
    val stage: String,
    val analyzerVersion: String,
    val completedAtMillis: Long,
)
