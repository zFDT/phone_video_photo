package com.frameyu.app.export

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoClipExporterTest {
    @Test
    fun `候选点片段窗口限制在视频范围内`() {
        assertEquals(ClipWindow(0L, 3_000L), createClipWindow(500L, 10_000L))
        assertEquals(ClipWindow(8_000L, 10_000L), createClipWindow(9_500L, 10_000L))
        assertEquals(ClipWindow(50L, 1_500L), createClipWindow(800L, 1_500L))
    }
}
