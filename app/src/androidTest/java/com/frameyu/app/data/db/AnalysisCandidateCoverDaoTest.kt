package com.frameyu.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisCandidateCoverDaoTest {
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
    fun migration2To3AddsIsCoverWithFalseDefault() {
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
            .addMigrations(FrameYuDatabase.MIGRATION_2_3)
            .build()
        try {
            val columns = roomDatabase.openHelper.readableDatabase.query("PRAGMA table_info(analysis_candidates)")
            var foundCoverColumn = false
            columns.use {
                while (it.moveToNext()) {
                    if (it.getString(it.getColumnIndexOrThrow("name")) == "is_cover") {
                        foundCoverColumn = true
                        assertTrue(it.getString(it.getColumnIndexOrThrow("dflt_value")) == "0")
                    }
                }
            }
            assertTrue(foundCoverColumn)
        } finally {
            roomDatabase.close()
            context.deleteDatabase(databaseName)
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
