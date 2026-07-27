package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentHashDuplicateDetectorTest {
    @Test
    fun `相同内容分组且不依赖 Uri`() = runBlocking {
        val first = media("content://one", 100L)
        val second = media("content://two", 200L)
        val result = ContentHashDuplicateDetector(ContentHashReader { listOf("same".toByteArray()) })
            .detect(listOf(first, second))

        assertEquals(1, result.groups.size)
        assertEquals(listOf(first, second), result.groups.single().assets)
        assertEquals(first, result.groups.single().suggestedKeep)
    }

    @Test
    fun `读取失败媒体单独报告且不参与分组`() = runBlocking {
        val failedUri = "content://failed"
        val result = ContentHashDuplicateDetector(ContentHashReader { uri ->
            if (uri == failedUri) error("读取失败") else listOf(uri.toByteArray())
        }).detect(listOf(media(failedUri, 1L), media("content://ok", 2L)))

        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(failedUri), result.failedUris)
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
