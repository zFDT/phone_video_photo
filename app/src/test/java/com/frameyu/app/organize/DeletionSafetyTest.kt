package com.frameyu.app.organize

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionSafetyTest {
    @Test
    fun `内容摘要使用标准 SHA-256`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex(listOf("abc".toByteArray())),
        )
    }

    @Test
    fun `可取消内容摘要成功后关闭输入流`() = runBlocking {
        val input = ObservableInputStream("abc".toByteArray())

        val hash = sha256HexCancellable(input)

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertTrue(input.closed)
    }

    @Test
    fun `可取消内容摘要在完整读取前传播取消并关闭输入流`() = runBlocking {
        val scanJob = Job()
        val input = ObservableInputStream(ByteArray(2 * 1024 * 1024)) { readCount ->
            if (readCount == 1) {
                scanJob.cancel(CancellationException("停止删除复验"))
            }
        }

        val thrown = runCatching {
            withContext(scanJob) {
                sha256HexCancellable(input)
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(1, input.readCount)
        assertTrue(input.closed)
    }

    @Test
    fun `可取消内容摘要读取异常时立即关闭输入流`() = runBlocking {
        val input = ObservableInputStream(ByteArray(2 * 1024 * 1024), failAtRead = 2)

        val thrown = runCatching { sha256HexCancellable(input) }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertEquals(2, input.readCount)
        assertTrue(input.closed)
    }

    @Test
    fun `删除前验证会读取完整组含保留项并复算 exact hash`() = runBlocking {
        val plan = exactPlan()
        val metadataReads = mutableListOf<String>()
        val hashReads = mutableListOf<String>()
        val validator = DeletionSafetyValidator(
            metadataReader = LiveMediaMetadataReader { expected ->
                metadataReads += expected.uri
                expected.toLiveMetadata()
            },
            sha256Reader = ContentSha256Reader { uri ->
                hashReads += uri
                "same-hash"
            },
        )

        val result = validator.validate(plan)

        assertEquals(DeletionValidationResult.Valid, result)
        assertEquals(listOf("content://media/1", "content://media/2"), metadataReads)
        assertEquals(listOf("content://media/1", "content://media/2"), hashReads)
    }

    @Test
    fun `任一完整组成员缺失或元数据变化都会阻止删除`() = runBlocking {
        val plan = exactPlan()
        val variants = listOf<LiveMediaMetadata?>(
            null,
            LiveMediaMetadata(99L, "content://media/2", 2L, 2L),
            LiveMediaMetadata(2L, "content://media/2", 99L, 2L),
            LiveMediaMetadata(2L, "content://media/2", 2L, 99L),
        )

        variants.forEach { changed ->
            val validator = DeletionSafetyValidator(
                metadataReader = LiveMediaMetadataReader { expected ->
                    if (expected.mediaId == 2L) changed else expected.toLiveMetadata()
                },
                sha256Reader = ContentSha256Reader { "same-hash" },
            )

            val result = validator.validate(plan)

            assertTrue(result is DeletionValidationResult.Blocked)
        }
    }

    @Test
    fun `exact hash 变化或读取失败都会阻止删除`() = runBlocking {
        val plan = exactPlan()
        val changed = DeletionSafetyValidator(
            metadataReader = LiveMediaMetadataReader { it.toLiveMetadata() },
            sha256Reader = ContentSha256Reader { uri ->
                if (uri.endsWith("/2")) "changed" else "same-hash"
            },
        ).validate(plan)
        val failed = DeletionSafetyValidator(
            metadataReader = LiveMediaMetadataReader { it.toLiveMetadata() },
            sha256Reader = ContentSha256Reader { error("读取失败") },
        ).validate(plan)

        assertTrue(changed is DeletionValidationResult.Blocked)
        assertTrue(failed is DeletionValidationResult.Blocked)
    }

    @Test
    fun `仓库只在 live 验证通过后调用删除器并保留部分失败结果`() = runBlocking {
        val plan = exactPlan()
        var deleteCalls = 0
        val expected = MediaDeletionResult(
            succeededUris = listOf("content://media/2"),
            failures = listOf(MediaDeletionFailure("content://media/3", "系统拒绝")),
        )
        val repository = OrganizerRepository(
            detector = DuplicateDetector(),
            deleter = { uris ->
                deleteCalls += 1
                assertEquals(listOf("content://media/2"), uris)
                expected
            },
            deletionSafetyValidator = validValidator(),
        )

        val result = repository.delete(plan)

        assertEquals(1, deleteCalls)
        assertEquals(DeletionExecutionResult.Completed(expected), result)
    }

    @Test
    fun `仓库在 live 验证失败时不会调用删除器`() = runBlocking {
        val plan = exactPlan()
        var deleted = false
        val repository = OrganizerRepository(
            detector = DuplicateDetector(),
            deleter = {
                deleted = true
                MediaDeletionResult(emptyList(), emptyList())
            },
            deletionSafetyValidator = DeletionSafetyValidator(
                metadataReader = LiveMediaMetadataReader { null },
                sha256Reader = ContentSha256Reader { "same-hash" },
            ),
        )

        val result = repository.delete(plan)

        assertFalse(deleted)
        assertTrue(result is DeletionExecutionResult.Blocked)
    }

    @Test
    fun `删除验证传播 CancellationException`() = runBlocking {
        val cancellation = CancellationException("取消")
        val validator = DeletionSafetyValidator(
            metadataReader = LiveMediaMetadataReader { throw cancellation },
            sha256Reader = ContentSha256Reader { "same-hash" },
        )

        val thrown = runCatching { validator.validate(exactPlan()) }.exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun `逐项删除仅同步成功 URI 并报告普通失败`() = runBlocking {
        val removedFromRoom = mutableListOf<List<String>>()

        val result = executeMediaDeletion(
            uris = listOf("success", "not-handled", "failed"),
            deleteOne = { uri ->
                when (uri) {
                    "success" -> true
                    "not-handled" -> false
                    else -> error("provider error")
                }
            },
            removeSucceededFromIndex = { removedFromRoom += it },
        )

        assertEquals(listOf("success"), result.succeededUris)
        assertEquals(listOf("not-handled", "failed"), result.failures.map(MediaDeletionFailure::uri))
        assertEquals(listOf(listOf("success")), removedFromRoom)
    }

    @Test
    fun `逐项删除取消前仍同步已成功 URI 并传播 CancellationException`() = runBlocking {
        val cancellation = CancellationException("取消")
        val removedFromRoom = mutableListOf<List<String>>()

        val thrown = runCatching {
            executeMediaDeletion(
                uris = listOf("first", "cancelled"),
                deleteOne = { uri ->
                    if (uri == "cancelled") throw cancellation
                    true
                },
                removeSucceededFromIndex = { removedFromRoom += it },
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf(listOf("first")), removedFromRoom)
    }

    private fun validValidator() = DeletionSafetyValidator(
        metadataReader = LiveMediaMetadataReader { it.toLiveMetadata() },
        sha256Reader = ContentSha256Reader { "same-hash" },
    )

    private fun exactPlan() = DeletionPlan(
        visibleFilter = OrganizerFilter.EXACT_DUPLICATE,
        items = listOf(DeletionPlanItem(2L, "content://media/2", 2L, 2L)),
        affectedGroups = listOf(
            DeletionPlanGroup(
                id = "exact:same-hash",
                filter = OrganizerFilter.EXACT_DUPLICATE,
                suggestedKeepId = 1L,
                items = listOf(
                    DeletionPlanItem(1L, "content://media/1", 1L, 1L),
                    DeletionPlanItem(2L, "content://media/2", 2L, 2L),
                ),
                exactContentHash = "same-hash",
            ),
        ),
    )

    private fun DeletionPlanItem.toLiveMetadata() = LiveMediaMetadata(
        mediaId = mediaId,
        uri = uri,
        sizeBytes = sizeBytes,
        lastModifiedMillis = lastModifiedMillis,
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
            if (readCount == failAtRead) throw IOException("读取失败")
            return delegate.read(buffer, offset, length).also { afterRead(readCount) }
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }
}
