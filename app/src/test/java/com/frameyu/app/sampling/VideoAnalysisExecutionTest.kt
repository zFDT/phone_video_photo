package com.frameyu.app.sampling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAnalysisExecutionTest {
    @Test
    fun `领域 Completed Cancelled Failed Skipped 都成功释放共享链`() = runBlocking {
        val results = listOf(
            VideoAnalysisTaskResult.Completed(progress()),
            VideoAnalysisTaskResult.Cancelled(progress()),
            VideoAnalysisTaskResult.Failed(progress(), IllegalStateException("分析失败")),
            VideoAnalysisTaskResult.Skipped,
        )
        val executed = mutableListOf<Long>()
        val execution = VideoAnalysisExecution(
            runAnalysis = { taskId, _, _ ->
                executed += taskId
                results[(taskId - 1L).toInt()]
            },
            markFailed = { _, _ -> true },
        )

        val outcomes = results.indices.map { index ->
            execution.execute(VideoAnalysisExecutionKey(index + 1L, 0L))
        }

        assertEquals(listOf(1L, 2L, 3L, 4L), executed)
        assertEquals(results, outcomes.map(VideoAnalysisExecutionOutcome::result))
    }

    @Test
    fun `真正 CancellationException 与 Error 原样传播`() = runBlocking {
        val cancellation = CancellationException("协程取消")
        val fatal = AssertionError("致命错误")

        assertSame(
            cancellation,
            runCatching {
                execution { throw cancellation }
                    .execute(VideoAnalysisExecutionKey(1L, 0L))
            }.exceptionOrNull(),
        )
        assertSame(
            fatal,
            runCatching {
                execution { throw fatal }
                    .execute(VideoAnalysisExecutionKey(1L, 0L))
            }.exceptionOrNull(),
        )
    }

    @Test
    fun `普通边界异常尽力落同代 FAILED 后成功释放链`() = runBlocking {
        val failed = mutableListOf<Pair<VideoAnalysisExecutionKey, String>>()
        val execution = VideoAnalysisExecution(
            runAnalysis = { _, _, _ -> error("前台通知失败") },
            markFailed = { key, message ->
                failed += key to message
                true
            },
        )
        val key = VideoAnalysisExecutionKey(7L, 3L)

        val outcome = execution.execute(key)

        assertEquals(listOf(key to "前台通知失败"), failed)
        assertEquals("前台通知失败", outcome.errorMessage)
        assertEquals(null, outcome.result)
    }

    @Test
    fun `FAILED 持久化异常不阻塞后续链`() = runBlocking {
        val execution = VideoAnalysisExecution(
            runAnalysis = { _, _, _ -> error("runner 崩溃") },
            markFailed = { _, _ -> error("Room 只读") },
        )

        val outcome = execution.execute(VideoAnalysisExecutionKey(7L, 3L))

        assertEquals("runner 崩溃", outcome.errorMessage)
        assertTrue(outcome.persistenceError is IllegalStateException)
    }

    private fun execution(
        runAnalysis: suspend () -> VideoAnalysisTaskResult,
    ) = VideoAnalysisExecution(
        runAnalysis = { _, _, _ -> runAnalysis() },
        markFailed = { _, _ -> true },
    )

    private fun progress() = VideoAnalysisProgress(
        planId = "plan",
        rangeIndex = 0,
        nextTimestampMillis = 0L,
        sampledFrameCount = 0L,
        totalSampleCount = 1L,
    )
}
