package com.frameyu.app.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `明显不同图样不会落入相同哈希`() {
        val black = IntArray(32 * 32) { gray(0) }
        val changed = IntArray(32 * 32) { index ->
            val x = index % 32
            if (x < 16 && x % 4 != 2) gray(255) else gray(0)
        }

        val blackHash = PerceptualHash.fromPixels(32, 32, black)
        val changedHash = PerceptualHash.fromPixels(32, 32, changed)

        assertNotEquals(blackHash, changedHash)
    }

    @Test
    fun `轻微亮度变化保持较小哈希距离`() {
        val original = IntArray(32 * 32) { index ->
            val x = index % 32
            val y = index / 32
            gray(x * 4 + y * 2)
        }
        val brighter = original.map { color ->
            val value = ((color and 0xFF) + 20).coerceAtMost(255)
            gray(value)
        }.toIntArray()

        val distance = PerceptualHash.hammingDistance(
            PerceptualHash.fromPixels(32, 32, original),
            PerceptualHash.fromPixels(32, 32, brighter),
        )

        assertTrue("亮度变化后的汉明距离为 $distance", distance <= 4)
    }

    @Test
    fun `等比例输入保持较小哈希距离`() {
        fun checker(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val cell = x * 4 / width + y * 4 / height
            gray(if (cell % 2 == 0) 32 else 224)
        }

        val distance = PerceptualHash.hammingDistance(
            PerceptualHash.fromPixels(32, 32, checker(32, 32)),
            PerceptualHash.fromPixels(64, 64, checker(64, 64)),
        )

        assertTrue("等比例输入的汉明距离为 $distance", distance <= 4)
    }

    private companion object {
        fun gray(value: Int): Int =
            0xFF000000.toInt() or (value shl 16) or (value shl 8) or value
    }
}
