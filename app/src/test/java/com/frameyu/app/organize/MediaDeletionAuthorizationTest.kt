package com.frameyu.app.organize

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeletionAuthorizationTest {
    @Test
    fun `Android 11 回收站授权取消时不移除 Room`() = runBlocking {
        val synced = mutableListOf<List<String>>()

        val result = executeTrashRequestDeletion(
            uris = listOf("one", "two"),
            requestTrash = { false },
            removeSucceededFromIndex = { synced += it },
        )

        assertTrue(result.succeededUris.isEmpty())
        assertEquals(listOf("one", "two"), result.failures.map(MediaDeletionFailure::uri))
        assertTrue(synced.isEmpty())
    }

    @Test
    fun `Android 11 仅在系统授权成功后同步全部 URI`() = runBlocking {
        val events = mutableListOf<String>()

        val result = executeTrashRequestDeletion(
            uris = listOf("one", "two"),
            requestTrash = {
                events += "authorized"
                true
            },
            removeSucceededFromIndex = {
                events += "synced:${it.joinToString()}"
            },
        )

        assertEquals(listOf("one", "two"), result.succeededUris)
        assertTrue(result.failures.isEmpty())
        assertEquals(listOf("authorized", "synced:one, two"), events)
    }

    @Test
    fun `Android 10 授权成功后只重试当前 URI 并同步成功项`() = runBlocking {
        val attempts = mutableMapOf<String, Int>()
        val synced = mutableListOf<List<String>>()
        val settled = mutableListOf<String>()

        val result = executeRecoverableMediaDeletion(
            uris = listOf("protected", "plain"),
            deleteOne = { uri ->
                val attempt = attempts.getOrDefault(uri, 0) + 1
                attempts[uri] = attempt
                when {
                    uri == "protected" && attempt == 1 -> MediaDeleteAttempt.RequiresAuthorization { true }
                    else -> MediaDeleteAttempt.Deleted
                }
            },
            removeSucceededFromIndex = { synced += it },
            onAuthorizedRetrySettled = { settled += it },
        )

        assertEquals(mapOf("protected" to 2, "plain" to 1), attempts)
        assertEquals(listOf("protected", "plain"), result.succeededUris)
        assertEquals(listOf(listOf("protected"), listOf("plain")), synced)
        assertEquals(listOf("protected"), settled)
    }

    @Test
    fun `Android 10 每个授权 URI 对账完成后才进入下一授权`() = runBlocking {
        val attempts = mutableMapOf<String, Int>()
        val events = mutableListOf<String>()

        val result = executeRecoverableMediaDeletion(
            uris = listOf("first", "second"),
            deleteOne = { uri ->
                val attempt = attempts.getOrDefault(uri, 0) + 1
                attempts[uri] = attempt
                if (attempt == 1) {
                    MediaDeleteAttempt.RequiresAuthorization {
                        events += "authorized:$uri"
                        true
                    }
                } else {
                    MediaDeleteAttempt.Deleted
                }
            },
            removeSucceededFromIndex = { uris ->
                events += "synced:${uris.single()}"
            },
            onAuthorizedRetrySettled = { uri ->
                events += "settled:$uri"
            },
        )

        assertEquals(listOf("first", "second"), result.succeededUris)
        assertEquals(
            listOf(
                "authorized:first",
                "synced:first",
                "settled:first",
                "authorized:second",
                "synced:second",
                "settled:second",
            ),
            events,
        )
    }

    @Test
    fun `Android 10 用户拒绝授权时不重试也不同步该 URI`() = runBlocking {
        var attempts = 0
        val synced = mutableListOf<List<String>>()

        val result = executeRecoverableMediaDeletion(
            uris = listOf("protected"),
            deleteOne = {
                attempts += 1
                MediaDeleteAttempt.RequiresAuthorization { false }
            },
            removeSucceededFromIndex = { synced += it },
        )

        assertEquals(1, attempts)
        assertTrue(result.succeededUris.isEmpty())
        assertEquals(listOf("protected"), result.failures.map(MediaDeletionFailure::uri))
        assertTrue(synced.isEmpty())
    }

    @Test
    fun `Android 10 单项删除异常不会阻止后续 URI 且仍同步成功项`() = runBlocking {
        val synced = mutableListOf<List<String>>()

        val result = executeRecoverableMediaDeletion(
            uris = listOf("failed", "success"),
            deleteOne = { uri ->
                if (uri == "failed") error("provider error")
                MediaDeleteAttempt.Deleted
            },
            removeSucceededFromIndex = { synced += it },
        )

        assertEquals(listOf("success"), result.succeededUris)
        assertEquals(listOf("failed"), result.failures.map(MediaDeletionFailure::uri))
        assertEquals(listOf(listOf("success")), synced)
    }

    @Test
    fun `授权请求串行且取消会释放挂起请求门`() = runBlocking {
        val gate = MediaAuthorizationGate()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var concurrentRequests = 0
        var maxConcurrentRequests = 0

        val first = async {
            gate.run {
                concurrentRequests += 1
                maxConcurrentRequests = maxOf(maxConcurrentRequests, concurrentRequests)
                firstStarted.complete(Unit)
                try {
                    releaseFirst.await()
                    true
                } finally {
                    concurrentRequests -= 1
                }
            }
        }
        firstStarted.await()
        val second = async {
            gate.run {
                concurrentRequests += 1
                maxConcurrentRequests = maxOf(maxConcurrentRequests, concurrentRequests)
                concurrentRequests -= 1
                true
            }
        }
        yield()

        assertFalse(second.isCompleted)
        first.cancel(CancellationException("页面退出"))
        assertTrue(second.await())
        assertEquals(1, maxConcurrentRequests)
    }

    @Test
    fun `回收站授权等待取消会原样传播且不做 Room 同步`() = runBlocking {
        val cancellation = CancellationException("页面退出")
        var synced = false

        val thrown = runCatching {
            executeTrashRequestDeletion(
                uris = listOf("one"),
                requestTrash = { throw cancellation },
                removeSucceededFromIndex = { synced = true },
            )
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertFalse(synced)
    }
}
