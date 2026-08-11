package com.frameyu.app.analysis.batch

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

internal class BatchAnalysisNotificationFactory(
    private val context: Context,
) {
    fun create(
        batchId: Long,
        progress: BatchAnalysisProgress,
        text: BatchNotificationText,
    ): ForegroundInfo {
        val openPendingIntent = PendingIntent.getActivity(
            context,
            BatchAnalysisIntents.openRequestCode(batchId),
            BatchAnalysisIntents.openIntent(context, batchId),
            PENDING_INTENT_FLAGS,
        )
        val pausePendingIntent = PendingIntent.getBroadcast(
            context,
            BatchAnalysisIntents.pauseRequestCode(batchId),
            BatchAnalysisIntents.pauseIntent(context, batchId),
            PENDING_INTENT_FLAGS,
        )
        return ForegroundInfo(
            BatchAnalysisWorker.notificationId(batchId),
            NotificationCompat.Builder(context, BatchAnalysisWorker.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(text.title)
                .setContentText(text.content)
                .setContentIntent(openPendingIntent)
                .setProgress(
                    progress.total.coerceAtLeast(0),
                    progress.processed.coerceAtLeast(0),
                    progress.total <= 0,
                )
                .setOngoing(true)
                .addAction(0, "暂停", pausePendingIntent)
                .addAction(0, "打开帧遇", openPendingIntent)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private companion object {
        const val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
