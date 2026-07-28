package com.frameyu.app.sampling

import com.frameyu.app.analysis.FrameScore
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.vision.FrameModelResult
import com.frameyu.app.vision.LabelScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameModelOrchestratorTest {
    @Test
    fun `规则候选先保存且模型成功结果在checkpoint前保存`() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingVideoFrameModelStore(events)
        val orchestrator = VideoFrameModelOrchestrator(
            analyzerProvider = {
                VideoFrameModelOperation<String> { _, _, highValue ->
                    events += "analyze:$highValue"
                    successfulResult()
                }
            },
            store = store,
        )
        val plan = SamplingPlan.create(1L)

        val result = VideoAnalysisTask(
            SamplingCheckpointStore { events += "checkpoint" },
        ).run(plan) { timestampMillis ->
            orchestrator.process(
                mediaId = 7L,
                candidate = candidate(timestampMillis, score = 70),
                frame = "frame",
            )
        }

        assertTrue(result is VideoAnalysisTaskResult.Completed)
        assertEquals(
            listOf(
                "candidate:PENDING",
                "analyze:true",
                "result",
                "status:COMPLETED",
                "checkpoint",
            ),
            events,
        )
    }

    @Test
    fun `模型异常仍保留规则候选并标记可重试`() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingVideoFrameModelStore(events)
        val orchestrator = VideoFrameModelOrchestrator(
            analyzerProvider = {
                VideoFrameModelOperation<String> { _, _, _ ->
                    throw IllegalStateException("模型暂不可用")
                }
            },
            store = store,
        )

        orchestrator.process(7L, candidate(0L), "frame")

        assertEquals("candidate:PENDING", events.first())
        assertEquals("status:RETRYABLE_FAILED", events.last())
        assertEquals("模型暂不可用", store.statuses.single().errorMessage)
        assertTrue(store.results.isEmpty())
    }

    @Test
    fun `模型返回局部失败时先保存结果再标记可重试`() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingVideoFrameModelStore(events)
        val orchestrator = VideoFrameModelOrchestrator(
            analyzerProvider = {
                VideoFrameModelOperation<String> { _, _, _ ->
                    FrameModelResult(
                        labels = listOf(LabelScore("concert", 0.8f)),
                        embedding = null,
                        detections = emptyList(),
                        failures = listOf("向量失败"),
                    )
                }
            },
            store = store,
        )

        orchestrator.process(7L, candidate(0L), "frame")

        assertEquals(listOf("candidate:PENDING", "result", "status:RETRYABLE_FAILED"), events)
        assertEquals("向量失败", store.statuses.single().errorMessage)
    }

    @Test
    fun `CancellationException和Error传播且不写失败状态`() {
        val cancellationStore = RecordingVideoFrameModelStore(mutableListOf())
        val cancellation = CancellationException("cancel")
        val cancellationOrchestrator = VideoFrameModelOrchestrator(
            analyzerProvider = {
                VideoFrameModelOperation<String> { _, _, _ -> throw cancellation }
            },
            store = cancellationStore,
        )

        val actualCancellation = assertThrows<CancellationException> {
            runBlocking { cancellationOrchestrator.process(7L, candidate(0L), "frame") }
        }
        assertTrue(actualCancellation === cancellation)
        assertTrue(cancellationStore.statuses.isEmpty())

        val errorStore = RecordingVideoFrameModelStore(mutableListOf())
        val expectedError = AssertionError("fatal")
        val errorOrchestrator = VideoFrameModelOrchestrator(
            analyzerProvider = {
                VideoFrameModelOperation<String> { _, _, _ -> throw expectedError }
            },
            store = errorStore,
        )

        val actualError = assertThrows<AssertionError> {
            runBlocking { errorOrchestrator.process(7L, candidate(0L), "frame") }
        }
        assertTrue(actualError === expectedError)
        assertTrue(errorStore.statuses.isEmpty())
    }

    @Test
    fun `分析器初始化失败在单次运行只尝试一次`() = runBlocking {
        var initializationCount = 0
        val store = RecordingVideoFrameModelStore(mutableListOf())
        val orchestrator = VideoFrameModelOrchestrator<String>(
            analyzerProvider = {
                initializationCount++
                throw IllegalStateException("初始化失败")
            },
            store = store,
        )

        orchestrator.process(7L, candidate(0L), "first")
        orchestrator.process(7L, candidate(1_000L), "second")

        assertEquals(1, initializationCount)
        assertEquals(2, store.statuses.size)
        assertTrue(store.statuses.all { it.status == CandidateModelStatus.RETRYABLE_FAILED })
        assertEquals(1, store.statuses.map(ModelStatusUpdate::errorMessage).distinct().size)
    }

    @Test
    fun `结果持久化异常传播且不会伪装完成`() {
        val expected = IllegalStateException("磁盘写入失败")
        val store = object : VideoFrameModelStore {
            override suspend fun saveRuleCandidate(candidate: AnalysisCandidateEntity) = Unit

            override suspend fun saveFrameResult(
                mediaId: Long,
                timestampMillis: Long,
                result: FrameModelResult,
            ) {
                throw expected
            }

            override suspend fun updateCandidateModelStatus(
                candidateId: Long,
                status: CandidateModelStatus,
                errorMessage: String?,
            ) = Unit
        }
        val orchestrator = VideoFrameModelOrchestrator(
            analyzerProvider = {
                VideoFrameModelOperation<String> { _, _, _ -> successfulResult() }
            },
            store = store,
        )

        val actual = assertThrows<IllegalStateException> {
            runBlocking { orchestrator.process(7L, candidate(0L), "frame") }
        }

        assertTrue(actual === expected)
    }

    private fun candidate(timestampMillis: Long, score: Int = 50): AnalysisCandidateEntity =
        AnalysisCandidateMapper.toEntity(
            7L,
            FrameScore(0, timestampMillis, 1, 2, 3, 4, 5, score),
        )

    private fun successfulResult() = FrameModelResult(
        labels = listOf(LabelScore("concert", 0.9f)),
        embedding = floatArrayOf(1f),
        detections = emptyList(),
        failures = emptyList(),
    )

    private class RecordingVideoFrameModelStore(
        private val events: MutableList<String>,
    ) : VideoFrameModelStore {
        val results = mutableListOf<FrameModelResult>()
        val statuses = mutableListOf<ModelStatusUpdate>()

        override suspend fun saveRuleCandidate(candidate: AnalysisCandidateEntity) {
            events += "candidate:${candidate.modelStatus}"
        }

        override suspend fun saveFrameResult(
            mediaId: Long,
            timestampMillis: Long,
            result: FrameModelResult,
        ) {
            events += "result"
            results += result
        }

        override suspend fun updateCandidateModelStatus(
            candidateId: Long,
            status: CandidateModelStatus,
            errorMessage: String?,
        ) {
            events += "status:$status"
            statuses += ModelStatusUpdate(status, errorMessage)
        }
    }

    private data class ModelStatusUpdate(
        val status: CandidateModelStatus,
        val errorMessage: String?,
    )

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
