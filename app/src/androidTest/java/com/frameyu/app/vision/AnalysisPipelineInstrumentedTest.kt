package com.frameyu.app.vision

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frameyu.app.analysis.FrameAnalysisContext
import com.frameyu.app.analysis.FrameAnalyzer
import com.frameyu.app.analysis.FrameSample
import com.frameyu.app.analysis.FrameScore
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.TaskCompletionSource
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AnalysisPipelineInstrumentedTest {
    @Test
    fun delayedModelFailureDoesNotDiscardOtherResults() {
        val faceTask = TaskCompletionSource<List<FaceObservation>>()
        val pipeline = AnalysisPipeline(
            ruleAnalyzer = FakeFrameAnalyzer(),
            visionBackend = FakeVisionBackend(faceTask),
            modelStatuses = VisionModel.values().associateWith { VisionModelStatus.BuiltInAvailable },
        )

        val resultTask = pipeline.analyze(AnalysisPipelineInput(sample()))
        faceTask.setException(IllegalStateException("人脸模型失败"))
        val result = Tasks.await(
            resultTask,
            2,
            TimeUnit.SECONDS,
        )

        assertTrue(result.face is ModelAnalysisState.Failed)
        assertTrue(result.labels is ModelAnalysisState.Succeeded)
        assertTrue(result.embedding is ModelAnalysisState.Succeeded)
        assertEquals(VisionModel.FaceDetection, result.failures.single().model)
    }

    private fun sample() = FrameSample(0, 0L, 1, 1, intArrayOf(0xFF808080.toInt()))

    private class FakeFrameAnalyzer : FrameAnalyzer {
        override fun analyze(sample: FrameSample, context: FrameAnalysisContext) = FrameScore(
            sample.frameIndex, sample.timestampMillis, 80, 80, 100, 100, 0, 80,
        )
    }

    private class FakeVisionBackend(
        private val faceTask: TaskCompletionSource<List<FaceObservation>>,
    ) : VisionAnalysisBackend {
        override val configuredModels = VisionModel.values().toSet()

        override fun analyzeFace(input: PipelineImage) =
            faceTask.task

        override fun analyzeLabels(input: PipelineImage) = Tasks.forResult(emptyList<LabelObservation>())

        override fun analyzeEmbedding(input: PipelineImage) =
            Tasks.forResult(EmbeddingObservation(floatArrayOf(0.1f)))
    }
}
