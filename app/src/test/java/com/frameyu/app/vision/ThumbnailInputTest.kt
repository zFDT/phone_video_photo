package com.frameyu.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailInputTest {
    @Test
    fun `接受不超过边长和像素预算的缩略图`() {
        val dimensions = ThumbnailConstraints.validate(width = 1024, height = 1024)

        assertEquals(1_048_576L, dimensions.pixelCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝超过最大边长的缩略图`() {
        ThumbnailConstraints.validate(width = 1025, height = 512)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝超过像素预算的缩略图`() {
        ThumbnailConstraints.validate(width = 1024, height = 1025)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝会造成整数溢出的尺寸`() {
        ThumbnailConstraints.validate(width = Int.MAX_VALUE, height = Int.MAX_VALUE)
    }

    @Test
    fun `约束使用长整型保存像素数量`() {
        val pixelCount = ThumbnailConstraints.pixelCount(width = 50_000, height = 50_000)

        assertTrue(pixelCount > Int.MAX_VALUE.toLong())
    }
}
