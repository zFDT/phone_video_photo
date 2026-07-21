package com.frameyu.app.domain.model

/** 可暂停、恢复的媒体分析任务。所有时间字段均使用毫秒。 */
data class AnalysisTask(
    val id: Long,
    val mediaAssetId: Long,
    val status: Status,
    val progress: Int,
    val currentTimestampMillis: Long,
    val errorMessage: String?,
    val updatedAtMillis: Long,
) {
    init {
        require(id >= 0) { "任务 ID 不能为负数" }
        require(mediaAssetId >= 0) { "媒体 ID 不能为负数" }
        require(progress in 0..100) { "任务进度必须在 0 到 100 之间" }
        require(currentTimestampMillis >= 0) { "当前时间点不能为负数" }
        require(updatedAtMillis >= 0) { "更新时间不能为负数" }
    }

    /** 返回状态转换后的新任务，原任务保持不变。 */
    fun transitionTo(
        status: Status,
        updatedAtMillis: Long,
        errorMessage: String? = this.errorMessage,
    ): AnalysisTask {
        require(canTransitionTo(status)) { "任务不能从 $this.status 转换为 $status" }
        return copy(
            status = status,
            errorMessage = errorMessage,
            updatedAtMillis = updatedAtMillis,
        )
    }

    /** 返回保存了最新采样位置和进度的新任务。 */
    fun withProgress(
        progress: Int,
        currentTimestampMillis: Long,
        updatedAtMillis: Long,
    ): AnalysisTask = copy(
        progress = progress,
        currentTimestampMillis = currentTimestampMillis,
        updatedAtMillis = updatedAtMillis,
    )

    private fun canTransitionTo(target: Status): Boolean = when (status) {
        Status.QUEUED -> target in setOf(Status.RUNNING, Status.CANCELLED, Status.FAILED)
        Status.RUNNING -> target in setOf(
            Status.PAUSED,
            Status.COMPLETED,
            Status.CANCELLED,
            Status.FAILED,
        )
        Status.PAUSED -> target in setOf(Status.RUNNING, Status.CANCELLED, Status.FAILED)
        Status.COMPLETED, Status.CANCELLED, Status.FAILED -> false
    }

    enum class Status {
        QUEUED,
        RUNNING,
        PAUSED,
        COMPLETED,
        CANCELLED,
        FAILED,
    }
}
