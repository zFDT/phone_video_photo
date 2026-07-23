package com.frameyu.app.vision

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** 图像标签分析抽象，输入固定为受约束的缩略图。 */
interface ImageLabelAnalyzer : Closeable {
    fun analyze(input: ThumbnailInput): Task<List<LabelObservation>>
}

/** ML Kit Image Labeling 适配器；失败会以带模型信息的 Task 异常返回。 */
class MlKitImageLabelAnalyzer(
    options: ImageLabelerOptions = defaultOptions(),
) : ImageLabelAnalyzer {
    private val labeler: ImageLabeler = ImageLabeling.getClient(options)
    private val closed = AtomicBoolean(false)

    override fun analyze(input: ThumbnailInput): Task<List<LabelObservation>> {
        check(!closed.get()) { "图像标签分析器已经释放" }
        return labeler.process(InputImage.fromBitmap(input.bitmap, 0)).continueWithTask { task ->
            if (!task.isSuccessful) {
                return@continueWithTask Tasks.forException(
                    VisionAnalysisException(
                        model = VisionModel.ImageLabeling,
                        operation = "图像标签分析",
                        cause = task.exception,
                    ),
                )
            }
            Tasks.forResult(task.result.map(::mapMlKitLabel))
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            labeler.close()
        }
    }

    companion object {
        fun defaultOptions(): ImageLabelerOptions = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build()
    }
}

class VisionAnalysisException(
    val model: VisionModel,
    operation: String,
    cause: Throwable?,
) : IllegalStateException(
    "${operation}失败（模型：$model）：${cause?.message?.ifBlank { null } ?: "未知模型错误"}",
    cause,
)

private fun mapMlKitLabel(label: ImageLabel): LabelObservation = LabelObservationMapper.map(
    RawLabelObservation(
        label = label.text,
        confidence = label.confidence,
        index = label.index,
    ),
)
