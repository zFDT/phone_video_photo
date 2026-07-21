package com.frameyu.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Upsert

/** 媒体资源表，字段与领域模型 MediaAsset 一一对应。 */
@Entity(
    tableName = "media_assets",
    indices = [Index(value = ["uri"], unique = true)]
)
data class MediaAssetEntity(
    @PrimaryKey
    val id: Long,
    val uri: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "taken_at_millis")
    val takenAtMillis: Long,
    @ColumnInfo(name = "duration_millis")
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "rotation_degrees")
    val rotationDegrees: Int,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "is_hdr")
    val isHdr: Boolean,
    @ColumnInfo(name = "relative_path")
    val relativePath: String?,
    @ColumnInfo(name = "last_modified_millis")
    val lastModifiedMillis: Long,
)

/** 分析候选帧表，候选帧通过媒体资源 ID 关联所属媒体。 */
@Entity(
    tableName = "analysis_candidates",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_asset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("media_asset_id")]
)
data class AnalysisCandidateEntity(
    @PrimaryKey
    val id: Long,
    @ColumnInfo(name = "media_asset_id")
    val mediaAssetId: Long,
    @ColumnInfo(name = "timestamp_millis")
    val timestampMillis: Long,
    val score: Int,
    @ColumnInfo(name = "sharpness_score")
    val sharpnessScore: Int,
    @ColumnInfo(name = "subject_score")
    val subjectScore: Int,
    @ColumnInfo(name = "expression_score")
    val expressionScore: Int,
    @ColumnInfo(name = "composition_score")
    val compositionScore: Int,
    @ColumnInfo(name = "scene_score")
    val sceneScore: Int,
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String?,
    @ColumnInfo(name = "is_selected")
    val isSelected: Boolean,
)

/** 分析任务表，状态使用字符串保存以避免 Room 类型转换依赖。 */
@Entity(
    tableName = "analysis_tasks",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_asset_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("media_asset_id")]
)
data class AnalysisTaskEntity(
    @PrimaryKey
    val id: Long,
    @ColumnInfo(name = "media_asset_id")
    val mediaAssetId: Long,
    val status: String,
    val progress: Int,
    @ColumnInfo(name = "current_timestamp_millis")
    val currentTimestampMillis: Long,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long,
)

/** 保存和读取任务进度。 */
@Dao
interface AnalysisTaskDao {
    @Upsert
    suspend fun saveTaskProgress(task: AnalysisTaskEntity)

    @Query("SELECT * FROM analysis_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskProgress(taskId: Long): AnalysisTaskEntity?
}

@Database(
    entities = [MediaAssetEntity::class, AnalysisCandidateEntity::class, AnalysisTaskEntity::class],
    version = 1,
    name = "frameyu.db",
    exportSchema = false
)
abstract class FrameYuDatabase : RoomDatabase() {
    abstract fun mediaAssetDao(): MediaAssetDao

    abstract fun analysisCandidateDao(): AnalysisCandidateDao

    abstract fun analysisTaskDao(): AnalysisTaskDao
}
