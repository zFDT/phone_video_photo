package com.frameyu.app.sampling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SerialAnalysisQueueTest {
    @Test
    fun `同一队列只允许一个大视频运行`() {
        val queue = SerialAnalysisQueue()
        queue.enqueue(1L)
        queue.enqueue(2L)

        assertEquals(1L, queue.takeNext())
        assertNull(queue.takeNext())

        queue.markFinished(1L)
        assertEquals(2L, queue.takeNext())
    }

    @Test
    fun `重复入队不会重复执行同一个任务`() {
        val queue = SerialAnalysisQueue()
        queue.enqueue(7L)
        queue.enqueue(7L)

        assertEquals(7L, queue.takeNext())
        queue.markFinished(7L)
        assertNull(queue.takeNext())
    }
}
