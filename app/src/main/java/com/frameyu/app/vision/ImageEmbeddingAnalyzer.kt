package com.frameyu.app.vision

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** 向量分析抽象，后续由 MediaPipe Image Embedder 实现。 */
interface ImageEmbeddingAnalyzer : Closeable {
    fun analyze(input: ThumbnailInput): Task<EmbeddingObservation>

    /** 同步执行向量推理，供已经切换到后台线程的统一分析器使用。 */
    fun analyzeSynchronously(input: ThumbnailInput): EmbeddingObservation =
        Tasks.await(analyze(input))
}

/** MediaPipe Image Embedder 的同步适配器，调用方应在后台线程执行 analyze。 */
class MediaPipeImageEmbeddingAnalyzer internal constructor(
    private val modelAssetPath: String,
    embedderFactory: () -> ImageEmbedder,
) : ImageEmbeddingAnalyzer {
    constructor(
        context: Context,
        modelAssetPath: String,
    ) : this(
        modelAssetPath = modelAssetPath,
        embedderFactory = {
            ImageEmbedder.createFromOptions(
                context,
                ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(modelAssetPath).build())
                    .setL2Normalize(true)
                    .setQuantize(false)
                    .build(),
            )
        },
    )

    private val closed = AtomicBoolean(false)
    private val inferenceLock = Any()
    private val initialization: Result<ImageEmbedder> = initializeMediaPipeModel(embedderFactory)
    private val embedder: ImageEmbedder? = initialization.getOrNull()
    private val initializationFailure: Throwable? = initialization.exceptionOrNull()

    override fun analyze(input: ThumbnailInput): Task<EmbeddingObservation> {
        check(!closed.get()) { "图像向量分析器已经释放" }
        return try {
            Tasks.forResult(analyzeSynchronously(input))
        } catch (error: Exception) {
            Tasks.forException(
                if (error is VisionAnalysisException) {
                    error
                } else {
                    VisionAnalysisException(VisionModel.ImageEmbedding, "图像向量分析", error)
                },
            )
        }
    }

    override fun analyzeSynchronously(input: ThumbnailInput): EmbeddingObservation =
        synchronized(inferenceLock) {
            check(!closed.get()) { "图像向量分析器已经释放" }
            initializationFailure?.let {
                throw VisionAnalysisException(
                    VisionModel.ImageEmbedding,
                    "图像向量分析（模型路径：$modelAssetPath）",
                    it,
                )
            }
            try {
                withMediaPipeBitmapImage(input.bitmap) { image ->
                    val result = requireNotNull(embedder).embed(image)
                    val embedding = result.embeddingResult().embeddings().firstOrNull()
                        ?: error("MediaPipe 未返回图像向量")
                    EmbeddingObservationMapper.map(embedding.floatEmbedding())
                }
            } catch (error: Exception) {
                if (error is VisionAnalysisException) throw error
                throw VisionAnalysisException(VisionModel.ImageEmbedding, "图像向量分析", error)
            }
        }

    override fun close() {
        synchronized(inferenceLock) {
            if (closed.compareAndSet(false, true)) embedder?.close()
        }
    }
}
