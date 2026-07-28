package com.frameyu.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import com.frameyu.app.analysis.DefaultImageAnalysisStageExecutor
import com.frameyu.app.analysis.ImageAnalysisCoordinator
import com.frameyu.app.analysis.ImageAnalysisModelShas
import com.frameyu.app.analysis.RoomImageAnalysisStore
import com.frameyu.app.analysis.batch.BatchAnalysisRunner
import com.frameyu.app.analysis.batch.BatchAnalysisScheduler
import com.frameyu.app.analysis.batch.BatchAnalysisStore
import com.frameyu.app.analysis.batch.BatchAnalysisWorker
import com.frameyu.app.analysis.batch.BatchAnalysisWorkerFactory
import com.frameyu.app.analysis.batch.ImageBatchWorkItemCoordinator
import com.frameyu.app.analysis.batch.RoomBatchAnalysisStore
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.media.MediaStoreRepository
import com.frameyu.app.data.media.MediaStoreScanner
import com.frameyu.app.sampling.RoomVideoAnalysisWorkerRunner
import com.frameyu.app.sampling.VideoAnalysisWorkScheduler
import com.frameyu.app.sampling.VideoAnalysisWorker
import com.frameyu.app.sampling.VideoAnalysisWorkerFactory
import com.frameyu.app.sampling.VideoAnalysisWorkerRunner
import com.frameyu.app.settings.SettingsStore
import com.frameyu.app.storage.FeatureCache
import com.frameyu.app.system.AndroidThermalStatusProvider
import com.frameyu.app.vision.MediaImageDecoder
import com.frameyu.app.vision.MediaPipeImageClassifierAnalyzer
import com.frameyu.app.vision.MediaPipeImageEmbeddingAnalyzer
import com.frameyu.app.vision.MediaPipeObjectDetectorAnalyzer
import com.frameyu.app.vision.model.ModelAssetCatalog
import com.frameyu.app.vision.model.ModelDescriptor
import com.frameyu.app.vision.model.ModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FrameYuApplication : Application(), Configuration.Provider {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: FrameYuDatabase
        private set

    lateinit var videoAnalysisWorkScheduler: VideoAnalysisWorkScheduler
        private set

    val batchAnalysisWorkScheduler: BatchAnalysisScheduler by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        BatchAnalysisScheduler.from(this, batchAnalysisStore)
    }

    lateinit var mediaStoreRepository: MediaStoreRepository
        private set

    var videoAnalysisWorkerRunner: VideoAnalysisWorkerRunner = VideoAnalysisWorkerRunner { _, _ ->
        error("尚未配置视频分析执行器")
    }

    private val batchAnalysisStore: BatchAnalysisStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomBatchAnalysisStore(database.batchAnalysisDao())
    }

    private val modelCatalog: ModelAssetCatalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        assets.open(MODEL_MANIFEST_PATH).use(ModelAssetCatalog::parse)
    }

    private val classifierModel: ModelDescriptor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        modelCatalog.require(ModelId.IMAGE_CLASSIFIER)
    }

    private val embedderModel: ModelDescriptor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        modelCatalog.require(ModelId.IMAGE_EMBEDDER)
    }

    private val detectorModel: ModelDescriptor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        modelCatalog.require(ModelId.OBJECT_DETECTOR)
    }

    private val imageClassifierAnalyzer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MediaPipeImageClassifierAnalyzer(this, classifierModel.assetPath())
    }

    private val imageEmbeddingAnalyzer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MediaPipeImageEmbeddingAnalyzer(this, embedderModel.assetPath())
    }

    private val objectDetectorAnalyzer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MediaPipeObjectDetectorAnalyzer(this, detectorModel.assetPath())
    }

    private val imageAnalysisCoordinator: ImageAnalysisCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
        ::createImageAnalysisCoordinator,
    )

    val batchAnalysisRunner: BatchAnalysisRunner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BatchAnalysisRunner(
            store = batchAnalysisStore,
            coordinatorProvider = {
                ImageBatchWorkItemCoordinator(imageAnalysisCoordinator)
            },
        )
    }

    override val workManagerConfiguration: Configuration
        get() {
            val workerFactory = DelegatingWorkerFactory().apply {
                addFactory(
                    VideoAnalysisWorkerFactory(
                        runnerProvider = { videoAnalysisWorkerRunner },
                    ),
                )
                addFactory(
                    BatchAnalysisWorkerFactory(
                        runnerProvider = { batchAnalysisRunner },
                    ),
                )
            }
            return Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
        }

    override fun onCreate() {
        super.onCreate()
        createVideoAnalysisNotificationChannel()
        createBatchAnalysisNotificationChannel()
        database = Room.databaseBuilder(this, FrameYuDatabase::class.java, "frameyu.db")
            .addMigrations(
                FrameYuDatabase.MIGRATION_1_2,
                FrameYuDatabase.MIGRATION_2_3,
                FrameYuDatabase.MIGRATION_3_4,
            )
            .build()
        val settingsStore = SettingsStore(getSharedPreferences(SettingsStore.PREFERENCES_NAME, MODE_PRIVATE))
        videoAnalysisWorkerRunner = RoomVideoAnalysisWorkerRunner(
            context = this,
            database = database,
            concertModeEnabledProvider = { settingsStore.read().concertModeEnabled },
            thermalStatusProvider = AndroidThermalStatusProvider(this)::current,
        )
        videoAnalysisWorkScheduler = VideoAnalysisWorkScheduler.from(this)
        mediaStoreRepository = MediaStoreRepository(MediaStoreScanner(contentResolver, database.mediaAssetDao()))
        applicationScope.launch {
            try {
                val reconciliation = batchAnalysisWorkScheduler.reconcilePendingBatches()
                reconciliation.failures.forEach { (batchId, error) ->
                    Log.e(TAG, "批量任务启动对账入队失败 batchId=$batchId", error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "批量任务启动对账失败", error)
            }
        }
    }

    private fun createVideoAnalysisNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    VideoAnalysisWorker.CHANNEL_ID,
                    "视频分析",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun createBatchAnalysisNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    BatchAnalysisWorker.CHANNEL_ID,
                    "批量分析",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun createImageAnalysisCoordinator(): ImageAnalysisCoordinator {
        val stageExecutor = DefaultImageAnalysisStageExecutor(
            decoder = MediaImageDecoder(contentResolver),
            classifierProvider = { imageClassifierAnalyzer },
            embedderProvider = { imageEmbeddingAnalyzer },
            detectorProvider = { objectDetectorAnalyzer },
        )
        return ImageAnalysisCoordinator(
            store = RoomImageAnalysisStore(database.mediaAssetDao(), database.batchAnalysisDao()),
            featureCache = FeatureCache(filesDir),
            stageExecutor = stageExecutor,
            modelShas = ImageAnalysisModelShas(
                classification = classifierModel.sha256,
                embedding = embedderModel.sha256,
                detection = detectorModel.sha256,
            ),
        )
    }

    private fun ModelDescriptor.assetPath(): String = "models/$fileName"

    private companion object {
        const val TAG = "FrameYuApplication"
        const val MODEL_MANIFEST_PATH = "models/model-manifest.properties"
    }
}
