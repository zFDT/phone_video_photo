package com.frameyu.app.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/** 图像分类的稳定结果，不向上层暴露 MediaPipe 类型。 */
data class LabelScore(
    val label: String,
    val score: Float,
    val index: Int = -1,
) {
    init {
        require(label.isNotBlank()) { "分类标签不能为空" }
        require(score.isFinite() && score in 0f..1f) { "分类置信度必须在 0 到 1 之间" }
        require(index >= -1) { "分类索引不能小于 -1" }
    }
}

interface ImageClassifierAnalyzer : Closeable {
    /** 同步执行分类，调用方必须在后台线程调用。 */
    fun classify(bitmap: Bitmap): List<LabelScore>
}

/** MediaPipe Image Classifier 的同步适配器。 */
class MediaPipeImageClassifierAnalyzer internal constructor(
    private val modelAssetPath: String,
    classifierFactory: () -> ImageClassifier,
) : ImageClassifierAnalyzer {
    constructor(
        context: Context,
        modelAssetPath: String,
    ) : this(
        modelAssetPath = modelAssetPath,
        classifierFactory = {
            ImageClassifier.createFromOptions(
                context,
                ImageClassifier.ImageClassifierOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAssetPath).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(MAX_RESULTS)
                    .build(),
            )
        },
    )

    private val closed = AtomicBoolean(false)
    private val inferenceLock = Any()
    private val initialization: Result<ImageClassifier> = initializeMediaPipeModel(classifierFactory)
    private val classifier = initialization.getOrNull()

    override fun classify(bitmap: Bitmap): List<LabelScore> = synchronized(inferenceLock) {
        check(!closed.get()) { "图像分类器已经释放" }
        require(!bitmap.isRecycled) { "分类输入 Bitmap 已被回收" }
        val activeClassifier = initialization.getOrElse { error ->
            throw IllegalStateException(
                "图像分类器初始化失败（模型路径：$modelAssetPath）：${error.modelMessage()}",
                error,
            )
        }

        try {
            val image = BitmapImageBuilder(bitmap).build()
            try {
                activeClassifier.classify(image)
                    .classificationResult()
                    .classifications()
                    .flatMap { it.categories() }
                    .mapNotNull { category ->
                        val label = category.categoryName()
                            .ifBlank { category.displayName() }
                            .trim()
                        if (label.isEmpty()) {
                            null
                        } else {
                            LabelScore(label, category.score(), category.index())
                        }
                    }
                    .sortedByDescending(LabelScore::score)
                    .take(MAX_RESULTS)
            } finally {
                image.close()
            }
        } catch (error: Exception) {
            throw IllegalStateException("图像分类失败：${error.modelMessage()}", error)
        }
    }

    override fun close() {
        synchronized(inferenceLock) {
            if (closed.compareAndSet(false, true)) classifier?.close()
        }
    }

    private companion object {
        const val MAX_RESULTS = 5
    }
}

internal fun Throwable.modelMessage(): String = message?.takeIf(String::isNotBlank) ?: "未知模型错误"

/** 只缓存可恢复初始化异常；取消和 JVM fatal Error 必须由调用方立即处理。 */
internal fun <T> initializeMediaPipeModel(factory: () -> T): Result<T> = try {
    Result.success(factory())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
