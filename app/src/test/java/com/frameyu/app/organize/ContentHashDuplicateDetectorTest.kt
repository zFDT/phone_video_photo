package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test
    fun `读取器取消会立即传播而不是记录为普通失败`() = runBlocking {
        val cancellation = CancellationException("用户取消扫描")
        val detector = ContentHashDuplicateDetector(ContentHashReader { throw cancellation })

        val thrown = runCatching {
            detector.detect(listOf(media("content://cancelled", 1L)))
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun `分块计算在一兆字节边界检查协程取消`() = runBlocking {
        val scanJob = Job()
        var chunksRead = 0
        val chunks = Iterable {
            listOf(ByteArray(1024 * 1024), ByteArray(1024 * 1024)).iterator().let { delegate ->
                object : Iterator<ByteArray> {
                    override fun hasNext(): Boolean = delegate.hasNext()

                    override fun next(): ByteArray {
                        chunksRead += 1
                        return delegate.next().also {
                            if (chunksRead == 1) scanJob.cancel(CancellationException("停止长视频扫描"))
                        }
                    }
                }
            }
        }
        val detector = ContentHashDuplicateDetector(ContentHashReader { chunks })

        val thrown = runCatching {
            withContext(scanJob) {
                detector.detect(listOf(media("content://long-video", 1L)))
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(1, chunksRead)
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
