package com.frameyu.app.vision

import android.graphics.Bitmap
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 单帧模型结果；所有集合与向量在边界处复制，避免调用方修改分析结果。 */
class FrameModelResult(
    labels: List<LabelScore>,
    embedding: FloatArray?,
    detections: List<DetectionObservation>,
    failures: List<String>,
) {
    val labels: List<LabelScore> = labels.toList()
    private val embeddingValues: FloatArray? = embedding?.copyOf()
    val embedding: FloatArray? get() = embeddingValues?.copyOf()
    val detections: List<DetectionObservation> = detections.toList()
    val failures: List<String> = failures.toList()

    init {
        require(embeddingValues == null || embeddingValues.isNotEmpty()) { "图像向量不能为空" }
        require(embeddingValues == null || embeddingValues.all(Float::isFinite)) {
            "图像向量必须只包含有限数值"
        }
    }
}

interface FrameModelAnalyzer {
    suspend fun analyze(
        bitmap: Bitmap,
        timestampMillis: Long,
        highValue: Boolean,
    ): FrameModelResult
}

fun interface FrameModelResultStore {
    suspend fun saveFrameResult(
        mediaId: Long,
        timestampMillis: Long,
        result: FrameModelResult,
    )
}

/** 在后台串行协调分类、向量和按需目标检测，单模型失败不会丢弃其他结果。 */
class DefaultFrameModelAnalyzer(
    private val classifier: ImageClassifierAnalyzer,
    private val embedder: ImageEmbeddingAnalyzer,
    private val detector: ObjectDetectorAnalyzer,
    private val detectionPolicy: ObjectDetectionPolicy = ObjectDetectionPolicy(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : FrameModelAnalyzer, Closeable {
    private val closed = AtomicBoolean(false)
    private val serialGate = CancellableSerialGate()

    override suspend fun analyze(
        bitmap: Bitmap,
        timestampMillis: Long,
        highValue: Boolean,
    ): FrameModelResult {
        require(timestampMillis >= 0L) { "帧时间戳不能为负数" }
        require(!bitmap.isRecycled) { "帧 Bitmap 已被回收" }

        return withContext(dispatcher) {
            serialGate.withLock {
                check(!closed.get()) { "统一帧分析器已经释放" }
                analyzeSerially(bitmap, highValue)
            }
        }
    }

    private fun analyzeSerially(bitmap: Bitmap, highValue: Boolean): FrameModelResult {
        return executeFrameModels(
            detectionPolicy = detectionPolicy,
            highValue = highValue,
            classify = { classifier.classify(bitmap) },
            embed = {
                embedder.analyzeSynchronously(ThumbnailInput.fromBitmap(bitmap)).values
            },
            detect = { detector.detect(bitmap) },
        )
    }

    override fun close() {
        serialGate.withLockBlocking {
            if (!closed.compareAndSet(false, true)) return@withLockBlocking
            val closeFailures = listOf(
                runCatching { classifier.close() },
                runCatching { embedder.close() },
                runCatching { detector.close() },
            ).mapNotNull(Result<Unit>::exceptionOrNull)
            if (closeFailures.isNotEmpty()) {
                throw IllegalStateException(
                    "统一帧分析器释放模型失败：${closeFailures.first().modelMessage()}",
                    closeFailures.first(),
                )
            }
        }
    }
}

/** Mutex 获取可取消；同步 close 通过同一把锁等待正在执行的推理结束。 */
internal class CancellableSerialGate {
    private val mutex = Mutex()

    suspend fun <T> withLock(operation: suspend () -> T): T =
        mutex.withLock { operation() }

    fun <T> withLockBlocking(operation: () -> T): T =
        runBlocking { mutex.withLock { operation() } }
}

/** 不依赖 Android Bitmap 的模型执行核心，便于验证失败隔离和取消传播。 */
internal fun executeFrameModels(
    detectionPolicy: ObjectDetectionPolicy,
    highValue: Boolean,
    classify: () -> List<LabelScore>,
    embed: () -> FloatArray?,
    detect: () -> List<DetectionObservation>,
): FrameModelResult {
    val failures = mutableListOf<String>()
    val labels = runModel(
        failurePrefix = "图像分类失败",
        failures = failures,
        fallback = emptyList(),
        operation = classify,
    )
    val embedding = runModel<FloatArray?>(
        failurePrefix = "图像向量分析失败",
        failures = failures,
        fallback = null,
        operation = embed,
    )
    val detections = if (detectionPolicy.shouldDetect(labels, highValue)) {
        runModel(
            failurePrefix = "目标检测失败",
            failures = failures,
            fallback = emptyList(),
            operation = detect,
        )
    } else {
        emptyList()
    }

    return FrameModelResult(labels, embedding, detections, failures)
}

private inline fun <T> runModel(
    failurePrefix: String,
    failures: MutableList<String>,
    fallback: T,
    operation: () -> T,
): T = try {
    operation()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    failures += "$failurePrefix：${error.modelMessage()}"
    fallback
}
