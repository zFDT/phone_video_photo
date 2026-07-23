package com.frameyu.app.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageObservationTest {
    @Test
    fun `标签映射保留文字置信度和索引`() {
        val result = LabelObservationMapper.map(
            RawLabelObservation(label = "舞台", confidence = 0.82f, index = 3),
        )

        assertEquals(LabelObservation("舞台", 0.82f, 3), result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `标签文字不能为空`() {
        LabelObservation(label = " ", confidence = 0.8f, index = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `标签置信度必须在范围内`() {
        LabelObservation(label = "人群", confidence = 1.1f, index = 0)
    }

    @Test
    fun `向量对外暴露副本并保留值`() {
        val source = floatArrayOf(0.1f, 0.2f, 0.3f)
        val observation = EmbeddingObservationMapper.map(source)
        source[0] = 9f

        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), observation.values, 0f)
        assertTrue(observation.values !== source)
        val exposed = observation.values
        exposed[0] = 8f
        assertEquals(0.1f, observation.values[0], 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `向量不能为空`() {
        EmbeddingObservation(floatArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `向量不能包含非有限值`() {
        EmbeddingObservation(floatArrayOf(0.1f, Float.NaN))
    }
}
