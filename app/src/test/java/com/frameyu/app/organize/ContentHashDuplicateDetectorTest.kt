package com.frameyu.app.organize

import com.frameyu.app.data.db.MediaAssetEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentHashDuplicateDetectorTest {
    @Test
    fun `相同内容分组且不依赖 Uri`() = runBlocking {
        val first = media("content://one", 100L)
        val second = media("content://two", 200L)
        val inputs = mapOf(
            first.uri to ObservableInputStream("same".toByteArray()),
            second.uri to ObservableInputStream("same".toByteArray()),
        )
        val result = ContentHashDuplicateDetector(ContentHashReader { uri ->
            checkNotNull(inputs[uri]).use { stream ->
                sha256HexCancellable(stream, HASH_BUFFER_SIZE)
            }
        })
            .detect(listOf(first, second))

        assertEquals(1, result.groups.size)
        assertEquals(listOf(first, second), result.groups.single().assets)
        assertEquals(first, result.groups.single().suggestedKeep)
        assertTrue(inputs.values.all(ObservableInputStream::closed))
    }

    @Test
    fun `读取失败媒体单独报告且不参与分组`() = runBlocking {
        val failedUri = "content://failed"
        val failedInput = ObservableInputStream(ByteArray(HASH_BUFFER_SIZE), failAtRead = 1)
        val result = ContentHashDuplicateDetector(ContentHashReader { uri ->
            if (uri == failedUri) {
                failedInput.use { stream -> sha256HexCancellable(stream, HASH_BUFFER_SIZE) }
            } else {
                sha256Hex(listOf(uri.toByteArray()))
            }
        }).detect(listOf(media(failedUri, 1L), media("content://ok", 2L)))

        assertTrue(result.groups.isEmpty())
        assertEquals(listOf(failedUri), result.failedUris)
        assertTrue(failedInput.closed)
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
    fun `取消只读取一块并立即关闭输入流`() = runBlocking {
        val scanJob = Job()
        val input = ObservableInputStream(ByteArray(2 * HASH_BUFFER_SIZE)) { readCount ->
            if (readCount == 1) {
                scanJob.cancel(CancellationException("停止长视频扫描"))
            }
        }
        val detector = ContentHashDuplicateDetector(ContentHashReader {
            input.use { stream -> sha256HexCancellable(stream, HASH_BUFFER_SIZE) }
        })

        val thrown = runCatching {
            withContext(scanJob) {
                detector.detect(listOf(media("content://long-video", 1L)))
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(1, input.readCount)
        assertTrue(input.closed)
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

    private class ObservableInputStream(
        bytes: ByteArray,
        private val failAtRead: Int? = null,
        private val afterRead: (Int) -> Unit = {},
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var readCount: Int = 0
            private set
        var closed: Boolean = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCount += 1
            if (readCount == failAtRead) error("读取失败")
            return delegate.read(buffer, offset, length).also { afterRead(readCount) }
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private companion object {
        const val HASH_BUFFER_SIZE = 1024 * 1024
    }
}
