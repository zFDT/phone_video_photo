package com.frameyu.app.sampling

import com.frameyu.app.data.db.AnalysisCandidateDao
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.BatchAnalysisDao
import com.frameyu.app.data.db.MediaClassificationEntity
import com.frameyu.app.data.db.MediaDetectionEntity
import com.frameyu.app.data.db.MediaFeatureReferenceEntity
import com.frameyu.app.storage.FeatureCache
import com.frameyu.app.vision.FrameModelResult
import com.frameyu.app.vision.FrameModelResultStore
import com.frameyu.app.vision.LabelScore
import java.util.Locale

/** 使用现有 media_* 表和按帧特征缓存持久化视频模型结果。 */
class RoomVideoFrameModelStore(
    private val candidateDao: AnalysisCandidateDao,
    private val batchAnalysisDao: BatchAnalysisDao,
    private val featureCache: FeatureCache,
    classificationModelSha256: String,
    embeddingModelSha256: String,
    detectionModelSha256: String,
) : VideoFrameModelStore, VideoClassificationAggregationStore, FrameModelResultStore {
    private val classificationModelSha256 = classificationModelSha256.normalizedModelSha()
    private val embeddingModelSha256 = embeddingModelSha256.normalizedModelSha()
    private val detectionModelSha256 = detectionModelSha256.normalizedModelSha()

    override suspend fun saveRuleCandidate(candidate: AnalysisCandidateEntity) {
        candidateDao.upsertRuleCandidate(candidate)
    }

    override suspend fun saveFrameResult(
        mediaId: Long,
        timestampMillis: Long,
        result: FrameModelResult,
    ) {
        require(mediaId > 0L) { "媒体 ID 必须大于 0" }
        require(timestampMillis >= 0L) { "帧时间戳不能为负数" }

        val embedding = result.embedding
        val featureReference = embedding?.let { vector ->
            val file = featureCache.write(
                embeddingModelSha256,
                mediaId,
                timestampMillis,
                vector,
            )
            MediaFeatureReferenceEntity(
                mediaAssetId = mediaId,
                timestampMillis = timestampMillis,
                modelSha256 = embeddingModelSha256,
                cachePath = file.absolutePath,
                dimension = vector.size,
            )
        }
        batchAnalysisDao.upsertClassifications(
            mediaAssetId = mediaId,
            timestampMillis = timestampMillis,
            modelSha256 = classificationModelSha256,
            results = result.labels.mapIndexed { rank, label ->
                MediaClassificationEntity(
                    mediaAssetId = mediaId,
                    timestampMillis = timestampMillis,
                    modelSha256 = classificationModelSha256,
                    rank = rank,
                    label = label.label,
                    confidence = label.score,
                )
            },
        )
        batchAnalysisDao.upsertDetections(
            mediaAssetId = mediaId,
            timestampMillis = timestampMillis,
            modelSha256 = detectionModelSha256,
            results = result.detections.mapIndexed { rank, detection ->
                MediaDetectionEntity(
                    mediaAssetId = mediaId,
                    timestampMillis = timestampMillis,
                    modelSha256 = detectionModelSha256,
                    rank = rank,
                    category = detection.label,
                    confidence = detection.score,
                    left = detection.left,
                    top = detection.top,
                    right = detection.right,
                    bottom = detection.bottom,
                )
            },
        )
        if (featureReference == null) {
            batchAnalysisDao.deleteFeatureReference(
                mediaId,
                timestampMillis,
                embeddingModelSha256,
            )
        } else {
            batchAnalysisDao.upsertFeatureReference(featureReference)
        }
    }

    override suspend fun updateCandidateModelStatus(
        candidateId: Long,
        status: CandidateModelStatus,
        errorMessage: String?,
    ) {
        check(candidateDao.updateModelStatus(candidateId, status.name, errorMessage) == 1) {
            "模型状态对应的候选帧不存在：$candidateId"
        }
    }

    override suspend fun loadFrameLabels(
        mediaId: Long,
        modelSha256: String,
    ): List<TimedLabels> {
        val normalizedModelSha = modelSha256.normalizedModelSha()
        return batchAnalysisDao.getFrameClassifications(mediaId, normalizedModelSha)
            .groupBy(MediaClassificationEntity::timestampMillis)
            .map { (timestampMillis, rows) ->
                TimedLabels(
                    timestampMillis = timestampMillis,
                    labels = rows.sortedBy(MediaClassificationEntity::rank).map { row ->
                        LabelScore(row.label, row.confidence)
                    },
                )
            }
            .sortedBy(TimedLabels::timestampMillis)
    }

    override suspend fun replaceAggregateLabels(
        mediaId: Long,
        modelSha256: String,
        timestampMillis: Long,
        labels: List<AggregatedVideoLabel>,
    ) {
        require(timestampMillis == VIDEO_AGGREGATE_TIMESTAMP_MILLIS) {
            "视频级聚合必须使用固定 sentinel 时间戳"
        }
        val normalizedModelSha = modelSha256.normalizedModelSha()
        batchAnalysisDao.upsertClassifications(
            mediaAssetId = mediaId,
            timestampMillis = timestampMillis,
            modelSha256 = normalizedModelSha,
            results = labels.mapIndexed { rank, label ->
                MediaClassificationEntity(
                    mediaAssetId = mediaId,
                    timestampMillis = timestampMillis,
                    modelSha256 = normalizedModelSha,
                    rank = rank,
                    label = label.label,
                    confidence = label.score,
                )
            },
        )
    }

    val classificationSha256: String
        get() = classificationModelSha256
}

private val MODEL_SHA_PATTERN = Regex("[0-9A-Fa-f]{64}")

private fun String.normalizedModelSha(): String {
    require(MODEL_SHA_PATTERN.matches(this)) { "模型 SHA-256 必须为 64 位十六进制字符串" }
    return lowercase(Locale.ROOT)
}
