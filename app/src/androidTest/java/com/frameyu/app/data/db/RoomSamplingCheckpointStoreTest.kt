package com.frameyu.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frameyu.app.sampling.VideoAnalysisProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSamplingCheckpointStoreTest {
    @Test
    fun `逐帧进度写入分析任务并可读取`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        try {
            database.mediaAssetDao().upsertAll(
                listOf(
                    MediaAssetEntity(
                        id = 1L,
                        uri = "content://media/1",
                        mimeType = "video/mp4",
                        takenAtMillis = 0L,
                        durationMillis = 30_000L,
                        width = 1_920,
                        height = 1_080,
                        rotationDegrees = 0,
                        sizeBytes = 1L,
                        isHdr = false,
                        relativePath = "Movies",
                        lastModifiedMillis = 0L,
                    ),
                ),
            )
            database.analysisTaskDao().saveTaskProgress(
                AnalysisTaskEntity(7L, 1L, "RUNNING", 0, 0L, null, 1L),
            )

            val store = RoomSamplingCheckpointStore(7L, database.analysisTaskDao()) { 99L }
            val progress = VideoAnalysisProgress("plan-1", 2, 12_500L, 125L, 500L)
            store.save(progress)

            val task = database.analysisTaskDao().getTaskProgress(7L)!!
            assertEquals("plan-1", task.planId)
            assertEquals(2, task.rangeIndex)
            assertEquals(12_500L, task.currentTimestampMillis)
            assertEquals(25, task.progress)
            assertEquals(125L, task.sampledFrameCount)
            assertEquals(500L, task.totalSampleCount)
            assertEquals(99L, task.updatedAtMillis)
        } finally {
            database.close()
        }
    }
}
