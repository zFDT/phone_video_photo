package com.frameyu.app.analysis.batch

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnalysisExecutionTest {
    @Test
    fun `IOException 在有限次数内交给 WorkManager 重试`() = runBlocking {
        var persistedFailures = 0
        val execution = execution(
            runBatch = { throw IOException("存储暂时不可用") },
            failActive = { _, _ ->
                persistedFailures += 1
                result()
            },
        )

        assertEquals(BatchExecutionOutcome.Retry, execution.execute(BATCH_ID, runAttemptCount = 0))
        assertEquals(BatchExecutionOutcome.Retry, execution.execute(BATCH_ID, runAttemptCount = 1))
        assertEquals(0, persistedFailures)
    }

    @Test
    fun `IOException 达到上限后持久化并成功释放共享链`() = runBlocking {
        val persisted = mutableListOf<Long>()
        val executed = mutableListOf<Long>()
        val execution = execution(
            runBatch = { batchId ->
                executed += batchId
                if (batchId == BATCH_ID) throw IOException("持续失败")
                result(completed = 1)
            },
            failActive = { batchId, _ ->
                persisted += batchId
                result(retryableFailed = 1)
            },
        )

        val failedBatch = execution.execute(BATCH_ID, runAttemptCount = 2)
        val followingBatch = execution.execute(BATCH_ID + 1L, runAttemptCount = 0)

        assertTrue(failedBatch is BatchExecutionOutcome.Success)
        assertEquals(1, (failedBatch as BatchExecutionOutcome.Success).result?.retryableFailed)
        assertEquals(1, (followingBatch as BatchExecutionOutcome.Success).result?.completed)
        assertEquals(listOf(BATCH_ID), persisted)
        assertEquals(listOf(BATCH_ID, BATCH_ID + 1L), executed)
    }

    @Test
    fun `普通异常持久化后成功释放共享链`() = runBlocking {
        var persistedMessage = ""
        val execution = execution(
            runBatch = { throw IllegalStateException("Runner 崩溃") },
            failActive = { _, message ->
                persistedMessage = message
                result(retryableFailed = 1)
            },
        )

        val outcome = execution.execute(BATCH_ID, runAttemptCount = 0)

        assertTrue(outcome is BatchExecutionOutcome.Success)
        assertEquals("Runner 崩溃", persistedMessage)
    }

    @Test
    fun `数据库不可写时记录上下文后仍成功释放共享链`() = runBlocking {
        val logs = mutableListOf<ExecutionFailureLog>()
        val original = IllegalStateException("Runner 失败")
        val persistence = IllegalStateException("数据库只读")
        val execution = BatchAnalysisExecution(
            runBatch = { _, _ -> throw original },
            failActiveExecution = { _, _ -> throw persistence },
            logger = BatchExecutionLogger { batchId, runAttemptCount, error, persistenceError ->
                logs += ExecutionFailureLog(batchId, runAttemptCount, error, persistenceError)
            },
        )

        val outcome = execution.execute(BATCH_ID, runAttemptCount = 4)

        assertTrue(outcome is BatchExecutionOutcome.Success)
        assertEquals(null, (outcome as BatchExecutionOutcome.Success).result)
        assertEquals(1, logs.size)
        assertEquals(BATCH_ID, logs.single().batchId)
        assertEquals(4, logs.single().runAttemptCount)
        assertSame(original, logs.single().error)
        assertSame(persistence, logs.single().persistenceError)
    }

    @Test
    fun `cause chain 中的 IOException 保持有限重试语义`() = runBlocking {
        val execution = execution(
            runBatch = {
                throw IllegalStateException("解码包装异常", IOException("读取失败"))
            },
        )

        assertEquals(BatchExecutionOutcome.Retry, execution.execute(BATCH_ID, runAttemptCount = 0))
    }

    @Test
    fun `CancellationException 与 Error 原样传播`() = runBlocking {
        val cancellation = CancellationException("用户取消")
        val cancellationExecution = execution(runBatch = { throw cancellation })
        val fatal = AssertionError("VM 错误")
        val fatalExecution = execution(runBatch = { throw fatal })

        assertSame(
            cancellation,
            runCatching { cancellationExecution.execute(BATCH_ID, 0) }.exceptionOrNull(),
        )
        assertSame(fatal, runCatching { fatalExecution.execute(BATCH_ID, 0) }.exceptionOrNull())
    }

    private fun execution(
        runBatch: suspend (Long) -> BatchAnalysisResult,
        failActive: suspend (Long, String) -> BatchAnalysisResult = { _, _ -> result() },
    ) = BatchAnalysisExecution(
        runBatch = { batchId, _ -> runBatch(batchId) },
        failActiveExecution = failActive,
        logger = BatchExecutionLogger { _, _, _, _ -> },
    )

    private companion object {
        const val BATCH_ID = 7L

        fun result(
            completed: Int = 0,
            retryableFailed: Int = 0,
        ) = BatchAnalysisResult(
            completed = completed,
            failed = retryableFailed,
            total = completed + retryableFailed,
            retryableFailed = retryableFailed,
            permanentFailed = 0,
            paused = false,
            cancelled = false,
            currentStage = "",
            currentMediaId = -1L,
        )
    }
}

private data class ExecutionFailureLog(
    val batchId: Long,
    val runAttemptCount: Int,
    val error: Exception,
    val persistenceError: Exception?,
)
