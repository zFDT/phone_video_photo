package com.frameyu.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisCandidateCoverDaoTest {
    @Test
    fun observeMediaCountReflectsPersistedAssets() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        try {
            assertEquals(0, database.mediaAssetDao().observeCount().first())

            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))

            assertEquals(2, database.mediaAssetDao().observeCount().first())
        } finally {
            database.close()
        }
    }

    @Test
    fun deleteMediaByUrisOnlyRemovesRequestedAssetsAndCascadesCandidates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            database.analysisCandidateDao().upsertAll(
                listOf(candidate(11L, 1L), candidate(21L, 2L)),
            )

            val deleted = database.mediaAssetDao().deleteByUris(listOf("content://media/1"))

            assertEquals(1, deleted)
            assertNull(database.mediaAssetDao().getById(1L))
            assertTrue(database.analysisCandidateDao().getByMediaAssetId(1L).isEmpty())
            assertTrue(database.mediaAssetDao().getById(2L) != null)
            assertEquals(1, database.analysisCandidateDao().getByMediaAssetId(2L).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun candidateSummariesGroupByMediaWithCountAndMaxScore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            database.analysisCandidateDao().upsertAll(
                listOf(
                    candidate(11L, 1L).copy(score = 20),
                    candidate(12L, 1L).copy(score = 80),
                    candidate(21L, 2L).copy(score = 60),
                ),
            )

            val summaries = database.analysisCandidateDao().observeSummaries().first()

            assertEquals(
                listOf(
                    AnalysisCandidateSummary(1L, 2L, 80),
                    AnalysisCandidateSummary(2L, 1L, 60),
                ),
                summaries,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun setCoverClearsOtherCandidatesForSameMedia() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            database.analysisCandidateDao().upsertAll(
                listOf(candidate(11L, 1L), candidate(12L, 1L), candidate(21L, 2L, isCover = true)),
            )

            database.analysisCandidateDao().setCover(1L, 12L)

            assertFalse(database.analysisCandidateDao().getByMediaAssetId(1L).first { it.id == 11L }.isCover)
            assertTrue(database.analysisCandidateDao().getByMediaAssetId(1L).first { it.id == 12L }.isCover)
            assertTrue(database.analysisCandidateDao().getCover(1L)?.id == 12L)
            assertTrue(database.analysisCandidateDao().getCover(2L)?.id == 21L)
            assertTrue(database.analysisCandidateDao().observeCover(1L).first { it?.id == 12L }?.isCover == true)
        } finally {
            database.close()
        }
    }

    @Test
    fun clearOtherCoversRemovesCoverForMedia() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L)))
            database.analysisCandidateDao().upsertAll(listOf(candidate(11L, 1L, isCover = true)))

            database.analysisCandidateDao().clearOtherCovers(1L)

            assertNull(database.analysisCandidateDao().getCover(1L))
        } finally {
            database.close()
        }
    }

    @Test
    fun ruleCandidateAndModelStatusUpdatesPreserveUserFlags() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L)))
            val original = candidate(11L, 1L, isCover = true).copy(
                isSelected = true,
                modelStatus = "COMPLETED",
            )
            database.analysisCandidateDao().upsertAll(listOf(original))

            database.analysisCandidateDao().upsertRuleCandidate(
                original.copy(
                    score = 99,
                    isSelected = false,
                    isCover = false,
                    modelStatus = "PENDING",
                ),
            )
            database.analysisCandidateDao().updateModelStatus(
                candidateId = original.id,
                status = "RETRYABLE_FAILED",
                errorMessage = "模型失败",
            )

            val updated = database.analysisCandidateDao().getByMediaAssetId(1L).single()
            assertTrue(updated.isSelected)
            assertTrue(updated.isCover)
            assertTrue(updated.score == 99)
            assertTrue(updated.modelStatus == "RETRYABLE_FAILED")
            assertTrue(updated.modelErrorMessage == "模型失败")
        } finally {
            database.close()
        }
    }

    @Test
    fun migration2To6AddsCandidateCoverAndModelStateDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "analysis-candidate-cover-migration.db"
        context.deleteDatabase(databaseName)
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TABLE media_assets (id INTEGER NOT NULL, uri TEXT NOT NULL, mime_type TEXT NOT NULL, taken_at_millis INTEGER NOT NULL, duration_millis INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, rotation_degrees INTEGER NOT NULL, size_bytes INTEGER NOT NULL, is_hdr INTEGER NOT NULL, relative_path TEXT, last_modified_millis INTEGER NOT NULL, PRIMARY KEY(id))")
            database.execSQL("CREATE UNIQUE INDEX index_media_assets_uri ON media_assets(uri)")
            database.execSQL("CREATE TABLE analysis_candidates (id INTEGER NOT NULL, media_asset_id INTEGER NOT NULL, timestamp_millis INTEGER NOT NULL, score INTEGER NOT NULL, sharpness_score INTEGER NOT NULL, subject_score INTEGER NOT NULL, expression_score INTEGER NOT NULL, composition_score INTEGER NOT NULL, scene_score INTEGER NOT NULL, thumbnail_path TEXT, is_selected INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(media_asset_id) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            database.execSQL("CREATE INDEX index_analysis_candidates_media_asset_id ON analysis_candidates(media_asset_id)")
            database.execSQL("CREATE TABLE analysis_tasks (id INTEGER NOT NULL, media_asset_id INTEGER NOT NULL, status TEXT NOT NULL, progress INTEGER NOT NULL, current_timestamp_millis INTEGER NOT NULL, error_message TEXT, updated_at_millis INTEGER NOT NULL, plan_id TEXT, range_index INTEGER NOT NULL, sampled_frame_count INTEGER NOT NULL, total_sample_count INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(media_asset_id) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            database.execSQL("CREATE INDEX index_analysis_tasks_media_asset_id ON analysis_tasks(media_asset_id)")
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, 'b1399d69e37760a84059306c84adfd65')")
            database.version = 2
        }

        val roomDatabase = Room.databaseBuilder(context, FrameYuDatabase::class.java, databaseName)
            .addMigrations(
                FrameYuDatabase.MIGRATION_2_3,
                FrameYuDatabase.MIGRATION_3_4,
                FrameYuDatabase.MIGRATION_4_5,
                FrameYuDatabase.MIGRATION_5_6,
                FrameYuDatabase.MIGRATION_6_7,
            )
            .build()
        try {
            val columns = roomDatabase.openHelper.readableDatabase.query("PRAGMA table_info(analysis_candidates)")
            var foundCoverColumn = false
            var foundModelStatusColumn = false
            var foundModelErrorColumn = false
            columns.use {
                while (it.moveToNext()) {
                    when (it.getString(it.getColumnIndexOrThrow("name"))) {
                        "is_cover" -> {
                            foundCoverColumn = true
                            assertTrue(it.getString(it.getColumnIndexOrThrow("dflt_value")) == "0")
                        }
                        "model_status" -> {
                            foundModelStatusColumn = true
                            assertTrue(it.getString(it.getColumnIndexOrThrow("dflt_value")) == "'PENDING'")
                        }
                        "model_error_message" -> foundModelErrorColumn = true
                    }
                }
            }
            assertTrue(foundCoverColumn)
            assertTrue(foundModelStatusColumn)
            assertTrue(foundModelErrorColumn)
        } finally {
            roomDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migration6To7InvalidatesLegacyActiveVideoWorkAndPreservesTerminalTasks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "video-analysis-generation-migration.db"
        context.deleteDatabase(databaseName)
        createVersion2Database(context, databaseName)
        val seedVersion6Tasks = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                FrameYuDatabase.MIGRATION_5_6.migrate(db)
                db.execSQL(
                    """
                    INSERT INTO media_assets (
                        id, uri, mime_type, taken_at_millis, duration_millis, width, height,
                        rotation_degrees, size_bytes, is_hdr, relative_path, last_modified_millis
                    ) VALUES
                        (1, 'content://media/1', 'video/mp4', 0, 1000, 2, 2, 0, 1, 0, NULL, 0),
                        (2, 'content://media/2', 'video/mp4', 0, 1000, 2, 2, 0, 1, 0, NULL, 0),
                        (3, 'content://media/3', 'video/mp4', 0, 1000, 2, 2, 0, 1, 0, NULL, 0)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO analysis_tasks (
                        id, media_asset_id, status, progress, current_timestamp_millis,
                        error_message, updated_at_millis, plan_id, range_index,
                        sampled_frame_count, total_sample_count
                    ) VALUES
                        (1, 1, 'QUEUED', 0, 0, NULL, 10, NULL, 0, 0, 0),
                        (2, 2, 'RUNNING', 50, 500, NULL, 20, 'plan', 0, 1, 2),
                        (3, 3, 'COMPLETED', 100, 1000, NULL, 30, 'plan', 1, 2, 2)
                    """.trimIndent(),
                )
            }
        }

        val roomDatabase = Room.databaseBuilder(context, FrameYuDatabase::class.java, databaseName)
            .addMigrations(
                FrameYuDatabase.MIGRATION_2_3,
                FrameYuDatabase.MIGRATION_3_4,
                FrameYuDatabase.MIGRATION_4_5,
                seedVersion6Tasks,
                FrameYuDatabase.MIGRATION_6_7,
            )
            .build()
        try {
            val queued = roomDatabase.analysisTaskDao().getTaskProgress(1L)!!
            val running = roomDatabase.analysisTaskDao().getTaskProgress(2L)!!
            val completed = roomDatabase.analysisTaskDao().getTaskProgress(3L)!!

            assertEquals("FAILED", queued.status)
            assertEquals(1L, queued.executionGeneration)
            assertTrue(queued.errorMessage!!.contains("升级"))
            assertEquals("FAILED", running.status)
            assertEquals(1L, running.executionGeneration)
            assertEquals(500L, running.currentTimestampMillis)
            assertEquals("COMPLETED", completed.status)
            assertEquals(0L, completed.executionGeneration)

            roomDatabase.analysisTaskDao().saveTaskProgress(
                AnalysisTaskEntity(
                    id = 4L,
                    mediaAssetId = 3L,
                    status = "QUEUED",
                    progress = 0,
                    currentTimestampMillis = 0L,
                    errorMessage = null,
                    updatedAtMillis = 40L,
                ),
            )
            assertEquals(
                0L,
                roomDatabase.analysisTaskDao().getTaskProgress(4L)?.executionGeneration,
            )
        } finally {
            roomDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun createVersion2Database(context: Context, databaseName: String) {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TABLE media_assets (id INTEGER NOT NULL, uri TEXT NOT NULL, mime_type TEXT NOT NULL, taken_at_millis INTEGER NOT NULL, duration_millis INTEGER NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, rotation_degrees INTEGER NOT NULL, size_bytes INTEGER NOT NULL, is_hdr INTEGER NOT NULL, relative_path TEXT, last_modified_millis INTEGER NOT NULL, PRIMARY KEY(id))")
            database.execSQL("CREATE UNIQUE INDEX index_media_assets_uri ON media_assets(uri)")
            database.execSQL("CREATE TABLE analysis_candidates (id INTEGER NOT NULL, media_asset_id INTEGER NOT NULL, timestamp_millis INTEGER NOT NULL, score INTEGER NOT NULL, sharpness_score INTEGER NOT NULL, subject_score INTEGER NOT NULL, expression_score INTEGER NOT NULL, composition_score INTEGER NOT NULL, scene_score INTEGER NOT NULL, thumbnail_path TEXT, is_selected INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(media_asset_id) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            database.execSQL("CREATE INDEX index_analysis_candidates_media_asset_id ON analysis_candidates(media_asset_id)")
            database.execSQL("CREATE TABLE analysis_tasks (id INTEGER NOT NULL, media_asset_id INTEGER NOT NULL, status TEXT NOT NULL, progress INTEGER NOT NULL, current_timestamp_millis INTEGER NOT NULL, error_message TEXT, updated_at_millis INTEGER NOT NULL, plan_id TEXT, range_index INTEGER NOT NULL, sampled_frame_count INTEGER NOT NULL, total_sample_count INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(media_asset_id) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            database.execSQL("CREATE INDEX index_analysis_tasks_media_asset_id ON analysis_tasks(media_asset_id)")
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES(42, 'b1399d69e37760a84059306c84adfd65')")
            database.version = 2
        }
    }

    private fun media(id: Long) = MediaAssetEntity(
        id = id,
        uri = "content://media/$id",
        mimeType = "video/mp4",
        takenAtMillis = 0L,
        durationMillis = 1_000L,
        width = 1_920,
        height = 1_080,
        rotationDegrees = 0,
        sizeBytes = 1L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )

    private fun candidate(id: Long, mediaAssetId: Long, isCover: Boolean = false) = AnalysisCandidateEntity(
        id = id,
        mediaAssetId = mediaAssetId,
        timestampMillis = 0L,
        score = 1,
        sharpnessScore = 1,
        subjectScore = 1,
        expressionScore = 1,
        compositionScore = 1,
        sceneScore = 1,
        thumbnailPath = null,
        isSelected = false,
        isCover = isCover,
    )
}
