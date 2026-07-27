package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarMediaDuplicateDetectorTest {
    @Test
    fun `视觉哈希接近且时间接近的媒体归为相似组`() = runBlocking {
        val first = media("content://first", 1_000L)
        val second = media("content://second", 2_000L)
        val third = media("content://third", 2_000L)
        val hashes = mapOf(first.uri to 0b1010L, second.uri to 0b1011L, third.uri to 0L)

        val result = SimilarMediaDuplicateDetector(
            reader = VisualHashReader { uri -> hashes.getValue(uri) },
            maxHammingDistance = 1,
        ).detect(listOf(first, second, third))

        assertEquals(1, result.groups.size)
        assertEquals(listOf(first, second), result.groups.single().assets)
        assertEquals(first, result.groups.single().suggestedKeep)
    }

    @Test
    fun `时间窗口外或读取失败的媒体不参与相似组`() = runBlocking {
        val old = media("content://old", 0L)
        val failed = media("content://failed", 1_000L)
        val current = media("content://current", 2_000L)

        val result = SimilarMediaDuplicateDetector(
            reader = VisualHashReader { uri ->
                if (uri == failed.uri) error("读取失败") else 1L
            },
            maxHammingDistance = 0,
            timeWindowMillis = 500L,
        ).detect(listOf(old, failed, current))

        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(failed.uri), result.failedUris)
    }

    private fun media(uri: String, takenAtMillis: Long) = MediaAssetEntity(
        id = uri.hashCode().toLong(),
        uri = uri,
        mimeType = "image/jpeg",
        takenAtMillis = takenAtMillis,
        durationMillis = 0L,
        width = 1_080,
        height = 1_920,
        rotationDegrees = 0,
        sizeBytes = 10L,
        isHdr = false,
        relativePath = null,
        lastModifiedMillis = 0L,
    )
}
