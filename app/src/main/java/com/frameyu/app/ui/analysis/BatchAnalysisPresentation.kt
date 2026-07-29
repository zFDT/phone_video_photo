package com.frameyu.app.ui.analysis

import com.frameyu.app.data.db.AnalysisBatchWithProgress

data class BatchAnalysisActions(
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canCancel: Boolean = false,
    val canRetryFailures: Boolean = false,
    val canOpenOrganizer: Boolean = false,
)

data class BatchAnalysisUiState(
    val batchId: Long,
    val status: String,
    val statusLabel: String,
    val total: Long,
    val completed: Long,
    val failed: Long,
    val currentStageLabel: String,
    val progressFraction: Float,
    val actions: BatchAnalysisActions,
)

fun batchAnalysisUiState(
    batchId: Long,
    progress: AnalysisBatchWithProgress,
    currentStage: String?,
): BatchAnalysisUiState {
    val status = progress.batch.status
    val processed = progress.completedWorkItems + progress.failedWorkItems
    val progressFraction = if (progress.totalWorkItems == 0L) {
        0f
    } else {
        (processed.toFloat() / progress.totalWorkItems.toFloat()).coerceIn(0f, 1f)
    }
    return BatchAnalysisUiState(
        batchId = batchId,
        status = status,
        statusLabel = batchStatusLabel(status),
        total = progress.totalWorkItems,
        completed = progress.completedWorkItems,
        failed = progress.failedWorkItems,
        currentStageLabel = batchStageLabel(currentStage),
        progressFraction = progressFraction,
        actions = batchActions(status, progress.failedWorkItems),
    )
}

internal fun batchActions(status: String, failed: Long): BatchAnalysisActions = when (status) {
    "QUEUED", "RUNNING" -> BatchAnalysisActions(canPause = true, canCancel = true)
    "PAUSED" -> BatchAnalysisActions(canResume = true, canCancel = true)
    "NEEDS_ATTENTION" -> BatchAnalysisActions(
        canCancel = true,
        canRetryFailures = failed > 0L,
    )
    "COMPLETED" -> BatchAnalysisActions(canOpenOrganizer = true)
    else -> BatchAnalysisActions()
}

internal fun batchStatusLabel(status: String): String = when (status) {
    "QUEUED" -> "等待分析"
    "RUNNING" -> "分析中"
    "PAUSED" -> "已暂停"
    "NEEDS_ATTENTION" -> "部分项目需要处理"
    "COMPLETED" -> "分析完成"
    "CANCELLED" -> "已取消"
    else -> "未知状态"
}

internal fun batchStageLabel(stage: String?): String = when (stage) {
    "QUALITY" -> "质量评分"
    "CLASSIFICATION" -> "图片分类"
    "EMBEDDING" -> "相似特征提取"
    "CONDITIONAL_DETECTION" -> "目标检测"
    null -> "等待开始"
    else -> stage
}
