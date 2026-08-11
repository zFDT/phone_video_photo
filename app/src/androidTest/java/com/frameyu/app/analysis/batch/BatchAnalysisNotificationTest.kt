package com.frameyu.app.analysis.batch

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frameyu.app.MainActivity
import com.frameyu.app.data.db.AnalysisBatchEntity
import com.frameyu.app.data.db.FrameYuDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchAnalysisNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun notificationContainsPauseAndOpenActionsWithIsolatedPendingIntents() {
        val batchId = 42L
        val foregroundInfo = BatchAnalysisNotificationFactory(context).create(
            batchId = batchId,
            progress = BatchAnalysisProgress(
                completed = 12,
                failed = 1,
                total = 40,
                currentStage = AnalysisStage.CLASSIFICATION.name,
                currentMediaId = 9L,
            ),
            text = BatchNotificationText(
                title = "帧遇正在批量分析",
                content = "图片分类 · 正常速度：13/40 · 失败 1",
            ),
        )

        val notification = foregroundInfo.notification
        assertEquals(BatchAnalysisWorker.notificationId(batchId), foregroundInfo.notificationId)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            foregroundInfo.foregroundServiceType,
        )
        assertEquals(BatchAnalysisWorker.CHANNEL_ID, notification.channelId)
        assertEquals(40, notification.extras.getInt("android.progressMax"))
        assertEquals(13, notification.extras.getInt("android.progress"))
        assertNotNull(notification.contentIntent)
        assertEquals(2, notification.actions.size)
        assertEquals("暂停", notification.actions[0].title.toString())
        assertEquals("打开帧遇", notification.actions[1].title.toString())
        assertNotNull(notification.actions[0].actionIntent)
        assertNotNull(notification.actions[1].actionIntent)
        assertNotEquals(
            BatchAnalysisIntents.pauseRequestCode(batchId),
            BatchAnalysisIntents.openRequestCode(batchId),
        )
        assertEquals(
            BatchAnalysisActionReceiver::class.java.name,
            BatchAnalysisIntents.pauseIntent(context, batchId).component?.className,
        )
        assertEquals(
            MainActivity::class.java.name,
            BatchAnalysisIntents.openIntent(context, batchId).component?.className,
        )
        assertTrue(notification.actions[0].actionIntent.isBroadcast)
        assertTrue(notification.actions[1].actionIntent.isActivity)
        assertEquals(notification.contentIntent, notification.actions[1].actionIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(notification.contentIntent.isImmutable)
            assertTrue(notification.actions[0].actionIntent.isImmutable)
        }
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        @Suppress("DEPRECATION")
        val receiverInfo = context.packageManager.getReceiverInfo(
            ComponentName(context, BatchAnalysisActionReceiver::class.java),
            0,
        )
        assertTrue(!receiverInfo.exported)
    }

    @Test
    fun receiverPausesQueuedBatchAndInvalidIdDoesNotChangeOtherBatch() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, FrameYuDatabase::class.java).build()
        val dao = database.batchAnalysisDao()
        val store = RoomBatchAnalysisStore(dao)
        val handledBatchIds = Channel<Long>(Channel.UNLIMITED)
        val receiver = BatchAnalysisActionReceiver { _, batchId ->
            try {
                store.pauseBatch(batchId)
            } finally {
                handledBatchIds.send(batchId)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BatchAnalysisIntents.ACTION_PAUSE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            val pausedBatchId = dao.createBatch(batch(), emptyList())
            val untouchedBatchId = dao.createBatch(batch(), emptyList())

            context.sendBroadcast(
                BatchAnalysisIntents.pauseIntent(context, pausedBatchId).apply {
                    component = null
                },
            )
            assertEquals(pausedBatchId, withTimeout(5_000L) { handledBatchIds.receive() })

            context.sendBroadcast(
                BatchAnalysisIntents.pauseIntent(context, Long.MAX_VALUE).apply {
                    component = null
                },
            )
            assertEquals(Long.MAX_VALUE, withTimeout(5_000L) { handledBatchIds.receive() })

            assertEquals(BatchAnalysisStatus.PAUSED.name, dao.batchStatus(pausedBatchId))
            assertEquals(BatchAnalysisStatus.QUEUED.name, dao.batchStatus(untouchedBatchId))
        } finally {
            context.unregisterReceiver(receiver)
            database.close()
        }
    }

    private fun batch() = AnalysisBatchEntity(
        status = BatchAnalysisStatus.QUEUED.name,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )
}
