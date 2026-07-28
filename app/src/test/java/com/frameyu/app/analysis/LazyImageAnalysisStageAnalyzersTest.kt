package com.frameyu.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LazyImageAnalysisStageAnalyzersTest {
    @Test
    fun `三个阶段 provider 独立延迟创建并复用单实例`() {
        val classifier = Any()
        val embedder = Any()
        val detector = Any()
        var classifierCalls = 0
        var embedderCalls = 0
        var detectorCalls = 0
        val analyzers = LazyImageAnalysisStageAnalyzers(
            classifierProvider = {
                classifierCalls += 1
                classifier
            },
            embedderProvider = {
                embedderCalls += 1
                embedder
            },
            detectorProvider = {
                detectorCalls += 1
                detector
            },
        )

        assertEquals(listOf(0, 0, 0), listOf(classifierCalls, embedderCalls, detectorCalls))
        assertSame(classifier, analyzers.classifier())
        assertSame(classifier, analyzers.classifier())
        assertEquals(listOf(1, 0, 0), listOf(classifierCalls, embedderCalls, detectorCalls))
        assertSame(embedder, analyzers.embedder())
        assertSame(embedder, analyzers.embedder())
        assertEquals(listOf(1, 1, 0), listOf(classifierCalls, embedderCalls, detectorCalls))
        assertSame(detector, analyzers.detector())
        assertSame(detector, analyzers.detector())
        assertEquals(listOf(1, 1, 1), listOf(classifierCalls, embedderCalls, detectorCalls))
    }
}
