package com.frameyu.app.vision

import com.frameyu.app.analysis.FrameAnalysisContext
import com.frameyu.app.analysis.FrameAnalyzer
import com.frameyu.app.analysis.FrameSample
import com.frameyu.app.analysis.FrameScore
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

/** 视觉模型的统一输入；当前阶段只携带已完成规则分析的采样帧。 */
data class PipelineImage(
    val sample: FrameSample,
    val faceInput: FaceInput? = null,
    val thumbnailInput: ThumbnailInput? = null,
)

data class AnalysisPipelineInput(
    val sample: FrameSample,
    val faceInput: FaceInput? = null,
    val thumbnailInput: ThumbnailInput? = null,
)

/** 由管线持有的可替换视觉后端，Android 适配器在边界处实现此接口。 */
interface VisionAnalysisBackend {
    val configuredModels: Set<VisionModel>

    fun analyzeFace(input: PipelineImage): Task<List<FaceObservation>> =
        Tasks.forException(UnsupportedOperationException("未配置人脸模型"))

    fun analyzeLabels(input: PipelineImage): Task<List<LabelObservation>> =
        Tasks.forException(UnsupportedOperationException("未配置图像标签模型"))

    fun analyzeEmbedding(input: PipelineImage): Task<EmbeddingObservation> =
        Tasks.forException(UnsupportedOperationException("未配置图像向量模型"))
}

/** 将三个真实模型适配器接入统一管线的生产后端。 */
class MlKitVisionAnalysisBackend(
    private val faceAnalyzer: FaceAnalyzer? = null,
    private val labelAnalyzer: ImageLabelAnalyzer? = null,
    private val embeddingAnalyzer: ImageEmbeddingAnalyzer? = null,
) : VisionAnalysisBackend {
    override val configuredModels: Set<VisionModel> = buildSet {
        if (faceAnalyzer != null) add(VisionModel.FaceDetection)
        if (labelAnalyzer != null) add(VisionModel.ImageLabeling)
        if (embeddingAnalyzer != null) add(VisionModel.ImageEmbedding)
    }

    override fun analyzeFace(input: PipelineImage): Task<List<FaceObservation>> =
        input.faceInput?.let { faceAnalyzer?.analyze(it) }
            ?: Tasks.forException(IllegalArgumentException("管线缺少人脸输入"))

    override fun analyzeLabels(input: PipelineImage): Task<List<LabelObservation>> =
        input.thumbnailInput?.let { labelAnalyzer?.analyze(it) }
            ?: Tasks.forException(IllegalArgumentException("管线缺少缩略图输入"))

    override fun analyzeEmbedding(input: PipelineImage): Task<EmbeddingObservation> =
        input.thumbnailInput?.let { embeddingAnalyzer?.analyze(it) }
            ?: Tasks.forException(IllegalArgumentException("管线缺少缩略图输入"))
}

sealed interface ModelAnalysisState<out T> {
    data class Succeeded<T>(val value: T) : ModelAnalysisState<T>
    data class Failed(val message: String, val causeType: String) : ModelAnalysisState<Nothing>
    data class NotReady(val status: VisionModelStatus) : ModelAnalysisState<Nothing>
    data object NotConfigured : ModelAnalysisState<Nothing>
}

data class ModelAnalysisFailure(
    val model: VisionModel,
    val message: String,
    val causeType: String,
)

data class AnalysisPipelineResult(
    val frameScore: FrameScore,
    val face: ModelAnalysisState<List<FaceObservation>>,
    val labels: ModelAnalysisState<List<LabelObservation>>,
    val embedding: ModelAnalysisState<EmbeddingObservation>,
    val failures: List<ModelAnalysisFailure>,
)

/** 串联规则评分和可选视觉模型，单个模型失败不会丢失其他结果。 */
class AnalysisPipeline(
    private val ruleAnalyzer: FrameAnalyzer,
    private val visionBackend: VisionAnalysisBackend,
    private val modelStatuses: Map<VisionModel, VisionModelStatus> = emptyMap(),
) {
    fun analyze(input: AnalysisPipelineInput): Task<AnalysisPipelineResult> = try {
        val sample = input.sample
        val frameScore = ruleAnalyzer.analyze(sample, FrameAnalysisContext())
        val image = PipelineImage(sample, input.faceInput, input.thumbnailInput)
        val faceTask = analyzeModel(VisionModel.FaceDetection) { visionBackend.analyzeFace(image) }
        val labelsTask = analyzeModel(VisionModel.ImageLabeling) { visionBackend.analyzeLabels(image) }
        val embeddingTask = analyzeModel(VisionModel.ImageEmbedding) { visionBackend.analyzeEmbedding(image) }
        Tasks.whenAll(faceTask, labelsTask, embeddingTask).continueWith {
            val values: List<ModelAnalysisState<*>> = listOf(faceTask.result, labelsTask.result, embeddingTask.result)
            val face = values[0] as ModelAnalysisState<List<FaceObservation>>
            val labels = values[1] as ModelAnalysisState<List<LabelObservation>>
            val embedding = values[2] as ModelAnalysisState<EmbeddingObservation>
            val models = listOf(VisionModel.FaceDetection, VisionModel.ImageLabeling, VisionModel.ImageEmbedding)
            val failures = values.mapIndexedNotNull { index, state ->
                val failure = state as? ModelAnalysisState.Failed ?: return@mapIndexedNotNull null
                ModelAnalysisFailure(
                    model = models[index],
                    message = failure.message,
                    causeType = failure.causeType,
                )
            }
            AnalysisPipelineResult(frameScore, face, labels, embedding, failures)
        }
    } catch (error: Exception) {
        Tasks.forException(error)
    }

    private fun <T> analyzeModel(
        model: VisionModel,
        operation: () -> Task<T>,
    ): Task<ModelAnalysisState<T>> {
        if (model !in visionBackend.configuredModels) return Tasks.forResult(ModelAnalysisState.NotConfigured)
        val status = modelStatuses[model] ?: return Tasks.forResult(
            ModelAnalysisState.NotReady(VisionModelStatus.LoadFailed("模型状态未检查")),
        )
        if (status !is VisionModelStatus.BuiltInAvailable) return Tasks.forResult(ModelAnalysisState.NotReady(status))
        val task = try {
            operation()
        } catch (error: Exception) {
            return Tasks.forResult(failedState(error))
        }
        return task.continueWith { completed ->
            if (completed.isSuccessful) {
                ModelAnalysisState.Succeeded(completed.result)
            } else {
                failedState(completed.exception ?: IllegalStateException("模型分析任务失败"))
            }
        }
    }

    private fun <T> failedState(error: Exception): ModelAnalysisState<T> {
        val cause = error.cause ?: error
        val message = cause.message?.ifBlank { null } ?: "模型分析失败"
        return ModelAnalysisState.Failed(message, cause::class.simpleName ?: "Exception")
    }
}
