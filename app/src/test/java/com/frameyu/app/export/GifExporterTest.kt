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

    @Test
    fun `GIF 裁切区域转换为原始帧像素边界`() {
        assertEquals(
            PixelCropBounds(left = 10, top = 20, right = 60, bottom = 60),
            calculateCropBounds(
                width = 100,
                height = 80,
                cropRect = CropRect(left = 0.1f, top = 0.25f, right = 0.6f, bottom = 0.75f),
            ),
        )
    }

    @Test
    fun `GIF 缺省裁切区域保留完整原始帧`() {
        assertEquals(
            PixelCropBounds(left = 0, top = 0, right = 100, bottom = 80),
            calculateCropBounds(width = 100, height = 80, cropRect = null),
        )
    }
}
