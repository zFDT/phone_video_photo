package com.frameyu.app.analysis.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchStateMachineTest {
    @Test
    fun `暂停任务可以重新排队`() {
        assertEquals(
            WorkItemStatus.PENDING,
            BatchStateMachine.transition(WorkItemStatus.PAUSED, WorkItemEvent.RESUME),
        )
    }

    @Test
    fun `完成任务不能再次开始`() {
        assertInvalidTransition(WorkItemStatus.COMPLETED, WorkItemEvent.START)
    }

    @Test
    fun `所有合法状态转换均返回目标状态`() {
        val transitions = mapOf(
            (WorkItemStatus.PENDING to WorkItemEvent.START) to WorkItemStatus.RUNNING,
            (WorkItemStatus.PENDING to WorkItemEvent.CANCEL) to WorkItemStatus.CANCELLED,
            (WorkItemStatus.RUNNING to WorkItemEvent.COMPLETE) to WorkItemStatus.COMPLETED,
            (WorkItemStatus.RUNNING to WorkItemEvent.PAUSE) to WorkItemStatus.PAUSED,
            (WorkItemStatus.RUNNING to WorkItemEvent.RETRYABLE_FAILURE) to WorkItemStatus.RETRYABLE_FAILED,
            (WorkItemStatus.RUNNING to WorkItemEvent.PERMANENT_FAILURE) to WorkItemStatus.PERMANENT_FAILED,
            (WorkItemStatus.RUNNING to WorkItemEvent.CANCEL) to WorkItemStatus.CANCELLED,
            (WorkItemStatus.PAUSED to WorkItemEvent.RESUME) to WorkItemStatus.PENDING,
            (WorkItemStatus.PAUSED to WorkItemEvent.CANCEL) to WorkItemStatus.CANCELLED,
            (WorkItemStatus.RETRYABLE_FAILED to WorkItemEvent.RETRY) to WorkItemStatus.PENDING,
            (WorkItemStatus.RETRYABLE_FAILED to WorkItemEvent.CANCEL) to WorkItemStatus.CANCELLED,
        )

        transitions.forEach { (transition, expected) ->
            assertEquals(
                "${transition.first} + ${transition.second}",
                expected,
                BatchStateMachine.transition(transition.first, transition.second),
            )
        }
    }

    @Test
    fun `批次取消会停止所有未完成项`() {
        val unfinishedStatuses = listOf(
            WorkItemStatus.PENDING,
            WorkItemStatus.RUNNING,
            WorkItemStatus.PAUSED,
            WorkItemStatus.RETRYABLE_FAILED,
        )

        unfinishedStatuses.forEach { status ->
            assertEquals(
                status.name,
                WorkItemStatus.CANCELLED,
                BatchStateMachine.transition(status, WorkItemEvent.CANCEL),
            )
        }
    }

    @Test
    fun `所有终态均拒绝任何事件`() {
        val terminalStatuses = listOf(
            WorkItemStatus.COMPLETED,
            WorkItemStatus.PERMANENT_FAILED,
            WorkItemStatus.CANCELLED,
        )

        terminalStatuses.forEach { status ->
            WorkItemEvent.entries.forEach { event ->
                assertInvalidTransition(status, event)
            }
        }
    }

    @Test
    fun `未定义的非终态组合被拒绝`() {
        assertInvalidTransition(WorkItemStatus.PENDING, WorkItemEvent.COMPLETE)
        assertInvalidTransition(WorkItemStatus.RUNNING, WorkItemEvent.RESUME)
        assertInvalidTransition(WorkItemStatus.PAUSED, WorkItemEvent.RETRY)
        assertInvalidTransition(WorkItemStatus.RETRYABLE_FAILED, WorkItemEvent.START)
    }

    @Test
    fun `阶段依赖与分析管线一致`() {
        val expected = mapOf(
            AnalysisStage.METADATA to emptySet(),
            AnalysisStage.CONTENT_HASH to setOf(AnalysisStage.METADATA),
            AnalysisStage.QUALITY to setOf(AnalysisStage.METADATA),
            AnalysisStage.CLASSIFICATION to setOf(AnalysisStage.METADATA),
            AnalysisStage.EMBEDDING to setOf(AnalysisStage.METADATA),
            AnalysisStage.CONDITIONAL_DETECTION to setOf(AnalysisStage.CLASSIFICATION),
            AnalysisStage.RECOMMENDATION to setOf(
                AnalysisStage.CONTENT_HASH,
                AnalysisStage.QUALITY,
                AnalysisStage.CLASSIFICATION,
                AnalysisStage.EMBEDDING,
                AnalysisStage.CONDITIONAL_DETECTION,
            ),
        )

        AnalysisStage.entries.forEach { stage ->
            assertEquals(stage.name, expected.getValue(stage), AnalysisStageDependencies.requiredFor(stage))
        }
    }

    @Test
    fun `调用方不能修改阶段依赖或影响后续结果`() {
        val dependencies = AnalysisStageDependencies.requiredFor(AnalysisStage.RECOMMENDATION)

        assertThrows(UnsupportedOperationException::class.java) {
            (dependencies as MutableSet<AnalysisStage>).remove(AnalysisStage.CONTENT_HASH)
        }
        assertTrue(
            AnalysisStage.CONTENT_HASH in
                AnalysisStageDependencies.requiredFor(AnalysisStage.RECOMMENDATION),
        )
    }

    @Test
    fun `分析器版本稳定且为六十四位大写十六进制`() {
        val version = AnalyzerVersion.create(AnalysisStage.QUALITY, null, "quality-v1")

        assertEquals(version, AnalyzerVersion.create(AnalysisStage.QUALITY, null, "quality-v1"))
        assertTrue(version.matches(Regex("[0-9A-F]{64}")))
    }

    @Test
    fun `模型摘要大小写和策略首尾空白会被归一化`() {
        val lowerSha = "abcdef0123456789".repeat(4)
        val upperSha = lowerSha.uppercase()

        assertEquals(
            AnalyzerVersion.create(AnalysisStage.CLASSIFICATION, upperSha, "policy-v1"),
            AnalyzerVersion.create(AnalysisStage.CLASSIFICATION, lowerSha, "  policy-v1  "),
        )
    }

    @Test
    fun `阶段模型或策略任一变化都会改变分析器版本`() {
        val modelSha = "0123456789ABCDEF".repeat(4)
        val otherModelSha = "FEDCBA9876543210".repeat(4)
        val baseline = AnalyzerVersion.create(AnalysisStage.CLASSIFICATION, modelSha, "policy-v1")

        assertNotEquals(
            baseline,
            AnalyzerVersion.create(AnalysisStage.EMBEDDING, modelSha, "policy-v1"),
        )
        assertNotEquals(
            baseline,
            AnalyzerVersion.create(AnalysisStage.CLASSIFICATION, otherModelSha, "policy-v1"),
        )
        assertNotEquals(
            baseline,
            AnalyzerVersion.create(AnalysisStage.CLASSIFICATION, modelSha, "policy-v2"),
        )
    }

    @Test
    fun `非法模型摘要被拒绝`() {
        listOf(
            "",
            "A".repeat(63),
            "A".repeat(65),
            "G".repeat(64),
        ).forEach { invalidSha ->
            assertThrows(IllegalArgumentException::class.java) {
                AnalyzerVersion.create(AnalysisStage.EMBEDDING, invalidSha, "policy-v1")
            }
        }
    }

    @Test
    fun `空白策略版本被拒绝`() {
        listOf("", " ", "\t\r\n").forEach { policyVersion ->
            assertThrows(IllegalArgumentException::class.java) {
                AnalyzerVersion.create(AnalysisStage.METADATA, null, policyVersion)
            }
        }
    }

    private fun assertInvalidTransition(source: WorkItemStatus, event: WorkItemEvent) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BatchStateMachine.transition(source, event)
        }

        assertTrue(error.message.orEmpty().contains("非法状态转换"))
        assertTrue(error.message.orEmpty().contains(source.name))
        assertTrue(error.message.orEmpty().contains(event.name))
    }
}
