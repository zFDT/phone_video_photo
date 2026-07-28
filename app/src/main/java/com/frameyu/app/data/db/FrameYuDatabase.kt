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
import kotlinx.coroutines.flow.Flow

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
    @ColumnInfo(name = "is_cover")
    val isCover: Boolean = false,
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

    @Query("SELECT * FROM analysis_tasks")
    fun observeTasks(): Flow<List<AnalysisTaskEntity>>

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
        SET status = 'CANCELLED',
            error_message = :errorMessage,
            updated_at_millis = :updatedAtMillis
        WHERE status IN ('QUEUED', 'RUNNING')
        """
    )
    suspend fun cancelActiveTasks(errorMessage: String?, updatedAtMillis: Long)

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
    entities = [
        MediaAssetEntity::class,
        AnalysisCandidateEntity::class,
        AnalysisTaskEntity::class,
        AnalysisBatchEntity::class,
        AnalysisWorkItemEntity::class,
        MediaClassificationEntity::class,
        MediaDetectionEntity::class,
        MediaFeatureReferenceEntity::class,
        MediaQualityResultEntity::class,
    ],
    version = 5
)
abstract class FrameYuDatabase : RoomDatabase() {
    abstract fun mediaAssetDao(): MediaAssetDao

    abstract fun analysisCandidateDao(): AnalysisCandidateDao

    abstract fun analysisTaskDao(): AnalysisTaskDao

    abstract fun batchAnalysisDao(): BatchAnalysisDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN plan_id TEXT")
                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN range_index INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN sampled_frame_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN total_sample_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_candidates ADD COLUMN is_cover INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `analysis_batches` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `status` TEXT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `created_at_millis` INTEGER NOT NULL,
                        `updated_at_millis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_analysis_batches_status` " +
                        "ON `analysis_batches` (`status`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `analysis_work_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `batch_id` INTEGER NOT NULL,
                        `media_asset_id` INTEGER NOT NULL,
                        `stage` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `analyzer_version` TEXT NOT NULL,
                        `next_timestamp_millis` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `error_message` TEXT,
                        `updated_at_millis` INTEGER NOT NULL,
                        FOREIGN KEY(`batch_id`) REFERENCES `analysis_batches`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`media_asset_id`) REFERENCES `media_assets`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_analysis_work_items_batch_id_media_asset_id_stage_analyzer_version` " +
                        "ON `analysis_work_items` " +
                        "(`batch_id`, `media_asset_id`, `stage`, `analyzer_version`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_analysis_work_items_batch_id` " +
                        "ON `analysis_work_items` (`batch_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_analysis_work_items_media_asset_id` " +
                        "ON `analysis_work_items` (`media_asset_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_analysis_work_items_status` " +
                        "ON `analysis_work_items` (`status`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_analysis_work_items_batch_id_status` " +
                        "ON `analysis_work_items` (`batch_id`, `status`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_classifications` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `media_asset_id` INTEGER NOT NULL,
                        `timestamp_millis` INTEGER NOT NULL,
                        `model_sha256` TEXT NOT NULL,
                        `rank` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        FOREIGN KEY(`media_asset_id`) REFERENCES `media_assets`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_media_classifications_media_asset_id_timestamp_millis_model_sha256_rank` " +
                        "ON `media_classifications` " +
                        "(`media_asset_id`, `timestamp_millis`, `model_sha256`, `rank`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_classifications_media_asset_id` " +
                        "ON `media_classifications` (`media_asset_id`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_detections` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `media_asset_id` INTEGER NOT NULL,
                        `timestamp_millis` INTEGER NOT NULL,
                        `model_sha256` TEXT NOT NULL,
                        `rank` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `left` REAL NOT NULL,
                        `top` REAL NOT NULL,
                        `right` REAL NOT NULL,
                        `bottom` REAL NOT NULL,
                        FOREIGN KEY(`media_asset_id`) REFERENCES `media_assets`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_media_detections_media_asset_id_timestamp_millis_model_sha256_rank` " +
                        "ON `media_detections` " +
                        "(`media_asset_id`, `timestamp_millis`, `model_sha256`, `rank`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_detections_media_asset_id` " +
                        "ON `media_detections` (`media_asset_id`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_feature_references` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `media_asset_id` INTEGER NOT NULL,
                        `timestamp_millis` INTEGER NOT NULL,
                        `model_sha256` TEXT NOT NULL,
                        `cache_path` TEXT NOT NULL,
                        `dimension` INTEGER NOT NULL,
                        FOREIGN KEY(`media_asset_id`) REFERENCES `media_assets`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_media_feature_references_media_asset_id_timestamp_millis_model_sha256` " +
                        "ON `media_feature_references` " +
                        "(`media_asset_id`, `timestamp_millis`, `model_sha256`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_feature_references_media_asset_id` " +
                        "ON `media_feature_references` (`media_asset_id`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_quality_results` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `media_asset_id` INTEGER NOT NULL,
                        `timestamp_millis` INTEGER NOT NULL,
                        `analyzer_version` TEXT NOT NULL,
                        `total_score` REAL NOT NULL,
                        `sharpness_score` REAL NOT NULL,
                        `exposure_score` REAL NOT NULL,
                        `noise_score` REAL NOT NULL,
                        `composition_score` REAL NOT NULL,
                        FOREIGN KEY(`media_asset_id`) REFERENCES `media_assets`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_media_quality_results_media_asset_id_timestamp_millis_analyzer_version` " +
                        "ON `media_quality_results` " +
                        "(`media_asset_id`, `timestamp_millis`, `analyzer_version`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_quality_results_media_asset_id` " +
                        "ON `media_quality_results` (`media_asset_id`)"
                )
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE analysis_batches " +
                        "ADD COLUMN execution_generation INTEGER NOT NULL DEFAULT 0"
                )
                // 隔离升级前无 generation 的遗留 WorkRequest，终态无需创建新执行代。
                db.execSQL(
                    """
                    UPDATE analysis_batches
                    SET execution_generation = 1
                    WHERE status IN ('QUEUED', 'RUNNING')
                    """.trimIndent()
                )
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
