package com.frameyu.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidatePresentationTest {
    @Test
    fun `候选帧时间点格式化为分秒`() {
        assertEquals("00:00.000", formatCandidateTimestamp(0L))
        assertEquals("01:05.250", formatCandidateTimestamp(65_250L))
    }
}
