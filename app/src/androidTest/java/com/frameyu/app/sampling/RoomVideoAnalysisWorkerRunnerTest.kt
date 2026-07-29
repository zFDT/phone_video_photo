package com.frameyu.app.sampling

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.data.db.AnalysisTaskEntity
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.storage.FeatureCache
import com.frameyu.app.storage.FeatureCacheMover
import com.frameyu.app.system.ThermalStatus
import com.frameyu.app.vision.FrameModelAnalyzer
import com.frameyu.app.vision.FrameModelResult
import com.frameyu.app.vision.LabelScore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun `同 generation RUNNING 重投从 checkpoint 续跑并保留历史规则候选`() = runBlocking {
        val plan = SamplingPlan.create(3_000L)
        seed(
            durationMillis = 3_000L,
            task = task(
                status = "RUNNING",
                planId = plan.planId,
                currentTimestampMillis = 1_000L,
                sampledFrameCount = 1L,
                totalSampleCount = 3L,
                executionGeneration = 5L,
            ),
        )
        database.analysisCandidateDao().upsertAll(listOf(candidate()))
        store.saveFrameResult(
            MEDIA_ID,
            0L,
            frameResult(labels = listOf(LabelScore("concert", 0.8f))),
        )
        val source = RecordingFrameSource()
        val analyzer = analyzer {
            frameResult(labels = listOf(LabelScore("concert", 0.9f)))
        }

        val result = runner(analyzer, store, source).run(TASK_ID, 5L) { }

        assertTrue(result is VideoAnalysisTaskResult.Completed)
        assertEquals(listOf(1_000L, 2_000L), source.requestedTimestamps)
        assertTrue(
            database.analysisCandidateDao().getByMediaAssetId(MEDIA_ID)
                .any { it.id == HISTORICAL_CANDIDATE_ID },
        )
        assertEquals("COMPLETED", database.analysisTaskDao().getTaskProgress(TASK_ID)?.status)
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

    @Test
    fun `模型provider取消原样传播并持久化终态且不推进checkpoint`() = runBlocking {
        seed(durationMillis = 1L)
        val cancellation = CancellationException("model provider cancelled")
        val closeFailure = IllegalStateException("frame source close failed")
        val source = RecordingFrameSource(closeFailure)
        val runner = runner(
            analyzerProvider = { throw cancellation },
            frameStore = store,
            source = source,
        )

        val actual = captureCancellation { runner.run(TASK_ID) { } }

        assertSame(cancellation, actual)
        assertEquals(listOf(closeFailure), actual.suppressedExceptions)
        assertCancelledWithoutProgress(source)
    }

    @Test
    fun `温控停止落普通失败并释放领域取消语义`() = runBlocking {
        seed(durationMillis = 1L)
        val source = RecordingFrameSource()
        val runner = runner(
            analyzerProvider = { analyzer { frameResult() } },
            frameStore = store,
            source = source,
            thermalStatusProvider = { ThermalStatus.CRITICAL },
        )

        val result = runner.run(TASK_ID, 0L) { }

        assertTrue(result is VideoAnalysisTaskResult.Failed)
        assertEquals(
            "设备温度过高，已暂停分析",
            (result as VideoAnalysisTaskResult.Failed).error.message,
        )
        val task = database.analysisTaskDao().getTaskProgress(TASK_ID)!!
        assertEquals("FAILED", task.status)
        assertEquals(0L, task.sampledFrameCount)
        assertTrue(source.closed)
        assertTrue(source.requestedTimestamps.isEmpty())
    }

    @Test
    fun `温控持续 PAUSED 时协作取消及时退出且不解码不推进 checkpoint`() = runBlocking {
        seed(durationMillis = 1_000L, task = task(executionGeneration = 5L))
        val source = RecordingFrameSource()
        val lifecycleStore = RoomVideoAnalysisLifecycleStore(database) { 99L }
        val enteredPausedState = CompletableDeferred<Unit>()
        val analysis = async {
            runner(
                analyzerProvider = { analyzer { frameResult() } },
                frameStore = store,
                source = source,
                thermalStatusProvider = {
                    enteredPausedState.complete(Unit)
                    ThermalStatus.MODERATE
                },
                thermalPollIntervalMillis = 10L,
            ).run(TASK_ID, 5L) { }
        }

        withTimeout(500L) { enteredPausedState.await() }
        lifecycleStore.cancel(TASK_ID)
        val result = withTimeout(500L) { analysis.await() }

        assertTrue(result is VideoAnalysisTaskResult.Cancelled)
        assertTrue(source.requestedTimestamps.isEmpty())
        val task = database.analysisTaskDao().getTaskProgress(TASK_ID)!!
        assertEquals("CANCELLED", task.status)
        assertEquals(6L, task.executionGeneration)
        assertEquals(0L, task.sampledFrameCount)
        assertEquals(0L, task.currentTimestampMillis)
    }

    @Test
    fun `旧 generation 节点 no-op 且不创建解码器`() = runBlocking {
        seed(durationMillis = 1L, task = task(executionGeneration = 2L))
        val source = RecordingFrameSource()

        val result = runner(analyzer { frameResult() }, store, source)
            .run(TASK_ID, 1L) { }

        assertEquals(VideoAnalysisTaskResult.Skipped, result)
        assertTrue(source.requestedTimestamps.isEmpty())
        assertTrue(!source.closed)
        val task = database.analysisTaskDao().getTaskProgress(TASK_ID)!!
        assertEquals("QUEUED", task.status)
        assertEquals(2L, task.executionGeneration)
    }

    @Test
    fun `运行中协作取消在下一帧停止并保留 checkpoint`() = runBlocking {
        seed(durationMillis = 3_000L, task = task(executionGeneration = 5L))
        val source = RecordingFrameSource()
        val lifecycleStore = RoomVideoAnalysisLifecycleStore(database) { 99L }

        val result = runner(analyzer { frameResult() }, store, source)
            .run(TASK_ID, 5L) { progress ->
                if (progress.sampledFrameCount == 1L) {
                    lifecycleStore.cancel(TASK_ID)
                }
            }

        assertTrue(result is VideoAnalysisTaskResult.Cancelled)
        assertEquals(listOf(0L), source.requestedTimestamps)
        val task = database.analysisTaskDao().getTaskProgress(TASK_ID)!!
        assertEquals("CANCELLED", task.status)
        assertEquals(6L, task.executionGeneration)
        assertEquals(1L, task.sampledFrameCount)
        assertEquals(1_000L, task.currentTimestampMillis)
    }

    @Test
    fun `旧 runner 不能覆盖新 generation 状态`() = runBlocking {
        seed(durationMillis = 3_000L, task = task(executionGeneration = 5L))
        val source = RecordingFrameSource()
        val lifecycleStore = RoomVideoAnalysisLifecycleStore(database) { 99L }

        val result = runner(analyzer { frameResult() }, store, source)
            .run(TASK_ID, 5L) { progress ->
                if (progress.sampledFrameCount == 1L) {
                    lifecycleStore.cancel(TASK_ID)
                    assertEquals(7L, lifecycleStore.prepareExecution(TASK_ID).executionGeneration)
                }
            }

        assertTrue(result is VideoAnalysisTaskResult.Cancelled)
        assertEquals(listOf(0L), source.requestedTimestamps)
        val task = database.analysisTaskDao().getTaskProgress(TASK_ID)!!
        assertEquals("QUEUED", task.status)
        assertEquals(7L, task.executionGeneration)
        assertEquals(0L, task.sampledFrameCount)
        assertEquals(0L, task.currentTimestampMillis)
    }

    @Test
    fun `首次Room读取前Job已取消仍收敛QUEUED任务`() = runBlocking {
        seed(durationMillis = 1L)
        val cancellation = CancellationException("cancel before room read")
        val source = RecordingFrameSource()

        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().job.cancel(cancellation)
            runner(analyzer { frameResult() }, store, source).run(TASK_ID) { }
        }
        val actual = captureCancellation { deferred.await() }

        assertSame(cancellation, actual)
        assertEquals("CANCELLED", database.analysisTaskDao().getTaskProgress(TASK_ID)?.status)
        assertTrue(source.requestedTimestamps.isEmpty())
    }

    @Test
    fun `更新RUNNING挂起点Job取消后仍收敛任务`() = runBlocking {
        seed(durationMillis = 1L)
        val cancellation = CancellationException("cancel while marking running")
        val source = RecordingFrameSource()

        val deferred = async {
            val analysisJob = currentCoroutineContext().job
            runner(
                analyzerProvider = { analyzer { frameResult() } },
                frameStore = store,
                source = source,
                nowMillis = {
                    analysisJob.cancel(cancellation)
                    99L
                },
            ).run(TASK_ID) { }
        }
        val actual = captureCancellation { deferred.await() }

        assertSame(cancellation, actual)
        assertEquals("CANCELLED", database.analysisTaskDao().getTaskProgress(TASK_ID)?.status)
        assertTrue(source.requestedTimestamps.isEmpty())
    }

    private fun runner(
        analyzer: FrameModelAnalyzer,
        frameStore: RoomVideoFrameModelStore,
        source: VideoFrameSource,
    ) = runner(
        analyzerProvider = { analyzer },
        frameStore = frameStore,
        source = source,
    )

    private fun runner(
        analyzerProvider: () -> FrameModelAnalyzer,
        frameStore: RoomVideoFrameModelStore,
        source: VideoFrameSource,
        thermalStatusProvider: () -> ThermalStatus = { ThermalStatus.NOMINAL },
        thermalPollIntervalMillis: Long = 5_000L,
        nowMillis: () -> Long = { 99L },
    ) = RoomVideoAnalysisWorkerRunner(
        context = context,
        database = database,
        frameModelAnalyzerProvider = analyzerProvider,
        frameModelStoreProvider = { frameStore },
        nowMillis = nowMillis,
        thermalStatusProvider = thermalStatusProvider,
        thermalPollIntervalMillis = thermalPollIntervalMillis,
        frameSourceProvider = { source },
    )

    private suspend fun assertCancelledWithoutProgress(source: RecordingFrameSource) {
        val task = database.analysisTaskDao().getTaskProgress(TASK_ID)!!
        assertEquals("CANCELLED", task.status)
        assertEquals(0L, task.sampledFrameCount)
        assertEquals(0L, task.currentTimestampMillis)
        assertTrue(source.closed)
    }

    private suspend fun captureCancellation(block: suspend () -> Unit): CancellationException =
        try {
            block()
            throw AssertionError("预期传播 CancellationException")
        } catch (cancelled: CancellationException) {
            cancelled
        }

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
        status: String = "QUEUED",
        planId: String? = null,
        currentTimestampMillis: Long = 0L,
        sampledFrameCount: Long = 0L,
        totalSampleCount: Long = 0L,
        executionGeneration: Long = 0L,
    ) = AnalysisTaskEntity(
        id = TASK_ID,
        mediaAssetId = MEDIA_ID,
        status = status,
        progress = 0,
        currentTimestampMillis = currentTimestampMillis,
        errorMessage = null,
        updatedAtMillis = 0L,
        planId = planId,
        rangeIndex = 0,
        sampledFrameCount = sampledFrameCount,
        totalSampleCount = totalSampleCount,
        executionGeneration = executionGeneration,
    )

    private fun candidate() = AnalysisCandidateEntity(
        id = HISTORICAL_CANDIDATE_ID,
        mediaAssetId = MEDIA_ID,
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

    private class RecordingFrameSource(
        private val closeFailure: Throwable? = null,
    ) : VideoFrameSource {
        val requestedTimestamps = mutableListOf<Long>()
        var closed: Boolean = false
            private set

        override fun setDataSource(context: Context, uri: Uri) = Unit

        override fun getFrameAt(timestampMillis: Long): Bitmap {
            requestedTimestamps += timestampMillis
            return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }

    private companion object {
        const val MEDIA_ID = 1L
        const val TASK_ID = 2L
        const val HISTORICAL_CANDIDATE_ID = 99L
        val CLASSIFICATION_SHA = "a".repeat(64)
        val EMBEDDING_SHA = "b".repeat(64)
        val DETECTION_SHA = "c".repeat(64)
    }
}
