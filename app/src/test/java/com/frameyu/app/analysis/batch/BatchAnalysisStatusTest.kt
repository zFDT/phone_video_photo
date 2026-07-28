package com.frameyu.app.analysis.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAnalysisStatusTest {
    @Test
    fun `持久状态字符串穷举解析`() {
        BatchAnalysisStatus.entries.forEach { status ->
            assertEquals(status, BatchAnalysisStatus.fromPersisted(status.name))
        }
    }

    @Test
    fun `未知持久状态明确失败`() {
        val thrown = runCatching {
            BatchAnalysisStatus.fromPersisted("BROKEN")
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
    }
}
