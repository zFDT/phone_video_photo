package com.frameyu.app.sampling

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.storage.FeatureCache
import com.frameyu.app.vision.DetectionObservation
import com.frameyu.app.vision.FrameModelResult
import com.frameyu.app.vision.LabelScore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomVideoFrameModelStoreTest {
    private lateinit var database: FrameYuDatabase
    private lateinit var cacheDirectory: File
    private lateinit var featureCache: FeatureCache
    private lateinit var store: RoomVideoFrameModelStore

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        cacheDirectory = File(context.cacheDir, "video-frame-store-${System.nanoTime()}").apply {
            check(mkdirs())
        }
        featureCache = FeatureCache(cacheDirectory)
        store = RoomVideoFrameModelStore(
            candidateDao = database.analysisCandidateDao(),
            batchAnalysisDao = database.batchAnalysisDao(),
            featureCache = featureCache,
            classificationModelSha256 = CLASSIFICATION_SHA,
            embeddingModelSha256 = EMBEDDING_SHA,
            detectionModelSha256 = DETECTION_SHA,
        )
        database.mediaAssetDao().upsertAll(listOf(media()))
    }

    @After
    fun tearDown() {
        database.close()
        cacheDirectory.deleteRecursively()
    }

    @Test
    fun `同timestamp和模型SHA完整替换分类检测与embedding引用`() = runBlocking {
        store.saveFrameResult(
            MEDIA_ID,
            1_000L,
            frameResult(
                labels = listOf(LabelScore("old-a", 0.8f), LabelScore("old-b", 0.7f)),
                detections = listOf(detection("old-a"), detection("old-b")),
                embedding = floatArrayOf(1f, 2f),
            ),
        )

        store.saveFrameResult(
            MEDIA_ID,
            1_000L,
            frameResult(
                labels = listOf(LabelScore("new", 0.9f)),
                detections = listOf(detection("new")),
                embedding = floatArrayOf(3f, 4f, 5f),
            ),
        )

        assertEquals(
            listOf("new"),
            database.batchAnalysisDao()
                .getClassifications(MEDIA_ID, 1_000L, CLASSIFICATION_SHA)
                .map { it.label },
        )
        assertEquals(
            listOf("new"),
            database.batchAnalysisDao()
                .getDetections(MEDIA_ID, 1_000L, DETECTION_SHA)
                .map { it.category },
        )
        assertArrayEquals(
            floatArrayOf(3f, 4f, 5f),
            featureCache.read(EMBEDDING_SHA, MEDIA_ID, 1_000L),
            0f,
        )
    }

    @Test
    fun `embedding按timestamp隔离且无embedding会清理对应引用`() = runBlocking {
        store.saveFrameResult(MEDIA_ID, 0L, frameResult(embedding = floatArrayOf(1f)))
        store.saveFrameResult(MEDIA_ID, 1_000L, frameResult(embedding = floatArrayOf(2f)))

        assertArrayEquals(floatArrayOf(1f), featureCache.read(EMBEDDING_SHA, MEDIA_ID), 0f)
        assertArrayEquals(
            floatArrayOf(2f),
            featureCache.read(EMBEDDING_SHA, MEDIA_ID, 1_000L),
            0f,
        )
        store.saveFrameResult(MEDIA_ID, 1_000L, frameResult(embedding = null))

        assertNull(
            database.batchAnalysisDao().getFeatureReference(MEDIA_ID, 1_000L, EMBEDDING_SHA),
        )
        assertEquals(
            featureCache.fileFor(EMBEDDING_SHA, MEDIA_ID).absolutePath,
            database.batchAnalysisDao()
                .getFeatureReference(MEDIA_ID, 0L, EMBEDDING_SHA)
                ?.cachePath,
        )
    }

    @Test
    fun `candidate状态保留用户标记且聚合sentinel幂等隔离`() = runBlocking {
        val selectedCover = candidate().copy(isSelected = true, isCover = true)
        database.analysisCandidateDao().upsertAll(listOf(selectedCover))

        store.saveRuleCandidate(
            selectedCover.copy(
                score = 88,
                isSelected = false,
                isCover = false,
                modelStatus = CandidateModelStatus.PENDING.name,
            ),
        )
        store.updateCandidateModelStatus(
            selectedCover.id,
            CandidateModelStatus.COMPLETED,
            null,
        )
        val savedCandidate = database.analysisCandidateDao().getByMediaAssetId(MEDIA_ID).single()
        assertEquals(88, savedCandidate.score)
        assertEquals(true, savedCandidate.isSelected)
        assertEquals(true, savedCandidate.isCover)
        assertEquals(CandidateModelStatus.COMPLETED.name, savedCandidate.modelStatus)

        store.saveFrameResult(
            MEDIA_ID,
            1_000L,
            frameResult(labels = listOf(LabelScore("frame", 0.8f))),
        )
        store.replaceAggregateLabels(
            MEDIA_ID,
            CLASSIFICATION_SHA,
            VIDEO_AGGREGATE_TIMESTAMP_MILLIS,
            listOf(AggregatedVideoLabel("old", 0.8f, 1f, 0.8f, 0.8f, 1)),
        )
        store.replaceAggregateLabels(
            MEDIA_ID,
            CLASSIFICATION_SHA,
            VIDEO_AGGREGATE_TIMESTAMP_MILLIS,
            listOf(AggregatedVideoLabel("new", 0.9f, 1f, 0.9f, 0.9f, 1)),
        )

        assertEquals(
            listOf("frame"),
            store.loadFrameLabels(MEDIA_ID, CLASSIFICATION_SHA)
                .flatMap { timed -> timed.labels.map(LabelScore::label) },
        )
        assertEquals(
            listOf("new"),
            database.batchAnalysisDao()
                .getClassifications(
                    MEDIA_ID,
                    VIDEO_AGGREGATE_TIMESTAMP_MILLIS,
                    CLASSIFICATION_SHA,
                )
                .map { it.label },
        )
    }

    private fun media() = MediaAssetEntity(
        id = MEDIA_ID,
        uri = "content://media/video/$MEDIA_ID",
        mimeType = "video/mp4",
        takenAtMillis = 0L,
        durationMillis = 2_000L,
        width = 100,
        height = 100,
        rotationDegrees = 0,
        sizeBytes = 1L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )

    private fun candidate() = AnalysisCandidateEntity(
        id = 7L,
        mediaAssetId = MEDIA_ID,
        timestampMillis = 1_000L,
        score = 70,
        sharpnessScore = 70,
        subjectScore = 0,
        expressionScore = 0,
        compositionScore = 0,
        sceneScore = 10,
        thumbnailPath = null,
        isSelected = false,
        isCover = false,
    )

    private fun frameResult(
        labels: List<LabelScore> = emptyList(),
        embedding: FloatArray? = null,
        detections: List<DetectionObservation> = emptyList(),
    ) = FrameModelResult(labels, embedding, detections, emptyList())

    private fun detection(label: String) = DetectionObservation(
        label = label,
        score = 0.8f,
        left = 0.1f,
        top = 0.1f,
        right = 0.9f,
        bottom = 0.9f,
    )

    private companion object {
        const val MEDIA_ID = 1L
        val CLASSIFICATION_SHA = "a".repeat(64)
        val EMBEDDING_SHA = "b".repeat(64)
        val DETECTION_SHA = "c".repeat(64)
    }
}
