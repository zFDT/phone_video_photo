package com.frameyu.app.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class PerceptualHashTest {
    @Test
    fun `相同像素产生相同哈希`() {
        val pixels = IntArray(32 * 32) { index ->
            val channel = index % 256
            0xFF000000.toInt() or (channel shl 16) or (channel shl 8) or channel
        }

        val first = PerceptualHash.fromPixels(32, 32, pixels)
        val second = PerceptualHash.fromPixels(32, 32, pixels.copyOf())

        assertEquals(first, second)
    }

    @Test
    fun `汉明距离统计不同位数`() {
        assertEquals(2, PerceptualHash.hammingDistance(0b0001L, 0b0111L))
    }
}
