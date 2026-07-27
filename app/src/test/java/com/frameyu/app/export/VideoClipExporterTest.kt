package com.frameyu.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoClipExporterTest {
    @Test
    fun `候选点片段窗口限制在视频范围内`() {
        assertEquals(ClipWindow(0L, 3_000L), createClipWindow(500L, 10_000L))
        assertEquals(ClipWindow(8_000L, 10_000L), createClipWindow(9_500L, 10_000L))
        assertEquals(ClipWindow(50L, 1_500L), createClipWindow(800L, 1_500L))
    }

    @Test
    fun `MP4 带裁切区域时明确报告原生复用不支持`() {
        val error = assertThrows(UnsupportedOperationException::class.java) {
            requireVideoExportStrategy(CropRect(0f, 0f, 0.5f, 1f))
        }

        assertEquals("MP4 视频级裁切当前不支持", error.message)
    }

    @Test
    fun `MP4 缺省裁切区域使用原生复用`() {
        assertEquals(
            VideoExportStrategy.NATIVE_MUX,
            requireVideoExportStrategy(null),
        )
    }
}
