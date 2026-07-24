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
import com.frameyu.app.sampling.VideoAnalysisProgress

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
    @ColumnInfo(name = "plan_id")
    val planId: String? = null,
    @ColumnInfo(name = "range_index")
    val rangeIndex: Int = 0,
    @ColumnInfo(name = "sampled_frame_count")
    val sampledFrameCount: Long = 0L,
    @ColumnInfo(name = "total_sample_count")
    val totalSampleCount: Long = 0L,
)

/** 保存和读取任务进度。 */
@Dao
interface AnalysisTaskDao {
    @Upsert
    suspend fun saveTaskProgress(task: AnalysisTaskEntity)

    @Query("SELECT * FROM analysis_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskProgress(taskId: Long): AnalysisTaskEntity?

    @Query(
        """
        UPDATE analysis_tasks
        SET status = :status,
            error_message = :errorMessage,
            updated_at_millis = :updatedAtMillis
        WHERE id = :taskId
        """
    )
    suspend fun updateTaskStatus(
        taskId: Long,
        status: String,
        errorMessage: String?,
        updatedAtMillis: Long,
    )

    @Query(
        """
        UPDATE analysis_tasks
        SET progress = :progress,
            current_timestamp_millis = :nextTimestampMillis,
            updated_at_millis = :updatedAtMillis,
            plan_id = :planId,
            range_index = :rangeIndex,
            sampled_frame_count = :sampledFrameCount,
            total_sample_count = :totalSampleCount
        WHERE id = :taskId
        """
    )
    suspend fun saveSamplingProgress(
        taskId: Long,
        progress: Int,
        nextTimestampMillis: Long,
        updatedAtMillis: Long,
        planId: String,
        rangeIndex: Int,
        sampledFrameCount: Long,
        totalSampleCount: Long,
    )
}

@Database(
    entities = [MediaAssetEntity::class, AnalysisCandidateEntity::class, AnalysisTaskEntity::class],
    version = 2
)
abstract class FrameYuDatabase : RoomDatabase() {
    abstract fun mediaAssetDao(): MediaAssetDao

    abstract fun analysisCandidateDao(): AnalysisCandidateDao

    abstract fun analysisTaskDao(): AnalysisTaskDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN plan_id TEXT")
                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN range_index INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN sampled_frame_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN total_sample_count INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

/** 将分析任务的逐帧进度持久化到 Room。 */
class RoomSamplingCheckpointStore(
    private val taskId: Long,
    private val dao: AnalysisTaskDao,
    private val nowMillis: () -> Long,
) : com.frameyu.app.sampling.SamplingCheckpointStore {
    override suspend fun save(progress: VideoAnalysisProgress) {
        val percentage = if (progress.totalSampleCount == 0L) 0 else {
            ((progress.sampledFrameCount * 100L) / progress.totalSampleCount).toInt().coerceIn(0, 100)
        }
        dao.saveSamplingProgress(
            taskId = taskId,
            progress = percentage,
            nextTimestampMillis = progress.nextTimestampMillis,
            updatedAtMillis = nowMillis(),
            planId = progress.planId,
            rangeIndex = progress.rangeIndex,
            sampledFrameCount = progress.sampledFrameCount,
            totalSampleCount = progress.totalSampleCount,
        )
    }
}
