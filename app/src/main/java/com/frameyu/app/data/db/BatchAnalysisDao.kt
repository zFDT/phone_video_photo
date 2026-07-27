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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertClassifications(results: List<MediaClassificationEntity>)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDetections(results: List<MediaDetectionEntity>)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFeatureReference(result: MediaFeatureReferenceEntity)

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertQualityResult(result: MediaQualityResultEntity)

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
