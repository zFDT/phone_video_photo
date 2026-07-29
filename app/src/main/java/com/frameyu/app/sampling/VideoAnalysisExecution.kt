package com.frameyu.app.sampling

import kotlinx.coroutines.CancellationException

internal data class VideoAnalysisExecutionOutcome(
    val result: VideoAnalysisTaskResult?,
    val errorMessage: String? = null,
    val persistenceError: Exception? = null,
)

/** 将领域终态统一转换为成功边界，避免共享 WorkManager 链级联失败。 */
internal class VideoAnalysisExecution(
    private val runAnalysis: suspend (
        taskId: Long,
        executionGeneration: Long,
        onProgress: suspend (VideoAnalysisProgress) -> Unit,
    ) -> VideoAnalysisTaskResult,
    private val markFailed: suspend (
        execution: VideoAnalysisExecutionKey,
        message: String,
    ) -> Boolean,
) {
    suspend fun execute(
        execution: VideoAnalysisExecutionKey,
        onProgress: suspend (VideoAnalysisProgress) -> Unit = {},
    ): VideoAnalysisExecutionOutcome = try {
        VideoAnalysisExecutionOutcome(
            result = runAnalysis(
                execution.taskId,
                execution.executionGeneration,
                onProgress,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val message = error.message ?: "视频分析执行失败"
        try {
            markFailed(execution, message)
            VideoAnalysisExecutionOutcome(result = null, errorMessage = message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (persistenceError: Exception) {
            VideoAnalysisExecutionOutcome(
                result = null,
                errorMessage = message,
                persistenceError = persistenceError,
            )
        }
    }
}
