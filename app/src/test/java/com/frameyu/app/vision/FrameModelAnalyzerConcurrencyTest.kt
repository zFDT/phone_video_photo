package com.frameyu.app.vision

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun `分析持锁期间 close 不提前关闭模型且释放后正常结束`() = runBlocking {
        val gate = CancellableSerialGate()
        val analysisEntered = CompletableDeferred<Unit>()
        val releaseAnalysis = CompletableDeferred<Unit>()
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val classifierCloseCount = AtomicInteger()
        val embedderCloseCount = AtomicInteger()
        val detectorCloseCount = AtomicInteger()

        val analysis = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.withLock {
                analysisEntered.complete(Unit)
                releaseAnalysis.await()
            }
        }
        analysisEntered.await()
        val closeThread = Thread {
            closeStarted.countDown()
            try {
                gate.withLockBlocking {
                    classifierCloseCount.incrementAndGet()
                    embedderCloseCount.incrementAndGet()
                    detectorCloseCount.incrementAndGet()
                }
            } catch (error: Throwable) {
                closeFailure.set(error)
            } finally {
                closeFinished.countDown()
            }
        }
        closeThread.start()

        try {
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
            assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, classifierCloseCount.get())
            assertEquals(0, embedderCloseCount.get())
            assertEquals(0, detectorCloseCount.get())
        } finally {
            releaseAnalysis.complete(Unit)
            analysis.join()
        }

        assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
        closeThread.join(2_000)
        assertFalse(closeThread.isAlive)
        assertNull(closeFailure.get())
        assertEquals(1, classifierCloseCount.get())
        assertEquals(1, embedderCloseCount.get())
        assertEquals(1, detectorCloseCount.get())
    }
}
