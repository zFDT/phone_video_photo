package com.frameyu.app.export

import org.junit.Assert.assertEquals
import org.junit.Test

class CropRectTest {
    @Test
    fun `按比例从中心构造横向裁切区域`() {
        assertEquals(
            CropRect(left = 0f, top = 7f / 32f, right = 1f, bottom = 25f / 32f),
            CropRect.fromCenter(aspectRatio = 16f / 9f)
        )
    }

    @Test
    fun `按比例从中心构造纵向裁切区域`() {
        assertEquals(
            CropRect(left = 3f / 8f, top = 0f, right = 5f / 8f, bottom = 1f),
            CropRect.fromCenter(aspectRatio = 1f / 4f)
        )
    }

    @Test
    fun `边界裁切将坐标限制在归一化范围内`() {
        assertEquals(
            CropRect(left = 0f, top = 0.2f, right = 1f, bottom = 1f),
            CropRect.clipped(left = -0.2f, top = 0.2f, right = 1.2f, bottom = 1.4f)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `构造时拒绝超出归一化范围的坐标`() {
        CropRect(left = -0.1f, top = 0f, right = 1f, bottom = 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `构造时拒绝反向或空矩形`() {
        CropRect(left = 0.5f, top = 0f, right = 0.5f, bottom = 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `拒绝非正宽高比`() {
        CropRect.fromCenter(aspectRatio = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `边界裁切后为空矩形时抛出异常`() {
        CropRect.clipped(left = -1f, top = 0f, right = -0.1f, bottom = 1f)
    }
}
