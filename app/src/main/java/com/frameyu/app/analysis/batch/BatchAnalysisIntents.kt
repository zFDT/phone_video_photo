package com.frameyu.app.analysis.batch

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.frameyu.app.MainActivity

object BatchAnalysisIntents {
    const val ACTION_PAUSE = "com.frameyu.app.action.PAUSE_BATCH_ANALYSIS"
    const val ACTION_OPEN = "com.frameyu.app.action.OPEN_BATCH_ANALYSIS"

    private const val EXTRA_BATCH_ID = "com.frameyu.app.extra.BATCH_ID"
    private const val PAUSE_REQUEST_SLOT = 0
    private const val OPEN_REQUEST_SLOT = 1

    fun validBatchId(batchId: Long): Long? = batchId.takeIf { it > 0L }

    fun openBatchId(intent: Intent): Long? = openBatchId(
        action = intent.action,
        batchId = intent.getLongExtra(EXTRA_BATCH_ID, -1L),
    )

    fun consumeOpenBatchId(intent: Intent): Long? = openBatchId(intent)?.also {
        intent.action = null
        intent.data = null
        intent.removeExtra(EXTRA_BATCH_ID)
    }

    fun pauseBatchId(intent: Intent): Long? = pauseBatchId(
        action = intent.action,
        batchId = intent.getLongExtra(EXTRA_BATCH_ID, -1L),
    )

    internal fun openBatchId(action: String?, batchId: Long): Long? =
        batchIdForAction(action, batchId, ACTION_OPEN)

    internal fun pauseBatchId(action: String?, batchId: Long): Long? =
        batchIdForAction(action, batchId, ACTION_PAUSE)

    fun pauseIntent(context: Context, batchId: Long): Intent = Intent(
        context,
        BatchAnalysisActionReceiver::class.java,
    ).apply {
        action = ACTION_PAUSE
        val validBatchId = requireBatchId(batchId)
        data = Uri.parse("frameyu://batch-analysis/$validBatchId/pause")
        putExtra(EXTRA_BATCH_ID, validBatchId)
    }

    fun openIntent(context: Context, batchId: Long): Intent = Intent(
        context,
        MainActivity::class.java,
    ).apply {
        action = ACTION_OPEN
        val validBatchId = requireBatchId(batchId)
        data = Uri.parse("frameyu://batch-analysis/$validBatchId/open")
        putExtra(EXTRA_BATCH_ID, validBatchId)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun pauseRequestCode(batchId: Long): Int = requestCode(batchId, PAUSE_REQUEST_SLOT)

    fun openRequestCode(batchId: Long): Int = requestCode(batchId, OPEN_REQUEST_SLOT)

    private fun requestCode(batchId: Long, slot: Int): Int {
        val value = requireBatchId(batchId)
        val folded = (value xor (value ushr Integer.SIZE)).toInt()
        return (folded shl 1) xor slot
    }

    private fun batchIdForAction(
        action: String?,
        batchId: Long,
        expectedAction: String,
    ): Long? = if (action == expectedAction) validBatchId(batchId) else null

    private fun requireBatchId(batchId: Long): Long = requireNotNull(validBatchId(batchId)) {
        "批次 ID 必须大于 0"
    }
}
