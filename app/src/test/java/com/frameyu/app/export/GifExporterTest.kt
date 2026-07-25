package com.frameyu.app.export

import org.junit.Assert.assertEquals
import org.junit.Test

class GifExporterTest {
    @Test
    fun `GIF 帧时间戳按固定帧率生成`() {
        assertEquals(
            listOf(1_000L, 1_125L, 1_250L, 1_375L),
            createGifFrameTimestamps(ClipWindow(1_000L, 1_500L), fps = 8),
        )
    }
}
