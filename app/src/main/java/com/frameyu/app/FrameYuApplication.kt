package com.frameyu.app

import android.app.Application
import androidx.room.Room
import com.frameyu.app.data.db.FrameYuDatabase
import com.frameyu.app.data.media.MediaStoreRepository
import com.frameyu.app.data.media.MediaStoreScanner

class FrameYuApplication : Application() {
    lateinit var mediaStoreRepository: MediaStoreRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(this, FrameYuDatabase::class.java, "frameyu.db")
            .addMigrations(FrameYuDatabase.MIGRATION_1_2)
            .build()
        mediaStoreRepository = MediaStoreRepository(MediaStoreScanner(contentResolver, database.mediaAssetDao()))
    }
}
