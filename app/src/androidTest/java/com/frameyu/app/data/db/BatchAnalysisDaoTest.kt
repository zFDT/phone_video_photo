package com.frameyu.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchAnalysisDaoTest {
    @Test
    fun batchWorkItemsSupportCheckpointRetryAndProgress() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val dao = database.batchAnalysisDao()

            val batchId = dao.createBatch(
                batch = batch(),
                items = listOf(
                    workItem(id = 11L, mediaAssetId = 1L, stage = "QUALITY"),
                    workItem(id = 12L, mediaAssetId = 2L, stage = "CLASSIFICATION"),
                    workItem(id = 13L, mediaAssetId = 1L, stage = "EMBEDDING"),
                ),
            )

            val createdItems = dao.nextRunnableItems(batchId, limit = 10)
            assertEquals(listOf(11L, 12L, 13L), createdItems.map { it.id })
            assertTrue(createdItems.all { it.batchId == batchId })

            dao.markRunning(itemId = 11L, updatedAtMillis = 100L)
            assertWorkItem(
                database = database,
                itemId = 11L,
                expectedStatus = "RUNNING",
                expectedAttempts = 1,
                expectedNextTimestampMillis = 0L,
            )
            dao.saveCheckpoint(itemId = 11L, nextTimestampMillis = 750L, updatedAtMillis = 110L)
            assertWorkItem(
                database = database,
                itemId = 11L,
                expectedStatus = "RUNNING",
                expectedAttempts = 1,
                expectedNextTimestampMillis = 750L,
            )
            dao.completeItem(itemId = 11L, updatedAtMillis = 120L)

            dao.markRunning(itemId = 13L, updatedAtMillis = 130L)
            dao.failItem(
                itemId = 13L,
                status = "RETRYABLE_FAILED",
                errorMessage = "模型暂不可用",
                updatedAtMillis = 140L,
            )

            val runnableItems = dao.nextRunnableItems(batchId, limit = 10)
            assertEquals(listOf(12L, 13L), runnableItems.map { it.id })
            assertEquals("RETRYABLE_FAILED", runnableItems[1].status)
            assertEquals("模型暂不可用", runnableItems[1].errorMessage)
            val progress = dao.observeBatch(batchId).first()
            assertEquals(3, progress.totalWorkItems)
            assertEquals(1, progress.completedWorkItems)
            assertEquals(0, progress.runningWorkItems)
            assertEquals(2, progress.pendingWorkItems)
            assertEquals(0, progress.failedWorkItems)
        } finally {
            database.close()
        }
    }

    @Test
    fun duplicateWorkItemIdentityIsRejected() = runBlocking {
        val database = createDatabase()
        try {
            database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
            val duplicate = workItem(id = 0L, mediaAssetId = 1L, stage = "QUALITY")

            val failure = runCatching {
                database.batchAnalysisDao().createBatch(
                    batch = batch(),
                    items = listOf(duplicate, duplicate.copy(mediaAssetId = 1L)),
                )
            }.exceptionOrNull()

            assertTrue(failure is SQLiteConstraintException)
        } finally {
            database.close()
        }
    }

    private fun createDatabase(): FrameYuDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
    }

    private fun batch() = AnalysisBatchEntity(
        status = "PENDING",
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private fun workItem(
        id: Long,
        mediaAssetId: Long,
        stage: String,
    ) = AnalysisWorkItemEntity(
        id = id,
        batchId = 0L,
        mediaAssetId = mediaAssetId,
        stage = stage,
        status = "PENDING",
        analyzerVersion = "analyzer-v1",
        nextTimestampMillis = 0L,
        attempts = 0,
        errorMessage = null,
        updatedAtMillis = 1L,
    )

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

    private fun assertWorkItem(
        database: FrameYuDatabase,
        itemId: Long,
        expectedStatus: String,
        expectedAttempts: Int,
        expectedNextTimestampMillis: Long,
    ) {
        database.openHelper.readableDatabase.query(
            "SELECT status, attempts, next_timestamp_millis FROM analysis_work_items WHERE id = ?",
            arrayOf(itemId.toString()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedStatus, cursor.getString(0))
            assertEquals(expectedAttempts, cursor.getInt(1))
            assertEquals(expectedNextTimestampMillis, cursor.getLong(2))
        }
    }
}
