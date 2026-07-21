package com.frameyu.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnalysisCandidateTest {
    @Test
    fun `候选帧评分允许边界值`() {
        val candidate = AnalysisCandidate(
            id = 1L,
            mediaAssetId = 2L,
            timestampMillis = 0L,
            score = 0,
            sharpnessScore = 100,
            subjectScore = 0,
            expressionScore = 100,
            compositionScore = 0,
            sceneScore = 100,
            thumbnailPath = "thumb.jpg",
            isSelected = false,
        )

        assertEquals(0, candidate.score)
        assertEquals(100, candidate.sharpnessScore)
    }

    @Test
    fun `候选帧评分超出范围时拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCandidate(
                id = 1L,
                mediaAssetId = 2L,
                timestampMillis = 100L,
                score = 101,
                sharpnessScore = 50,
                subjectScore = 50,
                expressionScore = 50,
                compositionScore = 50,
                sceneScore = 50,
                thumbnailPath = null,
                isSelected = false,
            )
        }
    }

    @Test
    fun `候选帧负时间戳被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisCandidate(
                id = 1L,
                mediaAssetId = 2L,
                timestampMillis = -1L,
                score = 50,
                sharpnessScore = 50,
                subjectScore = 50,
                expressionScore = 50,
                compositionScore = 50,
                sceneScore = 50,
                thumbnailPath = null,
                isSelected = false,
            )
        }
    }

    @Test
    fun `分析任务进度限制在零到一百并支持状态转换`() {
        val task = AnalysisTask(
            id = 1L,
            mediaAssetId = 2L,
            status = AnalysisTask.Status.QUEUED,
            progress = 0,
            currentTimestampMillis = 0L,
            errorMessage = null,
            updatedAtMillis = 1L,
        )

        val runningTask = task.transitionTo(
            status = AnalysisTask.Status.RUNNING,
            updatedAtMillis = 2L,
        )

        assertEquals(AnalysisTask.Status.RUNNING, runningTask.status)
        assertEquals(0, runningTask.progress)
        assertThrows(IllegalArgumentException::class.java) {
            task.copy(progress = 101)
        }
    }
}
