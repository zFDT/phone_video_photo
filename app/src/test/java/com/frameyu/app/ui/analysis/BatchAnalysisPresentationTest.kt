package com.frameyu.app.ui.analysis

import com.frameyu.app.data.db.AnalysisBatchEntity
import com.frameyu.app.data.db.AnalysisBatchWithProgress
import com.frameyu.app.data.db.AnalysisWorkItemEntity
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

    @Test
    fun `永久失败工作项在没有当前阶段时仍展示持久化原因`() {
        val state = batchAnalysisUiState(
            batchId = 1L,
            progress = progress(status = "NEEDS_ATTENTION", failed = 1L),
            currentStage = null,
            failedItems = listOf(
                failedItem(
                    id = 9L,
                    mediaAssetId = 77L,
                    stage = "CONDITIONAL_DETECTION",
                    status = "PERMANENT_FAILED",
                    message = "模型输出格式不支持",
                ),
            ),
        )

        assertEquals("无正在执行阶段", state.currentStageLabel)
        assertEquals(1, state.failureItems.size)
        assertEquals(9L, state.failureItems.single().id)
        assertEquals(77L, state.failureItems.single().mediaAssetId)
        assertEquals("目标检测", state.failureItems.single().stageLabel)
        assertEquals("永久失败", state.failureItems.single().statusLabel)
        assertEquals("模型输出格式不支持", state.failureItems.single().message)
    }

    @Test
    fun `批次操作入口在动作运行时拒绝再次启动`() {
        assertTrue(canStartBatchAction(actionRunning = false))
        assertFalse(canStartBatchAction(actionRunning = true))
    }

    private fun failedItem(
        id: Long,
        mediaAssetId: Long,
        stage: String,
        status: String,
        message: String?,
    ) = AnalysisWorkItemEntity(
        id = id,
        batchId = 1L,
        mediaAssetId = mediaAssetId,
        stage = stage,
        status = status,
        analyzerVersion = "test-v1",
        errorMessage = message,
        updatedAtMillis = 1L,
    )

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
