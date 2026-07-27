package com.frameyu.app.analysis

import android.graphics.Bitmap
import android.net.Uri
import com.frameyu.app.analysis.batch.AnalysisStage
import com.frameyu.app.data.db.AnalysisWorkItemEntity
import com.frameyu.app.data.db.BatchAnalysisDao
import com.frameyu.app.data.db.MediaAssetDao
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.data.db.MediaClassificationEntity
import com.frameyu.app.data.db.MediaDetectionEntity
import com.frameyu.app.data.db.MediaFeatureReferenceEntity
import com.frameyu.app.data.db.MediaQualityResultEntity
import com.frameyu.app.storage.FeatureCache
import com.frameyu.app.vision.DetectionObservation
import com.frameyu.app.vision.ImageClassifierAnalyzer
import com.frameyu.app.vision.ImageEmbeddingAnalyzer
import com.frameyu.app.vision.LabelScore
import com.frameyu.app.vision.MediaImageDecoder
import com.frameyu.app.vision.ObjectDetectionPolicy
import com.frameyu.app.vision.ObjectDetectorAnalyzer
import com.frameyu.app.vision.ThumbnailInput
import java.io.Closeable
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 图片分析所使用的三个模型 SHA-256。 */
data class ImageAnalysisModelShas(
    val classification: String,
    val embedding: String,
    val detection: String,
) {
    init {
        validateModelSha(classification, "分类")
        validateModelSha(embedding, "向量")
        validateModelSha(detection, "目标检测")
    }

    internal fun normalized(): ImageAnalysisModelShas = ImageAnalysisModelShas(
        classification = classification.lowercase(Locale.ROOT),
        embedding = embedding.lowercase(Locale.ROOT),
        detection = detection.lowercase(Locale.ROOT),
    )
}

/** 持久化边界；JVM 测试可使用内存实现，生产环境使用 RoomImageAnalysisStore。 */
interface ImageAnalysisStore {
    suspend fun getMedia(mediaAssetId: Long): MediaAssetEntity?

    suspend fun latestCompletedStageAt(
        mediaAssetId: Long,
        stage: String,
        analyzerVersion: String,
        excludingItemId: Long,
    ): Long?

    suspend fun latestCompletedClassificationAt(
        mediaAssetId: Long,
        excludingItemId: Long,
    ): Long?

    suspend fun getClassifications(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): List<MediaClassificationEntity>

    suspend fun upsertClassifications(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        results: List<MediaClassificationEntity>,
    )

    suspend fun getDetections(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): List<MediaDetectionEntity>

    suspend fun upsertDetections(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        results: List<MediaDetectionEntity>,
    )

    suspend fun getFeatureReference(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): MediaFeatureReferenceEntity?

    suspend fun upsertFeatureReference(result: MediaFeatureReferenceEntity)

    suspend fun getQualityResult(
        mediaAssetId: Long,
        timestampMillis: Long,
        analyzerVersion: String,
    ): MediaQualityResultEntity?

    suspend fun upsertQualityResult(result: MediaQualityResultEntity)
}

/** 对现有 DAO 的薄适配，不改变工作项生命周期。 */
class RoomImageAnalysisStore(
    private val mediaAssetDao: MediaAssetDao,
    private val batchAnalysisDao: BatchAnalysisDao,
) : ImageAnalysisStore {
    override suspend fun getMedia(mediaAssetId: Long): MediaAssetEntity? =
        mediaAssetDao.getById(mediaAssetId)

    override suspend fun latestCompletedStageAt(
        mediaAssetId: Long,
        stage: String,
        analyzerVersion: String,
        excludingItemId: Long,
    ): Long? = batchAnalysisDao.latestCompletedStageAt(
        mediaAssetId,
        stage,
        analyzerVersion,
        excludingItemId,
    )

    override suspend fun latestCompletedClassificationAt(
        mediaAssetId: Long,
        excludingItemId: Long,
    ): Long? = batchAnalysisDao.latestCompletedClassificationAt(
        mediaAssetId,
        excludingItemId,
    )

    override suspend fun getClassifications(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): List<MediaClassificationEntity> =
        batchAnalysisDao.getClassifications(mediaAssetId, timestampMillis, modelSha256)

    override suspend fun upsertClassifications(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        results: List<MediaClassificationEntity>,
    ) {
        batchAnalysisDao.upsertClassifications(mediaAssetId, timestampMillis, modelSha256, results)
    }

    override suspend fun getDetections(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): List<MediaDetectionEntity> =
        batchAnalysisDao.getDetections(mediaAssetId, timestampMillis, modelSha256)

    override suspend fun upsertDetections(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        results: List<MediaDetectionEntity>,
    ) {
        batchAnalysisDao.upsertDetections(mediaAssetId, timestampMillis, modelSha256, results)
    }

    override suspend fun getFeatureReference(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): MediaFeatureReferenceEntity? =
        batchAnalysisDao.getFeatureReference(mediaAssetId, timestampMillis, modelSha256)

    override suspend fun upsertFeatureReference(result: MediaFeatureReferenceEntity) {
        batchAnalysisDao.upsertFeatureReference(result)
    }

    override suspend fun getQualityResult(
        mediaAssetId: Long,
        timestampMillis: Long,
        analyzerVersion: String,
    ): MediaQualityResultEntity? =
        batchAnalysisDao.getQualityResult(mediaAssetId, timestampMillis, analyzerVersion)

    override suspend fun upsertQualityResult(result: MediaQualityResultEntity) {
        batchAnalysisDao.upsertQualityResult(result)
    }
}

/** 已归一化到 0 到 1 的图片质量分项。 */
data class ImageQualityScores(
    val total: Float,
    val sharpness: Float,
    val exposure: Float,
    val noise: Float,
    val composition: Float,
) {
    init {
        listOf(total, sharpness, exposure, noise, composition).forEach { score ->
            require(score.isFinite() && score in 0f..1f) { "图片质量分数必须是 0 到 1 之间的有限数" }
        }
    }
}

/** 把 Android 解码和具体模型调用隔离在可替换边界之后。 */
interface ImageAnalysisStageExecutor {
    suspend fun analyzeQuality(media: MediaAssetEntity): ImageQualityScores

    suspend fun classify(media: MediaAssetEntity): List<LabelScore>

    suspend fun embed(media: MediaAssetEntity): FloatArray

    fun shouldDetect(
        media: MediaAssetEntity,
        classifications: List<LabelScore>,
    ): Boolean

    suspend fun detect(media: MediaAssetEntity): List<DetectionObservation>
}

/**
 * 单张图片、单个阶段的协调器。
 * 工作项 RUNNING/COMPLETED/FAILED 状态由批量 Worker 管理，本类只提交阶段结果。
 */
class ImageAnalysisCoordinator(
    private val store: ImageAnalysisStore,
    private val featureCache: FeatureCache,
    private val stageExecutor: ImageAnalysisStageExecutor,
    modelShas: ImageAnalysisModelShas,
    private val cacheDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val modelShas = modelShas.normalized()

    suspend fun analyze(workItem: AnalysisWorkItemEntity) {
        require(workItem.mediaAssetId > 0L) { "工作项媒体 ID 必须大于 0" }
        val analyzerVersion = workItem.analyzerVersion.trim()
        require(analyzerVersion.isNotEmpty()) { "分析器版本不能为空" }
        val stage = parseSupportedStage(workItem.stage)
        val media = requireNotNull(store.getMedia(workItem.mediaAssetId)) {
            "媒体不存在：${workItem.mediaAssetId}"
        }
        require(media.mimeType.lowercase(Locale.ROOT).startsWith(IMAGE_MIME_PREFIX)) {
            "图片分析协调器不支持 MIME：${media.mimeType}"
        }

        when (stage) {
            AnalysisStage.QUALITY -> analyzeQuality(media, workItem.id, analyzerVersion)
            AnalysisStage.CLASSIFICATION -> analyzeClassification(
                media,
                workItem.id,
                analyzerVersion,
            )
            AnalysisStage.EMBEDDING -> analyzeEmbedding(media, workItem.id, analyzerVersion)
            AnalysisStage.CONDITIONAL_DETECTION -> analyzeConditionalDetection(
                media,
                workItem.id,
                analyzerVersion,
            )
            else -> error("不可达的图片分析阶段：$stage")
        }
    }

    private suspend fun analyzeQuality(
        media: MediaAssetEntity,
        workItemId: Long,
        analyzerVersion: String,
    ) {
        val resultExists =
            store.getQualityResult(media.id, IMAGE_TIMESTAMP_MILLIS, analyzerVersion) != null
        if (resultExists && hasFreshCompletedStage(
                media,
                AnalysisStage.QUALITY,
                analyzerVersion,
                workItemId,
            )
        ) {
            return
        }
        val result = stageExecutor.analyzeQuality(media)
        store.upsertQualityResult(
            MediaQualityResultEntity(
                mediaAssetId = media.id,
                timestampMillis = IMAGE_TIMESTAMP_MILLIS,
                analyzerVersion = analyzerVersion,
                totalScore = result.total,
                sharpnessScore = result.sharpness,
                exposureScore = result.exposure,
                noiseScore = result.noise,
                compositionScore = result.composition,
            ),
        )
    }

    private suspend fun analyzeClassification(
        media: MediaAssetEntity,
        workItemId: Long,
        analyzerVersion: String,
    ) {
        if (hasFreshCompletedStage(
                media,
                AnalysisStage.CLASSIFICATION,
                analyzerVersion,
                workItemId,
            )
        ) {
            return
        }

        val entities = stageExecutor.classify(media).mapIndexed { rank, label ->
            MediaClassificationEntity(
                mediaAssetId = media.id,
                timestampMillis = IMAGE_TIMESTAMP_MILLIS,
                modelSha256 = modelShas.classification,
                rank = rank,
                label = label.label,
                confidence = label.score,
            )
        }
        store.upsertClassifications(
            media.id,
            IMAGE_TIMESTAMP_MILLIS,
            modelShas.classification,
            entities,
        )
    }

    private suspend fun analyzeEmbedding(
        media: MediaAssetEntity,
        workItemId: Long,
        analyzerVersion: String,
    ) {
        val reference = store.getFeatureReference(
            media.id,
            IMAGE_TIMESTAMP_MILLIS,
            modelShas.embedding,
        )
        if (reference != null &&
            isReadableFeatureReference(reference, media.id) &&
            hasFreshCompletedStage(
                media,
                AnalysisStage.EMBEDDING,
                analyzerVersion,
                workItemId,
            )
        ) {
            return
        }

        val vector = stageExecutor.embed(media)
        val file = withContext(cacheDispatcher) {
            featureCache.write(modelShas.embedding, media.id, vector)
        }
        store.upsertFeatureReference(
            MediaFeatureReferenceEntity(
                mediaAssetId = media.id,
                timestampMillis = IMAGE_TIMESTAMP_MILLIS,
                modelSha256 = modelShas.embedding,
                cachePath = file.absolutePath,
                dimension = vector.size,
            ),
        )
    }

    private suspend fun analyzeConditionalDetection(
        media: MediaAssetEntity,
        workItemId: Long,
        analyzerVersion: String,
    ) {
        val detectionCompletedAt = store.latestCompletedStageAt(
            media.id,
            AnalysisStage.CONDITIONAL_DETECTION.name,
            analyzerVersion,
            workItemId,
        )
        val classificationCompletedAt =
            store.latestCompletedClassificationAt(media.id, workItemId)
        if (detectionCompletedAt != null &&
            classificationCompletedAt != null &&
            detectionCompletedAt >= media.lastModifiedMillis &&
            detectionCompletedAt >= classificationCompletedAt
        ) {
            return
        }

        val classifications = store.getClassifications(
            media.id,
            IMAGE_TIMESTAMP_MILLIS,
            modelShas.classification,
        )
        val labels = classifications.sortedBy(MediaClassificationEntity::rank).map { result ->
            LabelScore(result.label, result.confidence)
        }
        if (!stageExecutor.shouldDetect(media, labels)) {
            store.upsertDetections(
                media.id,
                IMAGE_TIMESTAMP_MILLIS,
                modelShas.detection,
                emptyList(),
            )
            return
        }

        val entities = stageExecutor.detect(media).mapIndexed { rank, detection ->
            MediaDetectionEntity(
                mediaAssetId = media.id,
                timestampMillis = IMAGE_TIMESTAMP_MILLIS,
                modelSha256 = modelShas.detection,
                rank = rank,
                category = detection.label,
                confidence = detection.score,
                left = detection.left,
                top = detection.top,
                right = detection.right,
                bottom = detection.bottom,
            )
        }
        store.upsertDetections(
            media.id,
            IMAGE_TIMESTAMP_MILLIS,
            modelShas.detection,
            entities,
        )
    }

    private suspend fun isReadableFeatureReference(
        reference: MediaFeatureReferenceEntity,
        mediaId: Long,
    ): Boolean {
        val expectedFile = featureCache.fileFor(modelShas.embedding, mediaId)
            .absoluteFile
            .normalize()
        val referencedFile = File(reference.cachePath).absoluteFile.normalize()
        if (referencedFile != expectedFile) return false
        val vector = withContext(cacheDispatcher) {
            featureCache.read(modelShas.embedding, mediaId)
        } ?: return false
        return reference.dimension == vector.size
    }

    private suspend fun hasFreshCompletedStage(
        media: MediaAssetEntity,
        stage: AnalysisStage,
        analyzerVersion: String,
        workItemId: Long,
    ): Boolean =
        store.latestCompletedStageAt(
            media.id,
            stage.name,
            analyzerVersion,
            workItemId,
        )?.let { completedAt -> completedAt >= media.lastModifiedMillis } == true

    private fun parseSupportedStage(rawStage: String): AnalysisStage {
        val stage = try {
            AnalysisStage.valueOf(rawStage)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("未知图片分析阶段：$rawStage", error)
        }
        require(stage in SUPPORTED_STAGES) { "图片分析协调器不支持阶段：$stage" }
        return stage
    }

    private companion object {
        const val IMAGE_TIMESTAMP_MILLIS = 0L
        const val IMAGE_MIME_PREFIX = "image/"
        val SUPPORTED_STAGES = setOf(
            AnalysisStage.QUALITY,
            AnalysisStage.CLASSIFICATION,
            AnalysisStage.EMBEDDING,
            AnalysisStage.CONDITIONAL_DETECTION,
        )
    }
}

/** 使用受限 Bitmap 和现有分析器执行生产图片阶段。 */
class DefaultImageAnalysisStageExecutor(
    private val decoder: MediaImageDecoder,
    private val classifier: ImageClassifierAnalyzer,
    private val embedder: ImageEmbeddingAnalyzer,
    private val detector: ObjectDetectorAnalyzer,
    private val detectionPolicy: ObjectDetectionPolicy = ObjectDetectionPolicy(),
    private val qualityScorer: QualityScorer = QualityScorer(),
    private val highValueProvider: (MediaAssetEntity) -> Boolean = { false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ImageAnalysisStageExecutor {
    override suspend fun analyzeQuality(media: MediaAssetEntity): ImageQualityScores =
        withBitmap(media) { bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val score = qualityScorer.score(
                FrameSample(
                    frameIndex = 0,
                    timestampMillis = 0L,
                    width = bitmap.width,
                    height = bitmap.height,
                    pixels = pixels,
                ),
            )
            ImageQualityScores(
                total = score.totalScore / SCORE_MAX,
                sharpness = score.clarityScore / SCORE_MAX,
                exposure = score.exposureScore / SCORE_MAX,
                // 当前确定性规则没有噪声与构图分项，使用中性值而非伪装模型输出。
                noise = NEUTRAL_SCORE,
                composition = NEUTRAL_SCORE,
            )
        }

    override suspend fun classify(media: MediaAssetEntity): List<LabelScore> =
        withBitmap(media, classifier::classify)

    override suspend fun embed(media: MediaAssetEntity): FloatArray =
        withBitmap(media) { bitmap ->
            embedder.analyzeSynchronously(ThumbnailInput.fromBitmap(bitmap)).values
        }

    override fun shouldDetect(
        media: MediaAssetEntity,
        classifications: List<LabelScore>,
    ): Boolean = detectionPolicy.shouldDetect(classifications, highValueProvider(media))

    override suspend fun detect(media: MediaAssetEntity): List<DetectionObservation> =
        withBitmap(media, detector::detect)

    private suspend fun <Result> withBitmap(
        media: MediaAssetEntity,
        block: (Bitmap) -> Result,
    ): Result = withDispatchedCloseable(
        ioDispatcher = ioDispatcher,
        inferenceDispatcher = inferenceDispatcher,
        acquire = {
            decoder.decode(
                uri = Uri.parse(media.uri),
                mediaRotationDegrees = media.rotationDegrees,
            )
        },
    ) { decoded ->
        block(decoded.bitmap)
    }

    private companion object {
        const val SCORE_MAX = 100f
        const val NEUTRAL_SCORE = 0.5f
    }
}

/**
 * 在 IO 调度器获取并关闭资源，在推理调度器使用资源。
 * 资源的 use 作用域包住线程切换，取消或异常时仍会回到 finally 关闭。
 */
internal suspend fun <Resource : Closeable, Result> withDispatchedCloseable(
    ioDispatcher: CoroutineDispatcher,
    inferenceDispatcher: CoroutineDispatcher,
    acquire: () -> Resource,
    block: suspend (Resource) -> Result,
): Result = withContext(ioDispatcher) {
    acquire().use { resource ->
        withContext(inferenceDispatcher) {
            block(resource)
        }
    }
}

private fun validateModelSha(value: String, modelName: String) {
    require(MODEL_SHA_PATTERN.matches(value)) {
        "$modelName 模型 SHA-256 必须为 64 位十六进制字符串"
    }
}

private val MODEL_SHA_PATTERN = Regex("[0-9A-Fa-f]{64}")
