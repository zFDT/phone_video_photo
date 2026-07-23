package com.frameyu.app.vision

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import android.content.Context
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** 向量分析抽象，后续由 MediaPipe Image Embedder 实现。 */
interface ImageEmbeddingAnalyzer : Closeable {
    fun analyze(input: ThumbnailInput): Task<EmbeddingObservation>
}

/** MediaPipe Image Embedder 的同步适配器，调用方应在后台线程执行 analyze。 */
class MediaPipeImageEmbeddingAnalyzer(
    context: Context,
    private val modelAssetPath: String,
) : ImageEmbeddingAnalyzer {
    private val closed = AtomicBoolean(false)
    private val initialization: Result<ImageEmbedder> = runCatching {
        ImageEmbedder.createFromOptions(
            context,
            ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAssetPath).build())
                .setL2Normalize(true)
                .setQuantize(false)
                .build(),
        )
    }
    private val embedder: ImageEmbedder? = initialization.getOrNull()
    private val initializationFailure: Throwable? = initialization.exceptionOrNull()

    override fun analyze(input: ThumbnailInput): Task<EmbeddingObservation> {
        check(!closed.get()) { "图像向量分析器已经释放" }
        initializationFailure?.let {
            return Tasks.forException(
                VisionAnalysisException(VisionModel.ImageEmbedding, "图像向量分析（模型路径：$modelAssetPath）", it),
            )
        }
        return try {
            val image = BitmapImageBuilder(input.bitmap).build()
            try {
                val result = requireNotNull(embedder).embed(image)
                val embedding = result.embeddingResult().embeddings().firstOrNull()
                    ?: error("MediaPipe 未返回图像向量")
                Tasks.forResult(EmbeddingObservationMapper.map(embedding.floatEmbedding()))
            } finally {
                image.close()
            }
        } catch (error: Exception) {
            Tasks.forException(VisionAnalysisException(VisionModel.ImageEmbedding, "图像向量分析", error))
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) embedder?.close()
    }
}
