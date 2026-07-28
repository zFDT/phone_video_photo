package com.frameyu.app.sampling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CancellationPersistenceTest {
    @Test
    fun `取消终态保存后原样传播原取消`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        var persisted = false

        val actual = captureCancellation {
            persistCancellationAndRethrow(cancellation) {
                persisted = true
            }
        }

        assertEquals(true, persisted)
        assertSame(cancellation, actual)
    }

    @Test
    fun `取消终态保存失败作为suppressed且不替换原取消`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val persistenceFailure = IllegalStateException("room unavailable")

        val actual = captureCancellation {
            persistCancellationAndRethrow(cancellation) {
                throw persistenceFailure
            }
        }

        assertSame(cancellation, actual)
        assertEquals(listOf(persistenceFailure), actual.suppressedExceptions)
    }

    @Test
    fun `首次读取挂起点取消会从QUEUED收敛并原样传播`() = runBlocking {
        val cancellation = CancellationException("cancel before task read")
        var status = "QUEUED"

        val actual = captureCancellation {
            runTaskLifecycle(
                taskId = 7L,
                markCancelled = { taskId, _ ->
                    assertEquals(7L, taskId)
                    status = "CANCELLED"
                },
                markFailed = { _, _ -> throw AssertionError("取消不能标记失败") },
                closeResource = {},
            ) {
                suspendWith(cancellation)
            }
        }

        assertSame(cancellation, actual)
        assertEquals("CANCELLED", status)
    }

    @Test
    fun `更新RUNNING挂起点取消会从RUNNING收敛并原样传播`() = runBlocking {
        val cancellation = CancellationException("cancel while marking running")
        var status = "QUEUED"

        val actual = captureCancellation {
            runTaskLifecycle(
                taskId = 8L,
                markCancelled = { _, _ -> status = "CANCELLED" },
                markFailed = { _, _ -> throw AssertionError("取消不能标记失败") },
                closeResource = {},
            ) {
                status = "RUNNING"
                suspendWith(cancellation)
            }
        }

        assertSame(cancellation, actual)
        assertEquals("CANCELLED", status)
    }

    @Test
    fun `close失败不会覆盖取消primary并作为suppressed`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val closeFailure = IllegalStateException("close failed")

        val actual = captureCancellation {
            runTaskLifecycle(
                taskId = 9L,
                markCancelled = { _, _ -> },
                markFailed = { _, _ -> },
                closeResource = { throw closeFailure },
            ) {
                throw cancellation
            }
        }

        assertSame(cancellation, actual)
        assertEquals(listOf(closeFailure), actual.suppressedExceptions)
    }

    @Test
    fun `close失败不会覆盖普通Exception primary`() = runBlocking {
        val primary = IllegalArgumentException("analysis failed")
        val closeFailure = IllegalStateException("close failed")

        val actual = captureThrowable {
            runTaskLifecycle(
                taskId = 10L,
                markCancelled = { _, _ -> },
                markFailed = { _, error -> assertSame(primary, error) },
                closeResource = { throw closeFailure },
            ) {
                throw primary
            }
        }

        assertSame(primary, actual)
        assertEquals(listOf(closeFailure), actual.suppressedExceptions)
    }

    @Test
    fun `close失败不会覆盖Error primary`() = runBlocking {
        val primary = AssertionError("fatal")
        val closeFailure = IllegalStateException("close failed")

        val actual = captureThrowable {
            runTaskLifecycle(
                taskId = 11L,
                markCancelled = { _, _ -> },
                markFailed = { _, error -> assertSame(primary, error) },
                closeResource = { throw closeFailure },
            ) {
                throw primary
            }
        }

        assertSame(primary, actual)
        assertEquals(listOf(closeFailure), actual.suppressedExceptions)
    }

    @Test
    fun `没有primary时close失败正常传播`() = runBlocking {
        val closeFailure = IllegalStateException("close failed")

        val actual = captureThrowable {
            runTaskLifecycle(
                taskId = 12L,
                markCancelled = { _, _ -> },
                markFailed = { _, _ -> },
                closeResource = { throw closeFailure },
            ) {
                "completed"
            }
        }

        assertSame(closeFailure, actual)
    }

    private suspend fun captureCancellation(block: suspend () -> Unit): CancellationException =
        try {
            block()
            throw AssertionError("预期传播 CancellationException")
        } catch (cancelled: CancellationException) {
            cancelled
        }

    private suspend fun captureThrowable(block: suspend () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("预期传播 Throwable")
        } catch (error: Throwable) {
            error
        }

    private suspend fun suspendWith(error: Throwable): Nothing =
        suspendCoroutine { continuation ->
            continuation.resumeWithException(error)
        }
}
