package com.frameyu.app.data.db

import androidx.room.ColumnInfo
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

    @Query(
        """
        SELECT * FROM analysis_batches
        WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED', 'NEEDS_ATTENTION')
        ORDER BY id DESC
        LIMIT 1
        """
    )
    abstract suspend fun latestOpenBatch(): AnalysisBatchEntity?

    @Query(
        """
        SELECT * FROM analysis_work_items
        WHERE batch_id = :batchId
        ORDER BY id
        """
    )
    abstract suspend fun batchWorkItems(batchId: Long): List<AnalysisWorkItemEntity>

    @Transaction
    open suspend fun replaceOpenBatch(
        openBatchId: Long,
        batch: AnalysisBatchEntity,
        items: List<AnalysisWorkItemEntity>,
        updatedAtMillis: Long,
    ): Long {
        cancelBatch(openBatchId, updatedAtMillis)
        return createBatch(batch, items)
    }

    suspend fun nextRunnableItems(batchId: Long, limit: Int): List<AnalysisWorkItemEntity> =
        nextRunnableItems(batchId, afterId = 0L, limit = limit)

    suspend fun nextRunnableItems(
        batchId: Long,
        afterId: Long,
        limit: Int,
    ): List<AnalysisWorkItemEntity> {
        require(afterId >= 0L) { "afterId 不能为负数" }
        require(limit > 0) { "limit 必须大于 0" }
        return queryNextRunnableItems(batchId, afterId, limit)
    }

    @Query(
        """
        SELECT * FROM analysis_work_items
        WHERE batch_id = :batchId
          AND id > :afterId
          AND status IN ('PENDING', 'RETRYABLE_FAILED')
        ORDER BY id
        LIMIT :limit
        """
    )
    protected abstract suspend fun queryNextRunnableItems(
        batchId: Long,
        afterId: Long,
        limit: Int,
    ): List<AnalysisWorkItemEntity>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM analysis_work_items
            WHERE batch_id = :batchId
              AND media_asset_id = :mediaAssetId
              AND stage = 'CLASSIFICATION'
              AND status = 'COMPLETED'
        )
        """
    )
    abstract suspend fun classificationCompleted(
        batchId: Long,
        mediaAssetId: Long,
    ): Boolean

    /** 工作项不存在时抛错；存在但来源状态已变化时返回 false。 */
    @Transaction
    open suspend fun markRunning(itemId: Long, updatedAtMillis: Long): Boolean {
        requireWorkItem(itemId)
        return markRunningInternal(itemId, updatedAtMillis) == 1
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

    /** 工作项不存在时抛错；存在但已不在 RUNNING 时返回 false。 */
    @Transaction
    open suspend fun completeItem(itemId: Long, updatedAtMillis: Long): Boolean {
        requireWorkItem(itemId)
        return completeItemInternal(itemId, updatedAtMillis) == 1
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

    /**
     * 根据 retryable 映射失败状态，message 按原始文本持久化。
     * 工作项不存在时抛错；存在但已不在 RUNNING 时返回 false。
     */
    @Transaction
    open suspend fun failItem(
        itemId: Long,
        retryable: Boolean,
        message: String,
        updatedAtMillis: Long,
    ): Boolean {
        requireWorkItem(itemId)
        val status = if (retryable) "RETRYABLE_FAILED" else "PERMANENT_FAILED"
        return failItemInternal(itemId, status, message, updatedAtMillis) == 1
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
    protected abstract suspend fun recoverInterruptedItemsInternal(
        batchId: Long,
        message: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE analysis_work_items
        SET status = 'RETRYABLE_FAILED',
            error_message = :message,
            updated_at_millis = :updatedAtMillis
        WHERE batch_id = :batchId
          AND status IN ('PENDING', 'RUNNING', 'RETRYABLE_FAILED')
        """
    )
    protected abstract suspend fun failRecoverableItemsInternal(
        batchId: Long,
        message: String,
        updatedAtMillis: Long,
    ): Int

    /** Runner 级异常只收敛当前已由 Worker claim 的 RUNNING 批次，不覆盖终态结果。 */
    @Transaction
    open suspend fun failActiveExecution(
        batchId: Long,
        executionGeneration: Long,
        message: String,
        updatedAtMillis: Long,
    ): Boolean {
        require(executionGeneration >= 0L) { "执行代际不能为负数" }
        val control = batchControlState(batchId)
        if (control.status != "RUNNING" ||
            control.executionGeneration != executionGeneration
        ) {
            return false
        }
        failRecoverableItemsInternal(batchId, message, updatedAtMillis)
        return finishRunningBatchInternal(
            batchId,
            executionGeneration,
            "NEEDS_ATTENTION",
            updatedAtMillis,
        ) == 1
    }

    @Query("SELECT status FROM analysis_batches WHERE id = :batchId")
    protected abstract suspend fun queryBatchStatus(batchId: Long): String?

    suspend fun batchStatus(batchId: Long): String {
        require(batchId > 0L) { "批次 ID 必须大于 0" }
        return requireNotNull(queryBatchStatus(batchId)) { "批次不存在：$batchId" }
    }

    @Query(
        """
        SELECT status, execution_generation
        FROM analysis_batches
        WHERE id = :batchId
        """
    )
    protected abstract suspend fun queryBatchControlState(batchId: Long): BatchControlState?

    suspend fun batchControlState(batchId: Long): BatchControlState {
        require(batchId > 0L) { "批次 ID 必须大于 0" }
        return requireNotNull(queryBatchControlState(batchId)) { "批次不存在：$batchId" }
    }

    @Transaction
    open suspend fun recoverInterruptedItems(
        batchId: Long,
        message: String,
        updatedAtMillis: Long,
    ): Int {
        batchStatus(batchId)
        return recoverInterruptedItemsInternal(batchId, message, updatedAtMillis)
    }

    @Query(
        """
        UPDATE analysis_batches
        SET status = :status,
            execution_generation = execution_generation + 1,
            updated_at_millis = :updatedAtMillis
        WHERE id = :batchId
          AND status IN (:sourceStatuses)
        """
    )
    protected abstract suspend fun updateBatchStatusFromInternal(
        batchId: Long,
        status: String,
        updatedAtMillis: Long,
        sourceStatuses: List<String>,
    ): Int

    @Query(
        """
        UPDATE analysis_batches
        SET status = 'RUNNING',
            updated_at_millis = :updatedAtMillis
        WHERE id = :batchId
          AND status IN ('QUEUED', 'RUNNING')
          AND execution_generation = :executionGeneration
        """
    )
    protected abstract suspend fun markBatchRunningInternal(
        batchId: Long,
        executionGeneration: Long,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE analysis_batches
        SET status = :status,
            updated_at_millis = :updatedAtMillis
        WHERE id = :batchId
          AND status = 'RUNNING'
          AND execution_generation = :executionGeneration
        """
    )
    protected abstract suspend fun finishRunningBatchInternal(
        batchId: Long,
        executionGeneration: Long,
        status: String,
        updatedAtMillis: Long,
    ): Int

    /** 兼容旧调用方，事务内读取当前执行代际后立即 claim。 */
    @Transaction
    open suspend fun markBatchRunning(batchId: Long, updatedAtMillis: Long): Boolean {
        val control = batchControlState(batchId)
        return markBatchRunningInternal(
            batchId,
            control.executionGeneration,
            updatedAtMillis,
        ) == 1
    }

    /** 批次不存在时抛错；控制态或执行代际已变化时返回 false。 */
    @Transaction
    open suspend fun markBatchRunning(
        batchId: Long,
        executionGeneration: Long,
        updatedAtMillis: Long,
    ): Boolean {
        require(executionGeneration >= 0L) { "执行代际不能为负数" }
        batchStatus(batchId)
        return markBatchRunningInternal(batchId, executionGeneration, updatedAtMillis) == 1
    }

    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0)
                   AS completed,
               COALESCE(SUM(CASE WHEN status = 'RETRYABLE_FAILED' THEN 1 ELSE 0 END), 0)
                   AS retryable_failed,
               COALESCE(SUM(CASE WHEN status = 'PERMANENT_FAILED' THEN 1 ELSE 0 END), 0)
                   AS permanent_failed,
               COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0)
                   AS pending,
               COALESCE(SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END), 0)
                   AS running,
               COALESCE(SUM(CASE WHEN status = 'PAUSED' THEN 1 ELSE 0 END), 0)
                   AS paused,
               COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0)
                   AS cancelled
        FROM analysis_work_items
        WHERE batch_id = :batchId
        """
    )
    protected abstract suspend fun queryBatchCounts(batchId: Long): BatchAnalysisCounts

    suspend fun batchCounts(batchId: Long): BatchAnalysisCounts {
        batchStatus(batchId)
        return queryBatchCounts(batchId)
    }

    @Query(
        """
        UPDATE analysis_work_items
        SET status = 'PAUSED',
            updated_at_millis = :updatedAtMillis
        WHERE batch_id = :batchId
          AND status IN ('PENDING', 'RETRYABLE_FAILED')
        """
    )
    protected abstract suspend fun pauseRunnableItems(
        batchId: Long,
        updatedAtMillis: Long,
    ): Int

    @Transaction
    open suspend fun pauseBatch(batchId: Long, updatedAtMillis: Long) {
        val status = batchStatus(batchId)
        if (status == "PAUSED") return
        check(status in setOf("QUEUED", "RUNNING")) {
            "批次 id=$batchId 当前状态不允许暂停：$status"
        }
        checkBatchTransitionUpdated(
            updateBatchStatusFromInternal(
                batchId,
                "PAUSED",
                updatedAtMillis,
                listOf("QUEUED", "RUNNING"),
            ),
            batchId,
            "暂停",
        )
        pauseRunnableItems(batchId, updatedAtMillis)
    }

    @Query(
        """
        UPDATE analysis_work_items
        SET status = CASE
                WHEN status = 'PAUSED' THEN 'PENDING'
                ELSE 'RETRYABLE_FAILED'
            END,
            error_message = CASE
                WHEN status = 'RUNNING' THEN :interruptedMessage
                ELSE error_message
            END,
            updated_at_millis = :updatedAtMillis
        WHERE batch_id = :batchId
          AND status IN ('PAUSED', 'RUNNING')
        """
    )
    protected abstract suspend fun resumeItems(
        batchId: Long,
        interruptedMessage: String,
        updatedAtMillis: Long,
    ): Int

    @Transaction
    open suspend fun resumeBatch(
        batchId: Long,
        interruptedMessage: String,
        updatedAtMillis: Long,
    ) {
        val status = batchStatus(batchId)
        if (status == "QUEUED") return
        check(status == "PAUSED") {
            "批次 id=$batchId 当前状态不允许恢复：$status"
        }
        resumeItems(batchId, interruptedMessage, updatedAtMillis)
        checkBatchTransitionUpdated(
            updateBatchStatusFromInternal(
                batchId,
                "QUEUED",
                updatedAtMillis,
                listOf("PAUSED"),
            ),
            batchId,
            "恢复",
        )
    }

    @Query(
        """
        UPDATE analysis_work_items
        SET status = 'CANCELLED',
            updated_at_millis = :updatedAtMillis
        WHERE batch_id = :batchId
          AND status IN ('PENDING', 'RUNNING', 'PAUSED', 'RETRYABLE_FAILED')
        """
    )
    protected abstract suspend fun cancelUnfinishedItems(
        batchId: Long,
        updatedAtMillis: Long,
    ): Int

    @Transaction
    open suspend fun cancelBatch(batchId: Long, updatedAtMillis: Long) {
        val status = batchStatus(batchId)
        if (status == "CANCELLED") return
        check(status in setOf("QUEUED", "RUNNING", "PAUSED", "NEEDS_ATTENTION")) {
            "批次 id=$batchId 当前状态不允许取消：$status"
        }
        checkBatchTransitionUpdated(
            updateBatchStatusFromInternal(
                batchId,
                "CANCELLED",
                updatedAtMillis,
                listOf("QUEUED", "RUNNING", "PAUSED", "NEEDS_ATTENTION"),
            ),
            batchId,
            "取消",
        )
        cancelUnfinishedItems(batchId, updatedAtMillis)
    }

    @Query(
        """
        UPDATE analysis_work_items
        SET status = 'PENDING',
            error_message = NULL,
            updated_at_millis = :updatedAtMillis
        WHERE batch_id = :batchId
          AND status IN ('RETRYABLE_FAILED', 'PERMANENT_FAILED')
        """
    )
    protected abstract suspend fun resetFailedItems(
        batchId: Long,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE analysis_work_items
        SET status = 'RETRYABLE_FAILED',
            error_message = :message,
            updated_at_millis = :updatedAtMillis
        WHERE batch_id = :batchId
          AND status IN ('PENDING', 'RUNNING', 'PAUSED')
        """
    )
    protected abstract suspend fun failUnscheduledItems(
        batchId: Long,
        message: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE analysis_batches
        SET status = 'NEEDS_ATTENTION',
            updated_at_millis = :updatedAtMillis
        WHERE id = :batchId
          AND execution_generation = :executionGeneration
          AND status IN ('QUEUED', 'RUNNING')
        """
    )
    protected abstract suspend fun markBatchNeedsAttentionInternal(
        batchId: Long,
        executionGeneration: Long,
        updatedAtMillis: Long,
    ): Int

    /**
     * WorkManager 入队失败时保留已完成结果，并把未执行项转换为可显式重试状态。
     */
    @Transaction
    open suspend fun markBatchNeedsAttention(
        batchId: Long,
        executionGeneration: Long,
        message: String,
        updatedAtMillis: Long,
    ): Boolean {
        require(executionGeneration >= 0L) { "执行代际不能为负数" }
        val control = batchControlState(batchId)
        if (control.executionGeneration != executionGeneration) return false
        if (control.status == "NEEDS_ATTENTION") return true
        check(control.status in setOf("QUEUED", "RUNNING")) {
            "批次 id=$batchId 当前状态不允许标记待处理：${control.status}"
        }
        if (markBatchNeedsAttentionInternal(
                batchId,
                executionGeneration,
                updatedAtMillis,
            ) != 1
        ) {
            return false
        }
        failUnscheduledItems(batchId, message, updatedAtMillis)
        return true
    }

    @Query(
        """
        SELECT b.id AS batch_id,
               b.execution_generation
        FROM analysis_batches AS b
        WHERE b.status IN ('QUEUED', 'RUNNING')
        ORDER BY b.id
        """
    )
    abstract suspend fun reconcilableExecutions(): List<BatchExecutionRecord>

    @Transaction
    open suspend fun retryFailures(batchId: Long, updatedAtMillis: Long) {
        val status = batchStatus(batchId)
        check(status == "NEEDS_ATTENTION") {
            "批次 id=$batchId 当前状态不允许重试失败项：$status"
        }
        resetFailedItems(batchId, updatedAtMillis)
        checkBatchTransitionUpdated(
            updateBatchStatusFromInternal(
                batchId,
                "QUEUED",
                updatedAtMillis,
                listOf("NEEDS_ATTENTION"),
            ),
            batchId,
            "重试失败项",
        )
    }

    @Transaction
    open suspend fun finishBatch(
        batchId: Long,
        executionGeneration: Long,
        status: String,
        updatedAtMillis: Long,
    ): Boolean {
        require(executionGeneration >= 0L) { "执行代际不能为负数" }
        require(status in setOf("COMPLETED", "NEEDS_ATTENTION")) {
            "批次最终状态不受支持：$status"
        }
        batchStatus(batchId)
        return finishRunningBatchInternal(
            batchId,
            executionGeneration,
            status,
            updatedAtMillis,
        ) == 1
    }

    @Query(
        """
        SELECT b.*,
               COUNT(w.id) AS total_work_items,
               COALESCE(SUM(CASE WHEN w.status = 'COMPLETED' THEN 1 ELSE 0 END), 0)
                   AS completed_work_items,
               COALESCE(SUM(CASE WHEN w.status = 'RUNNING' THEN 1 ELSE 0 END), 0)
                   AS running_work_items,
               COALESCE(SUM(CASE
                   WHEN w.status IN ('PENDING', 'PAUSED') THEN 1
                   ELSE 0
               END), 0) AS pending_work_items,
               COALESCE(SUM(CASE
                   WHEN w.status IN ('RETRYABLE_FAILED', 'PERMANENT_FAILED') THEN 1
                   ELSE 0
               END), 0)
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
        SELECT stage FROM analysis_work_items
        WHERE batch_id = :batchId
          AND status IN ('RUNNING', 'PENDING', 'PAUSED')
        ORDER BY CASE status
            WHEN 'RUNNING' THEN 0
            WHEN 'PAUSED' THEN 1
            ELSE 2
        END, id
        LIMIT 1
        """
    )
    abstract fun observeCurrentStage(batchId: Long): Flow<String?>

    @Query(
        """
        SELECT * FROM analysis_work_items
        WHERE batch_id = :batchId
          AND status IN ('RETRYABLE_FAILED', 'PERMANENT_FAILED')
        ORDER BY id
        LIMIT :limit
        """
    )
    abstract fun observeFailedItems(
        batchId: Long,
        limit: Int,
    ): Flow<List<AnalysisWorkItemEntity>>

    @Query("SELECT * FROM media_quality_results ORDER BY id DESC")
    abstract fun observeQualityResults(): Flow<List<MediaQualityResultEntity>>

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

    /** 查询逐帧分类；明确排除视频级聚合 sentinel。 */
    @Query(
        """
        SELECT * FROM media_classifications
        WHERE media_asset_id = :mediaAssetId
          AND model_sha256 = :modelSha256
          AND timestamp_millis >= 0
        ORDER BY timestamp_millis, rank
        """
    )
    abstract suspend fun getFrameClassifications(
        mediaAssetId: Long,
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

    @Query(
        """
        DELETE FROM media_feature_references
        WHERE media_asset_id = :mediaAssetId
          AND timestamp_millis = :timestampMillis
          AND model_sha256 = :modelSha256
        """
    )
    abstract suspend fun deleteFeatureReference(
        mediaAssetId: Long,
        timestampMillis: Long,
        modelSha256: String,
    )

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

    @Query("SELECT EXISTS(SELECT 1 FROM analysis_work_items WHERE id = :itemId)")
    protected abstract suspend fun workItemExists(itemId: Long): Boolean

    private suspend fun requireWorkItem(itemId: Long) {
        check(workItemExists(itemId)) { "工作项不存在：$itemId" }
    }

    private fun checkBatchTransitionUpdated(updatedRows: Int, batchId: Long, operation: String) {
        check(updatedRows == 1) {
            "无法对批次 id=$batchId 执行$operation：来源状态已变化"
        }
    }
}

/** Runner 所需的稳定批次计数快照。 */
data class BatchAnalysisCounts(
    val total: Int,
    val completed: Int,
    @ColumnInfo(name = "retryable_failed")
    val retryableFailed: Int,
    @ColumnInfo(name = "permanent_failed")
    val permanentFailed: Int,
    val pending: Int,
    val running: Int,
    val paused: Int,
    val cancelled: Int,
)

/** 批次控制态及绑定 WorkRequest 生命周期的执行代际。 */
data class BatchControlState(
    val status: String,
    @ColumnInfo(name = "execution_generation")
    val executionGeneration: Long,
)

/** 启动恢复对账所需的批次执行所有权。 */
data class BatchExecutionRecord(
    @ColumnInfo(name = "batch_id")
    val batchId: Long,
    @ColumnInfo(name = "execution_generation")
    val executionGeneration: Long,
)
