package com.frameyu.app.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** 目标检测的稳定结果，边界框坐标相对输入图像归一化。 */
data class DetectionObservation(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(label.isNotBlank()) { "检测标签不能为空" }
        require(score.isFinite() && score in 0f..1f) { "检测置信度必须在 0 到 1 之间" }
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f }) {
            "检测框坐标必须是 0 到 1 之间的有限数"
        }
        require(left < right) { "检测框宽度必须大于零" }
        require(top < bottom) { "检测框高度必须大于零" }
    }
}

interface ObjectDetectorAnalyzer : Closeable {
    /** 同步执行检测，调用方必须在后台线程调用。 */
    fun detect(bitmap: Bitmap): List<DetectionObservation>
}

/** MediaPipe Object Detector 的同步适配器。 */
class MediaPipeObjectDetectorAnalyzer internal constructor(
    private val modelAssetPath: String,
    detectorFactory: () -> ObjectDetector,
) : ObjectDetectorAnalyzer {
    constructor(
        context: Context,
        modelAssetPath: String,
    ) : this(
        modelAssetPath = modelAssetPath,
        detectorFactory = {
            ObjectDetector.createFromOptions(
                context,
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAssetPath).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(MAX_RESULTS)
                    .setScoreThreshold(SCORE_THRESHOLD)
                    .build(),
            )
        },
    )

    private val closed = AtomicBoolean(false)
    private val inferenceLock = Any()
    private val initialization: Result<ObjectDetector> = initializeMediaPipeModel(detectorFactory)
    private val detector = initialization.getOrNull()

    override fun detect(bitmap: Bitmap): List<DetectionObservation> = synchronized(inferenceLock) {
        check(!closed.get()) { "目标检测器已经释放" }
        require(!bitmap.isRecycled) { "检测输入 Bitmap 已被回收" }
        val activeDetector = initialization.getOrElse { error ->
            throw IllegalStateException(
                "目标检测器初始化失败（模型路径：$modelAssetPath）：${error.modelMessage()}",
                error,
            )
        }

        try {
            val image = BitmapImageBuilder(bitmap).build()
            try {
                activeDetector.detect(image).detections().mapNotNull { detection ->
                    detection.toObservation(bitmap.width, bitmap.height)
                }
            } finally {
                image.close()
            }
        } catch (error: Exception) {
            throw IllegalStateException("目标检测失败：${error.modelMessage()}", error)
        }
    }

    override fun close() {
        synchronized(inferenceLock) {
            if (closed.compareAndSet(false, true)) detector?.close()
        }
    }

    private companion object {
        const val MAX_RESULTS = 10
        const val SCORE_THRESHOLD = 0.30f
    }
}

private fun Detection.toObservation(bitmapWidth: Int, bitmapHeight: Int): DetectionObservation? {
    val category = categories().maxByOrNull { it.score() } ?: return null
    val label = category.categoryName().ifBlank { category.displayName() }.trim()
    if (label.isEmpty() || !category.score().isFinite() || category.score() !in 0f..1f) return null

    val box = boundingBox()
    if (!listOf(box.left, box.top, box.right, box.bottom).all(Float::isFinite)) return null
    val width = bitmapWidth.toFloat()
    val height = bitmapHeight.toFloat()
    val left = box.left.coerceIn(0f, width) / width
    val top = box.top.coerceIn(0f, height) / height
    val right = box.right.coerceIn(0f, width) / width
    val bottom = box.bottom.coerceIn(0f, height) / height
    if (left >= right || top >= bottom) return null

    return DetectionObservation(label, category.score(), left, top, right, bottom)
}
