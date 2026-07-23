package com.frameyu.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameSampleTest {
    @Test
    fun `像素数量必须与帧尺寸匹配`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameSample(
                frameIndex = 0,
                timestampMillis = 0L,
                width = 2,
                height = 2,
                pixels = IntArray(3),
            )
        }
    }

    @Test
    fun `可以读取像素亮度`() {
        val sample = FrameSample(
            frameIndex = 1,
            timestampMillis = 100L,
            width = 1,
            height = 1,
            pixels = intArrayOf(0xFFFFFFFF.toInt()),
        )

        assertEquals(255.0, sample.luminanceAt(0), 0.001)
    }
}
