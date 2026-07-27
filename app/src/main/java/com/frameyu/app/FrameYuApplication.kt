package com.frameyu.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.room.Room
import androidx.work.Configuration
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.media.MediaStoreRepository
import com.frameyu.app.data.media.MediaStoreScanner
import com.frameyu.app.sampling.VideoAnalysisWorker
import com.frameyu.app.sampling.VideoAnalysisWorkerFactory
import com.frameyu.app.sampling.VideoAnalysisWorkerRunner
import com.frameyu.app.sampling.RoomVideoAnalysisWorkerRunner
import com.frameyu.app.sampling.VideoAnalysisWorkScheduler
import com.frameyu.app.settings.SettingsStore

class FrameYuApplication : Application(), Configuration.Provider {
    lateinit var database: FrameYuDatabase
        private set

    lateinit var videoAnalysisWorkScheduler: VideoAnalysisWorkScheduler
        private set

    lateinit var mediaStoreRepository: MediaStoreRepository
        private set

    var videoAnalysisWorkerRunner: VideoAnalysisWorkerRunner = VideoAnalysisWorkerRunner { _, _ ->
        error("尚未配置视频分析执行器")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(VideoAnalysisWorkerFactory(videoAnalysisWorkerRunner))
            .build()

    override fun onCreate() {
        super.onCreate()
        createVideoAnalysisNotificationChannel()
        database = Room.databaseBuilder(this, FrameYuDatabase::class.java, "frameyu.db")
            .addMigrations(FrameYuDatabase.MIGRATION_1_2, FrameYuDatabase.MIGRATION_2_3)
            .build()
        val settingsStore = SettingsStore(getSharedPreferences(SettingsStore.PREFERENCES_NAME, MODE_PRIVATE))
        videoAnalysisWorkerRunner = RoomVideoAnalysisWorkerRunner(this, database) {
            settingsStore.read().concertModeEnabled
        }
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
}
