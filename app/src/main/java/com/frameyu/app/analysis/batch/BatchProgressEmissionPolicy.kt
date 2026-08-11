package com.frameyu.app.analysis.batch

/** 限制 WorkManager 进度和前台通知刷新频率，不影响逐项结果持久化。 */
internal class BatchProgressEmissionPolicy(
    private val minIntervalMillis: Long,
    private val nowMillis: () -> Long,
) {
    private var lastEmissionMillis: Long? = null

    init {
        require(minIntervalMillis > 0L) { "最小发布间隔必须大于零" }
    }

    fun shouldEmit(progress: BatchAnalysisProgress): Boolean {
        val now = nowMillis()
        val previousEmission = lastEmissionMillis
        val terminal = progress.total > 0 && progress.completed + progress.failed >= progress.total
        val intervalElapsed = previousEmission == null || now - previousEmission >= minIntervalMillis
        if (!terminal && !intervalElapsed) return false

        lastEmissionMillis = now
        return true
    }
}
