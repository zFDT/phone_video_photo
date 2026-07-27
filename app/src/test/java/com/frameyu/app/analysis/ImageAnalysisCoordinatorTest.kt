package com.frameyu.app.analysis

import com.frameyu.app.analysis.batch.AnalysisStage
import com.frameyu.app.data.db.AnalysisWorkItemEntity
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.data.db.MediaClassificationEntity
import com.frameyu.app.data.db.MediaDetectionEntity
import com.frameyu.app.data.db.MediaFeatureReferenceEntity
import com.frameyu.app.data.db.MediaQualityResultEntity
import com.frameyu.app.storage.FeatureCache
import com.frameyu.app.vision.DetectionObservation
import com.frameyu.app.vision.LabelScore
import java.io.File
import kotlinx.coroutines.CancellationException
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

        imageStages.forEach { stage -> coordinator.analyze(workItem(stage)) }
        imageStages.forEach { stage -> coordinator.analyze(workItem(stage)) }

        assertEquals(1, executor.qualityCalls)
        assertEquals(1, executor.classificationCalls)
        assertEquals(1, executor.embeddingCalls)
        assertEquals(0, executor.detectionCalls)
        assertEquals(listOf(0, 1), store.classifications.values.single().map { it.rank })
        assertTrue(store.detections.isEmpty())
        assertEquals(1, store.qualityResults.size)
        assertEquals(1, store.featureReferences.size)
    }

    @Test
    fun `只更换对应模型 SHA 或质量版本时只重跑对应阶段`() = runBlocking {
        val store = FakeImageAnalysisStore(image())
        val executor = FakeImageAnalysisStageExecutor().apply {
            shouldDetectResult = true
            detectionResult = listOf(
                DetectionObservation("stage", 0.7f, 0.1f, 0.2f, 0.8f, 0.9f),
            )
        }
        val base = coordinator(store, executor)
        imageStages.forEach { stage -> base.analyze(workItem(stage)) }
        base.analyze(workItem(AnalysisStage.CONDITIONAL_DETECTION))
        assertEquals(1, executor.detectionCalls)

        coordinator(
            store,
            executor,
            modelShas.copy(classification = changedClassificationSha),
        ).analyze(workItem(AnalysisStage.CLASSIFICATION))
        assertEquals(2, executor.classificationCalls)
        assertEquals(1, executor.embeddingCalls)
        assertEquals(1, executor.detectionCalls)

        coordinator(
            store,
            executor,
            modelShas.copy(embedding = changedEmbeddingSha),
        ).analyze(workItem(AnalysisStage.EMBEDDING))
        assertEquals(2, executor.embeddingCalls)
        assertEquals(2, executor.classificationCalls)
        assertEquals(1, executor.detectionCalls)

        coordinator(
            store,
            executor,
            modelShas.copy(detection = changedDetectionSha),
        ).analyze(workItem(AnalysisStage.CONDITIONAL_DETECTION))
        assertEquals(2, executor.detectionCalls)
        assertEquals(listOf(0), store.detections.values.last().map { it.rank })

        base.analyze(workItem(AnalysisStage.QUALITY, analyzerVersion = "quality-v2"))
        assertEquals(2, executor.qualityCalls)
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
    ) = ImageAnalysisCoordinator(
        store = store,
        featureCache = cache,
        stageExecutor = executor,
        modelShas = shas,
    )

    private fun workItem(
        stage: AnalysisStage,
        analyzerVersion: String = "quality-v1",
    ) = AnalysisWorkItemEntity(
        id = stage.ordinal.toLong() + 1L,
        batchId = 1L,
        mediaAssetId = imageId,
        stage = stage.name,
        status = "RUNNING",
        analyzerVersion = analyzerVersion,
        updatedAtMillis = 1L,
    )

    private fun image(
        id: Long = imageId,
        mimeType: String = "image/jpeg",
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
        lastModifiedMillis = 1L,
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

private class FakeImageAnalysisStore(initialAsset: MediaAssetEntity) : ImageAnalysisStore {
    val assets = mutableMapOf(initialAsset.id to initialAsset)
    val classifications = linkedMapOf<ResultKey, List<MediaClassificationEntity>>()
    val detections = linkedMapOf<ResultKey, List<MediaDetectionEntity>>()
    val featureReferences = linkedMapOf<ResultKey, MediaFeatureReferenceEntity>()
    val qualityResults = linkedMapOf<ResultKey, MediaQualityResultEntity>()

    override suspend fun getMedia(mediaAssetId: Long): MediaAssetEntity? = assets[mediaAssetId]

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
        classifications[ResultKey(mediaAssetId, timestampMillis, modelSha256)] = results
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
        detections[ResultKey(mediaAssetId, timestampMillis, modelSha256)] = results
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
    var detectionCalls = 0
    var shouldDetectResult = false
    var classificationError: Throwable? = null
    var embeddingError: Throwable? = null
    var detectionError: Throwable? = null
    val embeddingResult = floatArrayOf(0.1f, 0.2f, 0.3f)
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
        return listOf(
            LabelScore("stage", 0.8f),
            LabelScore("concert", 0.6f),
        )
    }

    override suspend fun embed(media: MediaAssetEntity): FloatArray {
        embeddingCalls++
        embeddingError?.let { throw it }
        return embeddingResult.copyOf()
    }

    override fun shouldDetect(
        media: MediaAssetEntity,
        classifications: List<LabelScore>,
    ): Boolean = shouldDetectResult

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
