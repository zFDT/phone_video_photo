package com.frameyu.app.sampling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

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

    private suspend fun captureCancellation(block: suspend () -> Unit): CancellationException =
        try {
            block()
            throw AssertionError("预期传播 CancellationException")
        } catch (cancelled: CancellationException) {
            cancelled
        }
}
