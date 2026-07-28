package com.frameyu.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
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

class FrameYuApplication : Application(), Configuration.Provider {
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

    private val imageAnalysisCoordinator: ImageAnalysisCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
        ::createImageAnalysisCoordinator,
    )

    val batchAnalysisRunner: BatchAnalysisRunner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BatchAnalysisRunner(
            store = batchAnalysisStore,
            coordinator = ImageBatchWorkItemCoordinator(imageAnalysisCoordinator),
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
        val catalog = assets.open(MODEL_MANIFEST_PATH).use(ModelAssetCatalog::parse)
        val classifier = catalog.require(ModelId.IMAGE_CLASSIFIER)
        val detector = catalog.require(ModelId.OBJECT_DETECTOR)
        val embedder = catalog.require(ModelId.IMAGE_EMBEDDER)
        val stageExecutor = DefaultImageAnalysisStageExecutor(
            decoder = MediaImageDecoder(contentResolver),
            classifier = MediaPipeImageClassifierAnalyzer(this, classifier.assetPath()),
            embedder = MediaPipeImageEmbeddingAnalyzer(this, embedder.assetPath()),
            detector = MediaPipeObjectDetectorAnalyzer(this, detector.assetPath()),
        )
        return ImageAnalysisCoordinator(
            store = RoomImageAnalysisStore(database.mediaAssetDao(), database.batchAnalysisDao()),
            featureCache = FeatureCache(filesDir),
            stageExecutor = stageExecutor,
            modelShas = ImageAnalysisModelShas(
                classification = classifier.sha256,
                embedding = embedder.sha256,
                detection = detector.sha256,
            ),
        )
    }

    private fun ModelDescriptor.assetPath(): String = "models/$fileName"

    private companion object {
        const val MODEL_MANIFEST_PATH = "models/model-manifest.properties"
    }
}
