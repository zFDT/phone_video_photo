package com.frameyu.app.analysis.batch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchProgressEmissionPolicyTest {
    @Test
    fun `首个进度立即发布且同一时刻的重复进度被抑制`() {
        var nowMillis = 100L
        val policy = BatchProgressEmissionPolicy(
            minIntervalMillis = 1_000L,
            nowMillis = { nowMillis },
        )

        assertTrue(policy.shouldEmit(progress(completed = 0)))
        assertFalse(policy.shouldEmit(progress(completed = 0)))
    }

    @Test
    fun `普通进度达到最小间隔后发布`() {
        var nowMillis = 100L
        val policy = BatchProgressEmissionPolicy(
            minIntervalMillis = 1_000L,
            nowMillis = { nowMillis },
        )

        assertTrue(policy.shouldEmit(progress(completed = 0)))
        nowMillis = 1_099L
        assertFalse(policy.shouldEmit(progress(completed = 1)))
        nowMillis = 1_100L
        assertTrue(policy.shouldEmit(progress(completed = 2)))
    }

    @Test
    fun `阶段变化仍受节流且终态立即发布`() {
        val policy = BatchProgressEmissionPolicy(
            minIntervalMillis = 1_000L,
            nowMillis = { 100L },
        )

        assertTrue(policy.shouldEmit(progress(completed = 0, stage = "EMBEDDING")))
        assertFalse(policy.shouldEmit(progress(completed = 0, stage = "CLASSIFICATION")))
        assertTrue(policy.shouldEmit(progress(completed = 100, stage = "CLASSIFICATION")))
    }

    private fun progress(
        completed: Int,
        failed: Int = 0,
        stage: String = "EMBEDDING",
    ) = BatchAnalysisProgress(
        completed = completed,
        failed = failed,
        total = 100,
        currentStage = stage,
        currentMediaId = 1L,
    )
}
