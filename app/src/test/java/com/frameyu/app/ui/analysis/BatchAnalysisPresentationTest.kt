package com.frameyu.app.ui.analysis

import com.frameyu.app.data.db.AnalysisBatchEntity
import com.frameyu.app.data.db.AnalysisBatchWithProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnalysisPresentationTest {
    @Test
    fun `运行中批次映射进度阶段和按钮能力`() {
        val state = batchAnalysisUiState(
            batchId = 7L,
            progress = progress(
                status = "RUNNING",
                total = 20L,
                completed = 8L,
                failed = 2L,
            ),
            currentStage = "CLASSIFICATION",
        )

        assertEquals(7L, state.batchId)
        assertEquals(20L, state.total)
        assertEquals(8L, state.completed)
        assertEquals(2L, state.failed)
        assertEquals("图片分类", state.currentStageLabel)
        assertEquals(0.5f, state.progressFraction)
        assertTrue(state.actions.canPause)
        assertTrue(state.actions.canCancel)
        assertFalse(state.actions.canResume)
        assertFalse(state.actions.canRetryFailures)
        assertFalse(state.actions.canOpenOrganizer)
    }

    @Test
    fun `暂停待处理和完成状态映射正确按钮`() {
        val paused = batchAnalysisUiState(1L, progress(status = "PAUSED"), null)
        val attention = batchAnalysisUiState(
            2L,
            progress(status = "NEEDS_ATTENTION", failed = 1L),
            "QUALITY",
        )
        val completed = batchAnalysisUiState(3L, progress(status = "COMPLETED"), null)

        assertTrue(paused.actions.canResume)
        assertTrue(paused.actions.canCancel)
        assertFalse(paused.actions.canPause)
        assertTrue(attention.actions.canRetryFailures)
        assertTrue(attention.actions.canCancel)
        assertEquals("质量评分", attention.currentStageLabel)
        assertTrue(completed.actions.canOpenOrganizer)
        assertFalse(completed.actions.canCancel)
    }

    @Test
    fun `空批次进度安全映射为零`() {
        val state = batchAnalysisUiState(1L, progress(total = 0L), null)

        assertEquals(0f, state.progressFraction)
        assertEquals("等待开始", state.currentStageLabel)
    }

    private fun progress(
        status: String = "QUEUED",
        total: Long = 4L,
        completed: Long = 0L,
        failed: Long = 0L,
    ) = AnalysisBatchWithProgress(
        batch = AnalysisBatchEntity(
            id = 1L,
            status = status,
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        ),
        totalWorkItems = total,
        completedWorkItems = completed,
        runningWorkItems = if (status == "RUNNING") 1L else 0L,
        pendingWorkItems = (total - completed - failed).coerceAtLeast(0L),
        failedWorkItems = failed,
    )
}
