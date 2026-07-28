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
            runBatch = { _, _, afterClaim ->
                afterClaim()
                throw original
            },
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
    fun `前台准备的包装 IOException 纳入有限重试边界`() = runBlocking {
        var runCalls = 0
        val execution = execution(
            runBatch = {
                runCalls += 1
                result()
            },
        )

        val outcome = execution.execute(
            batchId = BATCH_ID,
            runAttemptCount = 0,
            beforeRun = {
                throw IllegalStateException(
                    "前台服务启动失败",
                    IOException("通知服务暂不可用"),
                )
            },
        )

        assertEquals(BatchExecutionOutcome.Retry, outcome)
        assertEquals(0, runCalls)
    }

    @Test
    fun `前台准备普通异常达到边界后持久化并释放共享链`() = runBlocking {
        var persistedMessage = ""
        val execution = execution(
            runBatch = { result() },
            failActive = { _, message ->
                persistedMessage = message
                result(retryableFailed = 1)
            },
        )

        val outcome = execution.execute(
            batchId = BATCH_ID,
            runAttemptCount = 2,
            beforeRun = { throw IllegalStateException("无法切换前台任务") },
        )

        assertTrue(outcome is BatchExecutionOutcome.Success)
        assertEquals("无法切换前台任务", persistedMessage)
    }

    @Test
    fun `前台准备的 CancellationException 与 Error 原样传播`() = runBlocking {
        val cancellation = CancellationException("前台准备取消")
        val fatal = AssertionError("前台准备致命错误")
        val execution = execution(runBatch = { result() })

        assertSame(
            cancellation,
            runCatching {
                execution.execute(BATCH_ID, 0, beforeRun = { throw cancellation })
            }.exceptionOrNull(),
        )
        assertSame(
            fatal,
            runCatching {
                execution.execute(BATCH_ID, 0, beforeRun = { throw fatal })
            }.exceptionOrNull(),
        )
    }

    @Test
    fun `成功输出同时保留进度与执行错误信息`() {
        val output = BatchExecutionOutcome.Success(
            result = result(
                completed = 2,
                retryableFailed = 1,
                currentStage = "EMBEDDING",
                currentMediaId = 99L,
            ),
            errorMessage = "前台执行失败后已持久化",
        ).toWorkerOutputData()

        assertEquals(2, output.getInt(BatchAnalysisWorker.KEY_COMPLETED, -1))
        assertEquals(1, output.getInt(BatchAnalysisWorker.KEY_FAILED, -1))
        assertEquals(3, output.getInt(BatchAnalysisWorker.KEY_TOTAL, -1))
        assertEquals(
            "EMBEDDING",
            output.getString(BatchAnalysisWorker.KEY_CURRENT_STAGE),
        )
        assertEquals(
            99L,
            output.getLong(BatchAnalysisWorker.KEY_CURRENT_MEDIA_ID, -1L),
        )
        assertEquals(
            "前台执行失败后已持久化",
            output.getString(BatchAnalysisWorker.KEY_ERROR_MESSAGE),
        )
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
        runBatch = { batchId, _, afterClaim ->
            afterClaim()
            runBatch(batchId)
        },
        failActiveExecution = failActive,
        logger = BatchExecutionLogger { _, _, _, _ -> },
    )

    private companion object {
        const val BATCH_ID = 7L

        fun result(
            completed: Int = 0,
            retryableFailed: Int = 0,
            currentStage: String = "",
            currentMediaId: Long = -1L,
        ) = BatchAnalysisResult(
            completed = completed,
            failed = retryableFailed,
            total = completed + retryableFailed,
            retryableFailed = retryableFailed,
            permanentFailed = 0,
            paused = false,
            cancelled = false,
            currentStage = currentStage,
            currentMediaId = currentMediaId,
        )
    }
}

private data class ExecutionFailureLog(
    val batchId: Long,
    val runAttemptCount: Int,
    val error: Exception,
    val persistenceError: Exception?,
)
