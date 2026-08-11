package com.frameyu.app.analysis.batch

import com.frameyu.app.system.AppVisibility
import kotlin.math.ceil

enum class AnalysisRunMode(val label: String) {
    FOREGROUND("正常速度"),
    BACKGROUND_FRIENDLY("后台友好"),
}

data class BatchNotificationText(
    val title: String,
    val content: String,
)

class BatchEtaEstimator(
    private val minSamples: Int = 30,
    private val minWindowMillis: Long = 30_000L,
) {
    private var window: Window? = null

    init {
        require(minSamples > 0) { "最小样本数必须大于零" }
        require(minWindowMillis > 0L) { "最小观测时间必须大于零" }
    }

    fun record(
        processed: Int,
        total: Int,
        mode: AnalysisRunMode,
        nowMillis: Long,
    ): Long? {
        val currentWindow = window
        if (
            currentWindow == null ||
            currentWindow.mode != mode ||
            processed < currentWindow.lastProcessed ||
            nowMillis < currentWindow.lastNowMillis
        ) {
            reset(processed, mode, nowMillis)
            return null
        }

        currentWindow.lastProcessed = processed
        currentWindow.lastNowMillis = nowMillis
        val processedDelta = processed.toLong() - currentWindow.startProcessed.toLong()
        val elapsedMillis = nowMillis.toDouble() - currentWindow.startNowMillis.toDouble()
        val remaining = total.toLong() - processed.toLong()
        if (
            processedDelta < minSamples.toLong() ||
            elapsedMillis < minWindowMillis.toDouble() ||
            remaining <= 0L ||
            elapsedMillis <= 0.0
        ) {
            return null
        }

        val estimate = ceil(remaining.toDouble() * elapsedMillis / processedDelta.toDouble())
        return when {
            !estimate.isFinite() || estimate >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            estimate <= 0.0 -> null
            else -> estimate.toLong().coerceAtLeast(1L)
        }
    }

    private fun reset(
        processed: Int,
        mode: AnalysisRunMode,
        nowMillis: Long,
    ) {
        window = Window(
            startProcessed = processed,
            startNowMillis = nowMillis,
            mode = mode,
            lastProcessed = processed,
            lastNowMillis = nowMillis,
        )
    }

    private data class Window(
        val startProcessed: Int,
        val startNowMillis: Long,
        val mode: AnalysisRunMode,
        var lastProcessed: Int,
        var lastNowMillis: Long,
    )
}

fun presentBatchNotification(
    stage: String,
    mode: AnalysisRunMode,
    processed: Int,
    total: Int,
    failed: Int,
    etaMillis: Long?,
): BatchNotificationText {
    val stageLabel = stage.toStageLabel()
    val prefix = if (stageLabel.isEmpty()) {
        mode.label
    } else {
        "$stageLabel · ${mode.label}"
    }
    val eta = etaMillis?.toRoughEtaText()
    val content = buildString {
        append(prefix)
        append('：')
        append(processed)
        append('/')
        append(total)
        append(" · 失败 ")
        append(failed)
        if (eta != null) {
            append(" · ")
            append(eta)
        }
    }
    return BatchNotificationText(
        title = "帧遇正在批量分析",
        content = content,
    )
}

internal class BatchNotificationEmissionCoordinator(
    private val shouldEmit: (BatchAnalysisProgress) -> Boolean,
    private val visibilityProvider: () -> AppVisibility,
    private val nowMillis: () -> Long,
    private val recordEta: (Int, Int, AnalysisRunMode, Long) -> Long?,
    private val presentation: (
        BatchAnalysisProgress,
        AnalysisRunMode,
        Long?,
    ) -> BatchNotificationText = { progress, mode, etaMillis ->
        progress.toNotificationText(mode, etaMillis)
    },
) {
    fun initialNotification(progress: BatchAnalysisProgress): BatchNotificationText {
        val mode = visibilityProvider().toAnalysisRunMode()
        return presentation(progress, mode, null)
    }

    fun notificationFor(progress: BatchAnalysisProgress): BatchNotificationText? {
        if (!shouldEmit(progress)) return null

        val mode = visibilityProvider().toAnalysisRunMode()
        val now = nowMillis()
        val etaMillis = recordEta(progress.processed, progress.total, mode, now)
        return presentation(progress, mode, etaMillis)
    }
}

internal val BatchAnalysisProgress.processed: Int
    get() = (completed.toLong() + failed.toLong())
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()

private fun BatchAnalysisProgress.toNotificationText(
    mode: AnalysisRunMode,
    etaMillis: Long?,
): BatchNotificationText = presentBatchNotification(
    stage = currentStage,
    mode = mode,
    processed = processed,
    total = total,
    failed = failed,
    etaMillis = etaMillis,
)

fun AppVisibility.toAnalysisRunMode(): AnalysisRunMode = when (this) {
    AppVisibility.FOREGROUND -> AnalysisRunMode.FOREGROUND
    AppVisibility.BACKGROUND -> AnalysisRunMode.BACKGROUND_FRIENDLY
}

private fun String.toStageLabel(): String = when (this) {
    AnalysisStage.METADATA.name -> "元数据"
    AnalysisStage.CONTENT_HASH.name -> "内容哈希"
    AnalysisStage.QUALITY.name -> "画质分析"
    AnalysisStage.CLASSIFICATION.name -> "图片分类"
    AnalysisStage.EMBEDDING.name -> "图片特征"
    AnalysisStage.CONDITIONAL_DETECTION.name -> "目标检测"
    AnalysisStage.RECOMMENDATION.name -> "推荐排序"
    else -> this
}

private fun Long.toRoughEtaText(): String {
    val minutes = divideRoundingUp(coerceAtLeast(0L), MILLIS_PER_MINUTE).coerceAtLeast(1L)
    return if (minutes < MINUTES_PER_HOUR) {
        "约 $minutes 分钟"
    } else {
        val hours = divideRoundingUp(minutes, MINUTES_PER_HOUR)
        "约 $hours 小时"
    }
}

private fun divideRoundingUp(value: Long, divisor: Long): Long =
    value / divisor + if (value % divisor == 0L) 0L else 1L

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
