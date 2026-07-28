package com.frameyu.app.vision

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaPipeAnalyzerInitializationTest {
    @Test
    fun `分类器初始化Error原样传播`() {
        val fatal = AssertionError("classifier fatal")

        val actual = assertThrows(AssertionError::class.java) {
            MediaPipeImageClassifierAnalyzer("classifier.tflite") { throw fatal }
        }

        assertSame(fatal, actual)
    }

    @Test
    fun `向量模型初始化Error原样传播`() {
        val fatal = LinkageError("embedder fatal")

        val actual = assertThrows(LinkageError::class.java) {
            MediaPipeImageEmbeddingAnalyzer("embedder.tflite") { throw fatal }
        }

        assertSame(fatal, actual)
    }

    @Test
    fun `检测器初始化Error原样传播`() {
        val fatal = OutOfMemoryError("detector fatal")

        val actual = assertThrows(OutOfMemoryError::class.java) {
            MediaPipeObjectDetectorAnalyzer("detector.tflite") { throw fatal }
        }

        assertSame(fatal, actual)
    }

    @Test
    fun `三个分析器初始化取消都原样传播`() {
        val classifierCancellation = CancellationException("classifier cancelled")
        assertSame(
            classifierCancellation,
            assertThrows(CancellationException::class.java) {
                MediaPipeImageClassifierAnalyzer("classifier.tflite") {
                    throw classifierCancellation
                }
            },
        )

        val embedderCancellation = CancellationException("embedder cancelled")
        assertSame(
            embedderCancellation,
            assertThrows(CancellationException::class.java) {
                MediaPipeImageEmbeddingAnalyzer("embedder.tflite") {
                    throw embedderCancellation
                }
            },
        )

        val detectorCancellation = CancellationException("detector cancelled")
        assertSame(
            detectorCancellation,
            assertThrows(CancellationException::class.java) {
                MediaPipeObjectDetectorAnalyzer("detector.tflite") {
                    throw detectorCancellation
                }
            },
        )
    }
}
