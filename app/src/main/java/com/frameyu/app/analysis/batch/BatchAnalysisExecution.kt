package com.frameyu.app.analysis.batch

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** 标记应提升到 Worker 执行边界的数据库或运行基础设施异常。 */
class BatchAnalysisInfrastructureException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

internal sealed interface BatchExecutionOutcome {
    data object Retry : BatchExecutionOutcome

    data class Success(
        val result: BatchAnalysisResult?,
        val errorMessage: String? = null,
    ) : BatchExecutionOutcome
}

internal fun interface BatchExecutionLogger {
    fun log(
        batchId: Long,
        runAttemptCount: Int,
        error: Exception,
        persistenceError: Exception?,
    )
}

/**
 * 纯 Kotlin 的 Worker 执行边界。
 *
 * 有效批次的普通失败绝不返回 WorkManager failure，避免依赖链级联失败。
 */
internal class BatchAnalysisExecution(
    private val runBatch: suspend (
        batchId: Long,
        executionGeneration: Long,
        onProgress: suspend (BatchAnalysisProgress) -> Unit,
        onClaimed: suspend () -> Unit,
    ) -> BatchAnalysisResult,
    private val failActiveExecution: suspend (
        batchId: Long,
        executionGeneration: Long,
        message: String,
    ) -> BatchAnalysisResult,
    private val logger: BatchExecutionLogger,
) {
    suspend fun execute(
        batchId: Long,
        runAttemptCount: Int,
        executionGeneration: Long = 0L,
        onClaimed: suspend () -> Unit = {},
        onProgress: suspend (BatchAnalysisProgress) -> Unit = {},
    ): BatchExecutionOutcome {
        return try {
            BatchExecutionOutcome.Success(
                runBatch(batchId, executionGeneration, onProgress, onClaimed),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error.hasIOExceptionCause() && runAttemptCount < MAX_RETRY_ATTEMPT_COUNT) {
                BatchExecutionOutcome.Retry
            } else {
                releaseChain(batchId, executionGeneration, runAttemptCount, error)
            }
        }
    }

    private suspend fun releaseChain(
        batchId: Long,
        executionGeneration: Long,
        runAttemptCount: Int,
        error: Exception,
    ): BatchExecutionOutcome.Success {
        val message = error.message ?: "批量分析执行失败"
        return try {
            val result = failActiveExecution(batchId, executionGeneration, message)
            logger.log(batchId, runAttemptCount, error, null)
            BatchExecutionOutcome.Success(result, message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (persistenceError: Exception) {
            logger.log(batchId, runAttemptCount, error, persistenceError)
            BatchExecutionOutcome.Success(result = null, errorMessage = message)
        }
    }

    private companion object {
        const val MAX_RETRY_ATTEMPT_COUNT = 2
    }
}

internal fun Throwable.hasIOExceptionCause(): Boolean =
    generateSequence(this) { current -> current.cause }
        .any { current -> current is IOException }
