package com.frameyu.app.analysis.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchAnalysisIntentsTest {
    @Test
    fun `正批次 ID 有效`() {
        assertEquals(42L, BatchAnalysisIntents.validBatchId(42L))
    }

    @Test
    fun `零批次 ID 无效`() {
        assertNull(BatchAnalysisIntents.validBatchId(0L))
    }

    @Test
    fun `负批次 ID 无效`() {
        assertNull(BatchAnalysisIntents.validBatchId(-1L))
    }

    @Test
    fun `打开批次只接受打开动作`() {
        assertEquals(
            42L,
            BatchAnalysisIntents.openBatchId(BatchAnalysisIntents.ACTION_OPEN, 42L),
        )
        assertNull(
            BatchAnalysisIntents.openBatchId(BatchAnalysisIntents.ACTION_PAUSE, 42L),
        )
    }

    @Test
    fun `暂停批次只接受暂停动作`() {
        assertEquals(
            42L,
            BatchAnalysisIntents.pauseBatchId(BatchAnalysisIntents.ACTION_PAUSE, 42L),
        )
        assertNull(
            BatchAnalysisIntents.pauseBatchId(BatchAnalysisIntents.ACTION_OPEN, 42L),
        )
    }

    @Test
    fun `同一批次的暂停和打开请求码互不覆盖`() {
        assertNotEquals(
            BatchAnalysisIntents.pauseRequestCode(42L),
            BatchAnalysisIntents.openRequestCode(42L),
        )
    }
}
