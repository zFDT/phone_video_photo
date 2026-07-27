package com.frameyu.app.vision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class FrameModelAnalyzerConcurrencyTest {
    @Test
    fun `模型抛出取消异常时向上传播且不继续后续模型`() = runBlocking {
        val cancellation = CancellationException("停止分析")
        var embeddingRuns = 0
        var detectorRuns = 0

        try {
            executeFrameModels(
                detectionPolicy = ObjectDetectionPolicy(),
                highValue = true,
                classify = { throw cancellation },
                embed = {
                    embeddingRuns += 1
                    floatArrayOf(1f)
                },
                detect = {
                    detectorRuns += 1
                    emptyList()
                },
            )
            fail("应向上传播 CancellationException")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(0, embeddingRuns)
        assertEquals(0, detectorRuns)
    }

    @Test
    fun `等待串行锁的协程取消后不执行操作`() = runBlocking {
        val gate = CancellableSerialGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondRuns = 0

        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock {
                secondRuns += 1
            }
        }

        second.cancelAndJoin()
        releaseFirst.complete(Unit)
        first.join()

        assertEquals(0, secondRuns)
    }
}
