package com.frameyu.app.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class FrameModelResultTest {
    @Test
    fun `分类结果允许模型未提供索引`() {
        LabelScore(label = "stage", score = 0.5f, index = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `分类结果拒绝非有限置信度`() {
        LabelScore(label = "stage", score = Float.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `分类结果拒绝过小索引`() {
        LabelScore(label = "stage", score = 0.5f, index = -2)
    }

    @Test
    fun `检测结果接受合法归一化坐标`() {
        DetectionObservation(
            label = "person",
            score = 0.8f,
            left = 0.1f,
            top = 0.2f,
            right = 0.9f,
            bottom = 1f,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `检测结果拒绝退化坐标`() {
        DetectionObservation(
            label = "person",
            score = 0.8f,
            left = 0.5f,
            top = 0.2f,
            right = 0.5f,
            bottom = 0.8f,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `检测结果拒绝越界坐标`() {
        DetectionObservation(
            label = "person",
            score = 0.8f,
            left = -0.1f,
            top = 0.2f,
            right = 0.5f,
            bottom = 0.8f,
        )
    }

    @Test
    fun `统一结果复制输入和输出向量`() {
        val source = floatArrayOf(0.3f, 0.4f)
        val result = FrameModelResult(
            labels = emptyList(),
            embedding = source,
            detections = emptyList(),
            failures = emptyList(),
        )
        source[0] = 9f

        val firstRead = requireNotNull(result.embedding)
        assertArrayEquals(floatArrayOf(0.3f, 0.4f), firstRead, 0f)
        assertNotSame(source, firstRead)
        firstRead[0] = 8f
        assertArrayEquals(floatArrayOf(0.3f, 0.4f), result.embedding, 0f)
    }
}
