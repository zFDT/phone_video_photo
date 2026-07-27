package com.frameyu.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** 批量分析批次、工作项和模型结果的持久化接口。 */
@Dao
abstract class BatchAnalysisDao {
    @Insert
    protected abstract suspend fun insertBatch(batch: AnalysisBatchEntity): Long

    @Insert
    protected abstract suspend fun insertWorkItems(items: List<AnalysisWorkItemEntity>)

    @Transaction
    open suspend fun createBatch(
        batch: AnalysisBatchEntity,
        items: List<AnalysisWorkItemEntity>,
    ): Long {
        val batchId = insertBatch(batch)
        val reboundItems = items.map { item ->
            require(item.batchId == 0L || item.batchId == batchId) {
                "工作项 batchId=${item.batchId} 与新批次 id=$batchId 不一致"
            }
            item.copy(batchId = batchId)
        }
        if (reboundItems.isNotEmpty()) {
            insertWorkItems(reboundItems)
        }
        return batchId
    }

    suspend fun nextRunnableItems(batchId: Long, limit: Int): List<AnalysisWorkItemEntity> {
        require(limit > 0) { "limit 必须大于 0" }
        return queryNextRunnableItems(batchId, limit)
    }

    @Query(
        """
        SELECT * FROM analysis_work_items
        WHERE batch_id = :batchId
          AND status IN ('PENDING', 'RETRYABLE_FAILED')
        ORDER BY id
        LIMIT :limit
        """
    )
    protected abstract suspend fun queryNextRunnableItems(
        batchId: Long,
        limit: Int,
    ): List<AnalysisWorkItemEntity>

    suspend fun markRunning(itemId: Long, updatedAtMillis: Long) {
        checkSingleItemUpdated(markRunningInternal(itemId, updatedAtMillis), itemId, "标记运行")
    }

    @Query(
        """
        UPDATE analysis_work_items
        SET status = 'RUNNING',
            attempts = attempts + 1,
            updated_at_millis = :updatedAtMillis
        WHERE id = :itemId
          AND status IN ('PENDING', 'RETRYABLE_FAILED')
        """
    )
    protected abstract suspend fun markRunningInternal(
        itemId: Long,
        updatedAtMillis: Long,
    ): Int

    suspend fun saveCheckpoint(
        itemId: Long,
        nextTimestampMillis: Long,
        updatedAtMillis: Long,
    ) {
        checkSingleItemUpdated(
            saveCheckpointInternal(itemId, nextTimestampMillis, updatedAtMillis),
            itemId,
            "保存断点",
        )
    }

    @Query(
        """
        UPDATE analysis_work_items
        SET next_timestamp_millis = :nextTimestampMillis,
            updated_at_millis = :updatedAtMillis
        WHERE id = :itemId
          AND status = 'RUNNING'
        """
    )
    protected abstract suspend fun saveCheckpointInternal(
        itemId: Long,
        nextTimestampMillis: Long,
        updatedAtMillis: Long,
    ): Int

    suspend fun completeItem(itemId: Long, updatedAtMillis: Long) {
        checkSingleItemUpdated(completeItemInternal(itemId, updatedAtMillis), itemId, "完成")
    }

    @Query(
        """
        UPDATE analysis_work_items
        SET status = 'COMPLETED',
            error_message = NULL,
            updated_at_millis = :updatedAtMillis
        WHERE id = :itemId
          AND status = 'RUNNING'
        """
    )
    protected abstract suspend fun completeItemInternal(
        itemId: Long,
        updatedAtMillis: Long,
    ): Int

    /** 根据 retryable 映射失败状态，message 按调用方传入的原始文本持久化。 */
    suspend fun failItem(
        itemId: Long,
        retryable: Boolean,
        message: String,
        updatedAtMillis: Long,
    ) {
        val status = if (retryable) "RETRYABLE_FAILED" else "PERMANENT_FAILED"
        checkSingleItemUpdated(
            failItemInternal(itemId, status, message, updatedAtMillis),
            itemId,
            "标记失败",
        )
    }

    @Query(
        """
        UPDATE analysis_work_items
        SET status = :status,
            error_message = :errorMessage,
            updated_at_millis = :updatedAtMillis
        WHERE id = :itemId
          AND status = 'RUNNING'
        """
    )
    protected abstract suspend fun failItemInternal(
        itemId: Long,
        status: String,
        errorMessage: String?,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE analysis_work_items
        SET status = 'RETRYABLE_FAILED',
            error_message = :message,
            updated_at_millis = :updatedAtMillis
        WHERE batch_id = :batchId
          AND status = 'RUNNING'
        """
    )
    abstract suspend fun recoverInterruptedItems(
        batchId: Long,
        message: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        SELECT b.*,
               COUNT(w.id) AS total_work_items,
               COALESCE(SUM(CASE WHEN w.status = 'COMPLETED' THEN 1 ELSE 0 END), 0)
                   AS completed_work_items,
               COALESCE(SUM(CASE WHEN w.status = 'RUNNING' THEN 1 ELSE 0 END), 0)
                   AS running_work_items,
               COALESCE(SUM(CASE
                   WHEN w.status IN ('PENDING', 'PAUSED', 'RETRYABLE_FAILED') THEN 1
                   ELSE 0
               END), 0) AS pending_work_items,
               COALESCE(SUM(CASE WHEN w.status = 'PERMANENT_FAILED' THEN 1 ELSE 0 END), 0)
                   AS failed_work_items
        FROM analysis_batches AS b
        LEFT JOIN analysis_work_items AS w ON w.batch_id = b.id
        WHERE b.id = :batchId
        GROUP BY b.id
        """
    )
    abstract fun observeBatch(batchId: Long): Flow<AnalysisBatchWithProgress>

    @Query(
        """
        SELECT MAX(updated_at_millis) FROM analysis_work_items
        WHERE media_asset_id = :mediaAssetId
          AND stage = :stage
          AND analyzer_version = :analyzerVersion
          AND status = 'COMPLETED'
          AND id != :excludingItemId
        """
    )
    abstract suspend fun latestCompletedStageAt(
        mediaAssetId: Long,
        stage: String,
        analyzerVersion: String,
        excludingItemId: Long,
    ): Long?

    @Query(
        """
        SELECT MAX(updated_at_millis) FROM analysis_work_items
        WHERE media_asset_id = :mediaAssetId
          AND stage = 'CLASSIFICATION'
          AND status = 'COMPLETED'
          AND id != :excludingItemId
        """
    )
    abstract suspend fun latestCompletedClassificationAt(
        mediaAssetId: Long,
        excludingItemId: Long,
    ): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertClassifications(results: List<MediaClassificationEntity>)

    @Query(
        """
        DELETE FROM media_classifications
        WHERE media_asset_id = :mediaAssetId
          AND timestamp_millis = :timestampMillis
          AND model_sha256 = :modelSha256
        """
    )
    protected abstract suspend fun deleteClassifications(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    )

    /** 对指定自然键的分类结果执行完整集合替换，输入行 ID 不会保留。 */
    @Transaction
    open suspend fun upsertClassifications(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        results: List<MediaClassificationEntity>,
    ) {
        require(
            results.all { result ->
                result.mediaAssetId == mediaAssetId &&
                    result.timestampMillis == timestampMillis &&
                    result.modelSha256 == modelSha256
            }
        ) { "分类结果的自然键与替换目标不一致" }
        deleteClassifications(mediaAssetId, timestampMillis, modelSha256)
        if (results.isNotEmpty()) {
            insertClassifications(results.map { it.copy(id = 0L) })
        }
    }

    @Query(
        """
        SELECT * FROM media_classifications
        WHERE media_asset_id = :mediaAssetId
          AND timestamp_millis = :timestampMillis
          AND model_sha256 = :modelSha256
        ORDER BY rank
        """
    )
    abstract suspend fun getClassifications(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): List<MediaClassificationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertDetections(results: List<MediaDetectionEntity>)

    @Query(
        """
        DELETE FROM media_detections
        WHERE media_asset_id = :mediaAssetId
          AND timestamp_millis = :timestampMillis
          AND model_sha256 = :modelSha256
        """
    )
    protected abstract suspend fun deleteDetections(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    )

    /** 对指定自然键的检测结果执行完整集合替换，输入行 ID 不会保留。 */
    @Transaction
    open suspend fun upsertDetections(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        results: List<MediaDetectionEntity>,
    ) {
        require(
            results.all { result ->
                result.mediaAssetId == mediaAssetId &&
                    result.timestampMillis == timestampMillis &&
                    result.modelSha256 == modelSha256
            }
        ) { "检测结果的自然键与替换目标不一致" }
        deleteDetections(mediaAssetId, timestampMillis, modelSha256)
        if (results.isNotEmpty()) {
            insertDetections(results.map { it.copy(id = 0L) })
        }
    }

    @Query(
        """
        SELECT * FROM media_detections
        WHERE media_asset_id = :mediaAssetId
          AND timestamp_millis = :timestampMillis
          AND model_sha256 = :modelSha256
        ORDER BY rank
        """
    )
    abstract suspend fun getDetections(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): List<MediaDetectionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertFeatureReference(result: MediaFeatureReferenceEntity)

    @Query(
        """
        UPDATE media_feature_references
        SET cache_path = :cachePath,
            dimension = :dimension
        WHERE media_asset_id = :mediaAssetId
          AND timestamp_millis = :timestampMillis
          AND model_sha256 = :modelSha256
        """
    )
    protected abstract suspend fun updateFeatureReference(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
        cachePath: String,
        dimension: Int,
    ): Int

    /** 按自然键更新特征引用；调用方传入的行 ID 会被忽略。 */
    @Transaction
    open suspend fun upsertFeatureReference(result: MediaFeatureReferenceEntity) {
        val updatedRows = updateFeatureReference(
            mediaAssetId = result.mediaAssetId,
            timestampMillis = result.timestampMillis,
            modelSha256 = result.modelSha256,
            cachePath = result.cachePath,
            dimension = result.dimension,
        )
        if (updatedRows == 0) {
            insertFeatureReference(result.copy(id = 0L))
        }
    }

    @Query(
        """
        SELECT * FROM media_feature_references
        WHERE media_asset_id = :mediaAssetId
          AND timestamp_millis = :timestampMillis
          AND model_sha256 = :modelSha256
        LIMIT 1
        """
    )
    abstract suspend fun getFeatureReference(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    ): MediaFeatureReferenceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertQualityResult(result: MediaQualityResultEntity)

    @Query(
        """
        UPDATE media_quality_results
        SET total_score = :totalScore,
            sharpness_score = :sharpnessScore,
            exposure_score = :exposureScore,
            noise_score = :noiseScore,
            composition_score = :compositionScore
        WHERE media_asset_id = :mediaAssetId
          AND timestamp_millis = :timestampMillis
          AND analyzer_version = :analyzerVersion
        """
    )
    protected abstract suspend fun updateQualityResult(
        mediaAssetId: Long,
        timestampMillis: Long,
        analyzerVersion: String,
        totalScore: Float,
        sharpnessScore: Float,
        exposureScore: Float,
        noiseScore: Float,
        compositionScore: Float,
    ): Int

    /** 按自然键更新质量结果；调用方传入的行 ID 会被忽略。 */
    @Transaction
    open suspend fun upsertQualityResult(result: MediaQualityResultEntity) {
        val updatedRows = updateQualityResult(
            mediaAssetId = result.mediaAssetId,
            timestampMillis = result.timestampMillis,
            analyzerVersion = result.analyzerVersion,
            totalScore = result.totalScore,
            sharpnessScore = result.sharpnessScore,
            exposureScore = result.exposureScore,
            noiseScore = result.noiseScore,
            compositionScore = result.compositionScore,
        )
        if (updatedRows == 0) {
            insertQualityResult(result.copy(id = 0L))
        }
    }

    @Query(
        """
        SELECT * FROM media_quality_results
        WHERE media_asset_id = :mediaAssetId
          AND timestamp_millis = :timestampMillis
          AND analyzer_version = :analyzerVersion
        LIMIT 1
        """
    )
    abstract suspend fun getQualityResult(
        mediaAssetId: Long,
        timestampMillis: Long,
        analyzerVersion: String,
    ): MediaQualityResultEntity?

    private fun checkSingleItemUpdated(updatedRows: Int, itemId: Long, operation: String) {
        check(updatedRows == 1) {
            "无法对工作项 id=$itemId 执行$operation：工作项不存在或状态不允许"
        }
    }
}
