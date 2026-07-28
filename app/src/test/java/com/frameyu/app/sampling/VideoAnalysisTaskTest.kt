package com.frameyu.app.sampling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAnalysisTaskTest {
    @Test
    fun `逐帧保存进度并完成全部区间`() = runBlocking {
        val saved = mutableListOf<VideoAnalysisProgress>()
        val plan = SamplingPlan.create(2_000L, listOf(SampleRange(1_000L, 2_000L, 10.0, SamplingTier.FOCUSED)))
        val result = VideoAnalysisTask(SamplingCheckpointStore { saved += it }).run(plan) { }

        assertTrue(result is VideoAnalysisTaskResult.Completed)
        assertEquals(plan.sampleTimestamps().size, saved.size)
        assertEquals(plan.videoDurationMillis, (result as VideoAnalysisTaskResult.Completed).progress.nextTimestampMillis)
    }

    @Test
    fun `从断点继续且不重复已完成时间`() = runBlocking {
        val plan = SamplingPlan.create(3_000L)
        val checkpoint = SamplingCheckpoint(plan.planId, 0, 2_000L, 2L)
        val sampled = mutableListOf<Long>()
        val result = VideoAnalysisTask(SamplingCheckpointStore { }).run(plan, checkpoint) { sampled += it }

        assertTrue(result is VideoAnalysisTaskResult.Completed)
        assertEquals(listOf(2_000L), sampled)
    }

    @Test
    fun `采样回调失败时保留失败进度`() = runBlocking {
        val plan = SamplingPlan.create(3_000L)
        val result = VideoAnalysisTask(SamplingCheckpointStore { }).run(plan) {
            if (it == 1_000L) error("解码失败")
        }

        assertTrue(result is VideoAnalysisTaskResult.Failed)
        assertEquals(1L, (result as VideoAnalysisTaskResult.Failed).progress.sampledFrameCount)
    }

    @Test
    fun `协程取消和Error不会被转换为任务结果`() {
        val cancellation = CancellationException("cancel")
        val actualCancellation = assertThrows<CancellationException> {
            runBlocking {
                VideoAnalysisTask(SamplingCheckpointStore { }).run(SamplingPlan.create(1L)) {
                    throw cancellation
                }
            }
        }
        assertTrue(actualCancellation === cancellation)

        val fatal = AssertionError("fatal")
        val actualError = assertThrows<AssertionError> {
            runBlocking {
                VideoAnalysisTask(SamplingCheckpointStore { }).run(SamplingPlan.create(1L)) {
                    throw fatal
                }
            }
        }
        assertTrue(actualError === fatal)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("预期 ${T::class.java.simpleName}，实际 ${error::class.java.simpleName}", error is T)
            return error as T
        }
        throw AssertionError("预期抛出 ${T::class.java.simpleName}")
    }
}
