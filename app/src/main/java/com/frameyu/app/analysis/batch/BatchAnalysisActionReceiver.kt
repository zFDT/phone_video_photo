package com.frameyu.app.analysis.batch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.frameyu.app.FrameYuApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BatchAnalysisActionReceiver internal constructor(
    private val pauseBatch: suspend (Context, Long) -> Unit,
) : BroadcastReceiver() {
    constructor() : this(
        pauseBatch = { context, batchId ->
            val application = context.applicationContext as? FrameYuApplication
                ?: error("未配置批量分析调度器")
            application.batchAnalysisWorkScheduler.pause(batchId)
        },
    )

    override fun onReceive(context: Context, intent: Intent) {
        val batchId = BatchAnalysisIntents.pauseBatchId(intent)
        if (batchId == null) {
            Log.w(TAG, "忽略无效的批次暂停通知 action=${intent.action}")
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handlePause(context, batchId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "通知暂停失败 batchId=$batchId", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal suspend fun handlePause(context: Context, batchId: Long) {
        pauseBatch(context, batchId)
    }

    private companion object {
        const val TAG = "BatchAnalysisAction"
    }
}
