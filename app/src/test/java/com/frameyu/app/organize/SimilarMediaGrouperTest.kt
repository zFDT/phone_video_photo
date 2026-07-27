package com.frameyu.app.organize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarMediaGrouperTest {
    @Test
    fun `高相似且时间接近的媒体归为一组`() {
        val groups = SimilarMediaGrouper(threshold = 0.9f).group(
            listOf(
                MediaEmbedding(1L, 1_000L, floatArrayOf(1f, 0f)),
                MediaEmbedding(2L, 2_000L, floatArrayOf(0.99f, 0.1f)),
                MediaEmbedding(3L, 2_000L, floatArrayOf(0f, 1f)),
            ),
        )

        assertEquals(listOf(1L, 2L), groups.single().mediaIds)
        assertTrue(groups.single().similarity >= 0.9f)
    }

    @Test
    fun `时间窗口之外或维度不同的向量不会分组`() {
        val grouper = SimilarMediaGrouper(threshold = 0.9f, timeWindowMillis = 1_000L)
        val groups = grouper.group(
            listOf(
                MediaEmbedding(1L, 0L, floatArrayOf(1f, 0f)),
                MediaEmbedding(2L, 2_000L, floatArrayOf(1f, 0f)),
                MediaEmbedding(3L, 0L, floatArrayOf(1f, 0f, 0f)),
            ),
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `合并多个相似子组时保留组内最高相似度`() {
        val groups = SimilarMediaGrouper(threshold = 0.5f).group(
            listOf(
                MediaEmbedding(1L, 0L, floatArrayOf(1f, 0f, 0f)),
                MediaEmbedding(2L, 0L, floatArrayOf(0.85f, 0.526f, 0f)),
                MediaEmbedding(3L, 0L, floatArrayOf(0f, 1f, 0f)),
                MediaEmbedding(4L, 0L, floatArrayOf(0f, 1f, 0f)),
            ),
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), groups.single().mediaIds)
        assertTrue(groups.single().similarity >= 0.99f)
    }
}
