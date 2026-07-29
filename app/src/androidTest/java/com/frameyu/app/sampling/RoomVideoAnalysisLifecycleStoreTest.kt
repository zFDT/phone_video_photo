package com.frameyu.app.sampling

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.AnalysisTaskEntity
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomVideoAnalysisLifecycleStoreTest {
    private lateinit var database: FrameYuDatabase
    private lateinit var store: RoomVideoAnalysisLifecycleStore

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        database.mediaAssetDao().upsertAll(listOf(media(1L), media(2L)))
        store = RoomVideoAnalysisLifecycleStore(database) { 99L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `首次准备创建 generation0 排队任务`() = runBlocking {
        val execution = store.prepareExecution(1L)

        assertEquals(VideoAnalysisExecutionKey(1L, 0L), execution)
        val task = database.analysisTaskDao().getTaskProgress(1L)!!
        assertEquals("QUEUED", task.status)
        assertEquals(0L, task.executionGeneration)
    }

    @Test
    fun `终态重试原子提升 generation 并清空错误与 checkpoint`() = runBlocking {
        database.analysisTaskDao().saveTaskProgress(
            task(
                status = "FAILED",
                generation = 4L,
                errorMessage = "解码失败",
                progress = 50,
                currentTimestampMillis = 500L,
            ),
        )

        val execution = store.prepareExecution(1L)

        assertEquals(VideoAnalysisExecutionKey(1L, 5L), execution)
        val task = database.analysisTaskDao().getTaskProgress(1L)!!
        assertEquals("QUEUED", task.status)
        assertEquals(0, task.progress)
        assertEquals(0L, task.currentTimestampMillis)
        assertNull(task.errorMessage)
        assertNull(task.planId)
    }

    @Test
    fun `活动任务重复准备保持 generation 和 checkpoint`() = runBlocking {
        database.analysisTaskDao().saveTaskProgress(
            task(
                status = "RUNNING",
                generation = 3L,
                progress = 50,
                currentTimestampMillis = 500L,
            ),
        )

        val execution = store.prepareExecution(1L)

        assertEquals(VideoAnalysisExecutionKey(1L, 3L), execution)
        assertEquals(
            500L,
            database.analysisTaskDao().getTaskProgress(1L)?.currentTimestampMillis,
        )
    }

    @Test
    fun `取消单个和全部活动任务只更新数据库并提升 generation`() = runBlocking {
        database.analysisTaskDao().saveTaskProgress(task(status = "RUNNING", generation = 2L))
        database.analysisTaskDao().saveTaskProgress(
            task(id = 2L, status = "QUEUED", generation = 7L),
        )

        store.cancel(1L)
        store.cancelAll()

        val first = database.analysisTaskDao().getTaskProgress(1L)!!
        val second = database.analysisTaskDao().getTaskProgress(2L)!!
        assertEquals("CANCELLED", first.status)
        assertEquals(3L, first.executionGeneration)
        assertEquals("CANCELLED", second.status)
        assertEquals(8L, second.executionGeneration)
    }

    @Test
    fun `同代入队失败落 FAILED 且旧代不能覆盖新状态`() = runBlocking {
        database.analysisTaskDao().saveTaskProgress(task(status = "QUEUED", generation = 2L))

        assertTrue(
            store.markEnqueueFailed(
                VideoAnalysisExecutionKey(1L, 2L),
                "入队失败",
            ),
        )
        assertEquals("FAILED", database.analysisTaskDao().getTaskProgress(1L)?.status)

        val newExecution = store.prepareExecution(1L)
        assertEquals(3L, newExecution.executionGeneration)
        assertFalse(
            store.markEnqueueFailed(
                VideoAnalysisExecutionKey(1L, 2L),
                "旧代迟到",
            ),
        )
        assertEquals("QUEUED", database.analysisTaskDao().getTaskProgress(1L)?.status)
    }

    @Test
    fun `只有成功 claim 的新执行才清理候选帧`() = runBlocking {
        database.analysisTaskDao().saveTaskProgress(task(status = "QUEUED", generation = 2L))
        database.analysisCandidateDao().upsertAll(listOf(candidate()))
        val execution = VideoAnalysisExecutionKey(1L, 2L)

        assertFalse(store.claim(VideoAnalysisExecutionKey(1L, 1L)))
        assertEquals(1, database.analysisCandidateDao().getByMediaAssetId(1L).size)
        assertTrue(store.claim(execution))
        assertTrue(database.analysisCandidateDao().getByMediaAssetId(1L).isEmpty())
        assertFalse(store.claim(execution))
    }

    private fun media(id: Long) = MediaAssetEntity(
        id = id,
        uri = "content://media/$id",
        mimeType = "video/mp4",
        takenAtMillis = 0L,
        durationMillis = 1_000L,
        width = 2,
        height = 2,
        rotationDegrees = 0,
        sizeBytes = 1L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )

    private fun task(
        id: Long = 1L,
        status: String,
        generation: Long,
        errorMessage: String? = null,
        progress: Int = 0,
        currentTimestampMillis: Long = 0L,
    ) = AnalysisTaskEntity(
        id = id,
        mediaAssetId = id,
        status = status,
        progress = progress,
        currentTimestampMillis = currentTimestampMillis,
        errorMessage = errorMessage,
        updatedAtMillis = 1L,
        planId = if (currentTimestampMillis > 0L) "plan" else null,
        sampledFrameCount = if (currentTimestampMillis > 0L) 1L else 0L,
        totalSampleCount = if (currentTimestampMillis > 0L) 2L else 0L,
        executionGeneration = generation,
    )

    private fun candidate() = AnalysisCandidateEntity(
        id = 11L,
        mediaAssetId = 1L,
        timestampMillis = 0L,
        score = 1,
        sharpnessScore = 1,
        subjectScore = 1,
        expressionScore = 1,
        compositionScore = 1,
        sceneScore = 1,
        thumbnailPath = null,
        isSelected = false,
    )
}
