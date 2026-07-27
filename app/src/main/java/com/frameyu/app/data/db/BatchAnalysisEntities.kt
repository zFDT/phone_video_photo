package com.frameyu.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一次批量分析任务。 */
@Entity(
    tableName = "analysis_batches",
    indices = [Index("status")],
)
data class AnalysisBatchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val status: String,
    val scope: String = "ALL_MEDIA",
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long,
)

/** 批次中可独立调度的分析阶段。 */
@Entity(
    tableName = "analysis_work_items",
    foreignKeys = [
        ForeignKey(
            entity = AnalysisBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["batch_id", "media_asset_id", "stage", "analyzer_version"],
            unique = true,
        ),
        Index("batch_id"),
        Index("media_asset_id"),
        Index("status"),
        Index(value = ["batch_id", "status"]),
    ],
)
data class AnalysisWorkItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "batch_id")
    val batchId: Long,
    @ColumnInfo(name = "media_asset_id")
    val mediaAssetId: Long,
    val stage: String,
    val status: String,
    @ColumnInfo(name = "analyzer_version")
    val analyzerVersion: String,
    @ColumnInfo(name = "next_timestamp_millis")
    val nextTimestampMillis: Long = 0L,
    val attempts: Int = 0,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long,
)

/** 单帧模型分类结果。 */
@Entity(
    tableName = "media_classifications",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["media_asset_id", "timestamp_millis", "model_sha256", "rank"],
            unique = true,
        ),
        Index("media_asset_id"),
    ],
)
data class MediaClassificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "media_asset_id")
    val mediaAssetId: Long,
    @ColumnInfo(name = "timestamp_millis")
    val timestampMillis: Long,
    @ColumnInfo(name = "model_sha256")
    val modelSha256: String,
    val rank: Int,
    val label: String,
    val confidence: Float,
)

/** 单帧模型检测框结果。 */
@Entity(
    tableName = "media_detections",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["media_asset_id", "timestamp_millis", "model_sha256", "rank"],
            unique = true,
        ),
        Index("media_asset_id"),
    ],
)
data class MediaDetectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "media_asset_id")
    val mediaAssetId: Long,
    @ColumnInfo(name = "timestamp_millis")
    val timestampMillis: Long,
    @ColumnInfo(name = "model_sha256")
    val modelSha256: String,
    val rank: Int,
    val category: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** 媒体特征向量在缓存中的位置。 */
@Entity(
    tableName = "media_feature_references",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["media_asset_id", "timestamp_millis", "model_sha256"],
            unique = true,
        ),
        Index("media_asset_id"),
    ],
)
data class MediaFeatureReferenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "media_asset_id")
    val mediaAssetId: Long,
    @ColumnInfo(name = "timestamp_millis")
    val timestampMillis: Long,
    @ColumnInfo(name = "model_sha256")
    val modelSha256: String,
    @ColumnInfo(name = "cache_path")
    val cachePath: String,
    val dimension: Int,
)

/** 单帧规则或模型质量评分。 */
@Entity(
    tableName = "media_quality_results",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["media_asset_id", "timestamp_millis", "analyzer_version"],
            unique = true,
        ),
        Index("media_asset_id"),
    ],
)
data class MediaQualityResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "media_asset_id")
    val mediaAssetId: Long,
    @ColumnInfo(name = "timestamp_millis")
    val timestampMillis: Long,
    @ColumnInfo(name = "analyzer_version")
    val analyzerVersion: String,
    @ColumnInfo(name = "total_score")
    val totalScore: Float,
    @ColumnInfo(name = "sharpness_score")
    val sharpnessScore: Float,
    @ColumnInfo(name = "exposure_score")
    val exposureScore: Float,
    @ColumnInfo(name = "noise_score")
    val noiseScore: Float,
    @ColumnInfo(name = "composition_score")
    val compositionScore: Float,
)

/** 批次及其工作项状态汇总。 */
data class AnalysisBatchWithProgress(
    @Embedded
    val batch: AnalysisBatchEntity,
    @ColumnInfo(name = "total_work_items")
    val totalWorkItems: Int,
    @ColumnInfo(name = "completed_work_items")
    val completedWorkItems: Int,
    @ColumnInfo(name = "running_work_items")
    val runningWorkItems: Int,
    @ColumnInfo(name = "pending_work_items")
    val pendingWorkItems: Int,
    @ColumnInfo(name = "failed_work_items")
    val failedWorkItems: Int,
)
