package com.frameyu.app.sampling

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frameyu.app.data.db.AnalysisTaskEntity
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.storage.FeatureCache
import com.frameyu.app.storage.FeatureCacheMover
import com.frameyu.app.vision.FrameModelAnalyzer
import com.frameyu.app.vision.FrameModelResult
import com.frameyu.app.vision.LabelScore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomVideoAnalysisWorkerRunnerTest {
    private lateinit var context: Context
    private lateinit var database: FrameYuDatabase
    private lateinit var cacheDirectory: File
    private lateinit var store: RoomVideoFrameModelStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        cacheDirectory = File(context.cacheDir, "video-runner-${System.nanoTime()}").apply {
            check(mkdirs())
        }
        store = createStore(FeatureCache(cacheDirectory))
    }

    @After
    fun tearDown() {
        database.close()
        cacheDirectory.deleteRecursively()
    }

    @Test
    fun `生产runner按规则候选模型结果状态checkpoint顺序持久化`() = runBlocking {
        seed(durationMillis = 1L)
        val events = mutableListOf<String>()
        val analyzer = object : FrameModelAnalyzer {
            override suspend fun analyze(
                bitmap: Bitmap,
                timestampMillis: Long,
                highValue: Boolean,
            ): FrameModelResult {
                val candidate = database.analysisCandidateDao().getByMediaAssetId(MEDIA_ID).single()
                assertEquals(CandidateModelStatus.PENDING.name, candidate.modelStatus)
                events += "candidate-before-analyzer"
                return frameResult(labels = listOf(LabelScore("concert", 0.9f)))
            }
        }
        val source = RecordingFrameSource()

        val result = runner(analyzer, store, source).run(TASK_ID) {
            val candidate = database.analysisCandidateDao().getByMediaAssetId(MEDIA_ID).single()
            assertEquals(CandidateModelStatus.COMPLETED.name, candidate.modelStatus)
            assertEquals(
                listOf("concert"),
                database.batchAnalysisDao()
                    .getClassifications(MEDIA_ID, 0L, CLASSIFICATION_SHA)
                    .map { row -> row.label },
            )
            assertEquals(1L, database.analysisTaskDao().getTaskProgress(TASK_ID)?.sampledFrameCount)
            events += "result-status-before-progress"
        }

        assertTrue(result is VideoAnalysisTaskResult.Completed)
        assertEquals(
            listOf("candidate-before-analyzer", "result-status-before-progress"),
            events,
        )
        assertEquals(listOf(0L), source.requestedTimestamps)
    }

    @Test
    fun `模型结果持久化异常不会推进checkpoint`() = runBlocking {
        seed(durationMillis = 1L)
        val failingCache = FeatureCache(
            cacheDirectory,
            FeatureCacheMover { _, _ -> throw IOException("cache write failed") },
        )
        val failingStore = createStore(failingCache)
        val analyzer = analyzer {
            frameResult(embedding = floatArrayOf(1f, 2f))
        }

        val result = runner(analyzer, failingStore, RecordingFrameSource()).run(TASK_ID) { }

        assertTrue(result is VideoAnalysisTaskResult.Failed)
        val task = database.analysisTaskDao().getTaskProgress(TASK_ID)!!
        assertEquals(0L, task.sampledFrameCount)
        assertEquals(0L, task.currentTimestampMillis)
        assertEquals("FAILED", task.status)
    }

    @Test
    fun `恢复聚合包含checkpoint之前已落库的历史帧`() = runBlocking {
        val plan = SamplingPlan.create(3_000L)
        seed(
            durationMillis = 3_000L,
            task = task(
                planId = plan.planId,
                currentTimestampMillis = 1_000L,
                sampledFrameCount = 1L,
                totalSampleCount = 3L,
            ),
        )
        store.saveFrameResult(
            MEDIA_ID,
            0L,
            frameResult(labels = listOf(LabelScore("concert", 0.8f))),
        )
        val source = RecordingFrameSource()
        val analyzer = analyzer {
            frameResult(labels = listOf(LabelScore("concert", 0.9f)))
        }

        val result = runner(analyzer, store, source).run(TASK_ID) { }

        assertTrue(result is VideoAnalysisTaskResult.Completed)
        assertEquals(listOf(1_000L, 2_000L), source.requestedTimestamps)
        val aggregate = database.batchAnalysisDao().getClassifications(
            MEDIA_ID,
            VIDEO_AGGREGATE_TIMESTAMP_MILLIS,
            CLASSIFICATION_SHA,
        ).single()
        assertEquals("concert", aggregate.label)
        assertEquals(0.94f, aggregate.confidence, 0.0001f)
    }

    @Test
    fun `旧sampling算法planId会忽略checkpoint并从canonical起点重跑`() = runBlocking {
        seed(
            durationMillis = 2_000L,
            task = task(
                planId = "0".repeat(64),
                currentTimestampMillis = 1_000L,
                sampledFrameCount = 1L,
                totalSampleCount = 2L,
            ),
        )
        val source = RecordingFrameSource()

        val result = runner(
            analyzer { frameResult() },
            store,
            source,
        ).run(TASK_ID) { }

        assertTrue(result is VideoAnalysisTaskResult.Completed)
        assertEquals(listOf(0L, 1_000L), source.requestedTimestamps)
    }

    private fun runner(
        analyzer: FrameModelAnalyzer,
        frameStore: RoomVideoFrameModelStore,
        source: VideoFrameSource,
    ) = RoomVideoAnalysisWorkerRunner(
        context = context,
        database = database,
        frameModelAnalyzerProvider = { analyzer },
        frameModelStoreProvider = { frameStore },
        nowMillis = { 99L },
        frameSourceProvider = { source },
    )

    private suspend fun seed(
        durationMillis: Long,
        task: AnalysisTaskEntity = task(),
    ) {
        database.mediaAssetDao().upsertAll(listOf(media(durationMillis)))
        database.analysisTaskDao().saveTaskProgress(task)
    }

    private fun createStore(featureCache: FeatureCache) = RoomVideoFrameModelStore(
        candidateDao = database.analysisCandidateDao(),
        batchAnalysisDao = database.batchAnalysisDao(),
        featureCache = featureCache,
        classificationModelSha256 = CLASSIFICATION_SHA,
        embeddingModelSha256 = EMBEDDING_SHA,
        detectionModelSha256 = DETECTION_SHA,
    )

    private fun analyzer(
        result: suspend (timestampMillis: Long) -> FrameModelResult,
    ) = object : FrameModelAnalyzer {
        override suspend fun analyze(
            bitmap: Bitmap,
            timestampMillis: Long,
            highValue: Boolean,
        ): FrameModelResult = result(timestampMillis)
    }

    private fun frameResult(
        labels: List<LabelScore> = emptyList(),
        embedding: FloatArray? = null,
    ) = FrameModelResult(labels, embedding, emptyList(), emptyList())

    private fun media(durationMillis: Long) = MediaAssetEntity(
        id = MEDIA_ID,
        uri = "content://media/video/$MEDIA_ID",
        mimeType = "video/mp4",
        takenAtMillis = 0L,
        durationMillis = durationMillis,
        width = 2,
        height = 2,
        rotationDegrees = 0,
        sizeBytes = 1L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )

    private fun task(
        planId: String? = null,
        currentTimestampMillis: Long = 0L,
        sampledFrameCount: Long = 0L,
        totalSampleCount: Long = 0L,
    ) = AnalysisTaskEntity(
        id = TASK_ID,
        mediaAssetId = MEDIA_ID,
        status = "QUEUED",
        progress = 0,
        currentTimestampMillis = currentTimestampMillis,
        errorMessage = null,
        updatedAtMillis = 0L,
        planId = planId,
        rangeIndex = 0,
        sampledFrameCount = sampledFrameCount,
        totalSampleCount = totalSampleCount,
    )

    private class RecordingFrameSource : VideoFrameSource {
        val requestedTimestamps = mutableListOf<Long>()

        override fun setDataSource(context: Context, uri: Uri) = Unit

        override fun getFrameAt(timestampMillis: Long): Bitmap {
            requestedTimestamps += timestampMillis
            return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }

        override fun close() = Unit
    }

    private companion object {
        const val MEDIA_ID = 1L
        const val TASK_ID = 2L
        val CLASSIFICATION_SHA = "a".repeat(64)
        val EMBEDDING_SHA = "b".repeat(64)
        val DETECTION_SHA = "c".repeat(64)
    }
}
