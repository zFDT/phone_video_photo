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
    fun `24帧和15帧执行时间戳与计划完全一致`() = runBlocking {
        val plan = SamplingPlan.create(
            videoDurationMillis = 400L,
            requestedRanges = listOf(
                SampleRange(0L, 200L, 24.0, SamplingTier.HIGH_VALUE),
                SampleRange(200L, 400L, 15.0, SamplingTier.HIGH_VALUE),
            ),
        )
        val sampled = mutableListOf<Long>()

        val result = VideoAnalysisTask(SamplingCheckpointStore { }).run(plan) { sampled += it }

        assertTrue(result is VideoAnalysisTaskResult.Completed)
        assertEquals(plan.sampleTimestamps(), sampled)
    }

    @Test
    fun `checkpoint始终指向下一canonical时间戳并在区间末端指向end`() = runBlocking {
        val plan = SamplingPlan.create(
            videoDurationMillis = 200L,
            requestedRanges = listOf(SampleRange(0L, 200L, 24.0, SamplingTier.HIGH_VALUE)),
        )
        val saved = mutableListOf<VideoAnalysisProgress>()

        VideoAnalysisTask(SamplingCheckpointStore { saved += it }).run(plan) { }

        assertEquals(listOf(41L, 83L, 125L, 166L, 200L), saved.map { it.nextTimestampMillis })
    }

    @Test
    fun `多区间从canonical断点恢复不会重复或漂移`() = runBlocking {
        val plan = SamplingPlan.create(
            videoDurationMillis = 500L,
            requestedRanges = listOf(
                SampleRange(100L, 300L, 15.0, SamplingTier.HIGH_VALUE),
                SampleRange(300L, 500L, 24.0, SamplingTier.HIGH_VALUE),
            ),
        )
        val fullProgress = mutableListOf<VideoAnalysisProgress>()
        VideoAnalysisTask(SamplingCheckpointStore { fullProgress += it }).run(plan) { }
        val checkpointProgress = fullProgress.first { it.nextTimestampMillis == 383L }
        val checkpoint = SamplingCheckpoint(
            checkpointProgress.planId,
            checkpointProgress.rangeIndex,
            checkpointProgress.nextTimestampMillis,
            checkpointProgress.sampledFrameCount,
        )
        val resumed = mutableListOf<Long>()

        val result = VideoAnalysisTask(SamplingCheckpointStore { }).run(plan, checkpoint) { resumed += it }

        assertTrue(result is VideoAnalysisTaskResult.Completed)
        assertEquals(plan.sampleTimestamps().drop(checkpoint.sampledFrameCount.toInt()), resumed)
    }

    @Test
    fun `旧算法planId断点会被拒绝而不是混用非canonical时间戳`() = runBlocking {
        val plan = SamplingPlan.create(
            videoDurationMillis = 200L,
            requestedRanges = listOf(SampleRange(0L, 200L, 24.0, SamplingTier.HIGH_VALUE)),
        )
        val sampled = mutableListOf<Long>()

        val result = VideoAnalysisTask(SamplingCheckpointStore { }).run(
            plan,
            SamplingCheckpoint(
                planId = "0".repeat(64),
                rangeIndex = 0,
                nextTimestampMillis = 82L,
                sampledFrameCount = 2L,
            ),
        ) { sampled += it }

        assertTrue(result is VideoAnalysisTaskResult.Failed)
        assertTrue(sampled.isEmpty())
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
