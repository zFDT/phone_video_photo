package com.frameyu.app.sampling

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 视频帧的所有权。采样器不会自动回收 Bitmap，回调方使用完后负责释放或交给后续处理。 */
enum class FrameOwnership {
    CALLER_OWNS,
}

/** 解码器输出的单帧，requestedTimestampMillis 是计划时间，actualTimestampMillis 是实际解码时间。 */
data class DecodedFrame<Frame>(
    val requestedTimestampMillis: Long,
    val actualTimestampMillis: Long,
    val frame: Frame,
    val ownership: FrameOwnership = FrameOwnership.CALLER_OWNS,
) {
    init {
        require(requestedTimestampMillis >= 0L) { "请求时间不能为负数" }
        require(actualTimestampMillis >= 0L) { "实际时间不能为负数" }
    }
}

fun interface FrameDecoder<Frame> {
    fun decode(timestampMillis: Long): DecodedFrame<Frame>?
}

fun interface FrameSamplingCallback<Frame> {
    fun onFrame(frame: DecodedFrame<Frame>)
}

enum class FrameSamplingStatus {
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class FrameSamplingResult<Frame>(
    val status: FrameSamplingStatus,
    val sampledTimestampsMillis: List<Long>,
    val error: Throwable? = null,
) {
    init {
        require(sampledTimestampsMillis.all { it >= 0L }) { "采样时间不能为负数" }
        require((status == FrameSamplingStatus.FAILED) == (error != null)) {
            "失败状态必须包含异常，其他状态不能包含异常"
        }
    }
}

/** 可在线程间安全取消的采样句柄。取消不会回收已经交给回调方的 Bitmap。 */
class FrameSamplingHandle {
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }
}

/** 纯 Kotlin 的采样调度核心，负责去重、取消和错误状态，不依赖 Android 解码器。 */
class FrameSamplingCoordinator<Frame> {
    fun sample(
        plan: SamplingPlan,
        decoder: FrameDecoder<Frame>,
        callback: FrameSamplingCallback<Frame>,
        handle: FrameSamplingHandle = FrameSamplingHandle(),
    ): FrameSamplingResult<Frame> {
        val sampledTimestamps = mutableListOf<Long>()
        val timestamps = plan.sampleTimestamps().distinct()

        if (handle.isCancelled) {
            return FrameSamplingResult(FrameSamplingStatus.CANCELLED, sampledTimestamps)
        }

        for (timestamp in timestamps) {
            if (handle.isCancelled) {
                return FrameSamplingResult(FrameSamplingStatus.CANCELLED, sampledTimestamps)
            }

            try {
                val decoded = decoder.decode(timestamp)
                    ?: throw FrameSamplingException("无法解码时间 ${timestamp}ms 的视频帧")
                callback.onFrame(decoded)
                sampledTimestamps += timestamp
            } catch (error: Throwable) {
                return FrameSamplingResult(FrameSamplingStatus.FAILED, sampledTimestamps, error)
            }
        }

        return if (handle.isCancelled) {
            FrameSamplingResult(FrameSamplingStatus.CANCELLED, sampledTimestamps)
        } else {
            FrameSamplingResult(FrameSamplingStatus.COMPLETED, sampledTimestamps)
        }
    }
}

class FrameSamplingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 支持 ContentResolver Uri 和带本地 Uri 的 Media3 MediaItem。 */
sealed interface VideoMediaInput {
    val uri: Uri

    data class UriInput(override val uri: Uri) : VideoMediaInput

    data class MediaItemInput(val mediaItem: MediaItem) : VideoMediaInput {
        override val uri: Uri = requireNotNull(mediaItem.localConfiguration?.uri) {
            "MediaItem 必须包含可访问的本地 Uri"
        }
    }
}

interface VideoFrameSampler {
    /** 异步执行采样；回调收到的 Bitmap 由回调方负责生命周期管理。 */
    fun sample(
        input: VideoMediaInput,
        plan: SamplingPlan,
        callback: FrameSamplingCallback<Bitmap>,
        onComplete: (FrameSamplingResult<Bitmap>) -> Unit,
    ): FrameSamplingHandle
}

/**
 * 基于 MediaMetadataRetriever 的 Media3 输入适配器。
 * MediaItem 负责统一输入描述，ContentResolver Uri 由系统媒体 Provider 读取。
 */
class Media3VideoFrameSampler(
    private val context: Context,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
) : VideoFrameSampler, AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun sample(
        input: VideoMediaInput,
        plan: SamplingPlan,
        callback: FrameSamplingCallback<Bitmap>,
        onComplete: (FrameSamplingResult<Bitmap>) -> Unit,
    ): FrameSamplingHandle {
        check(!closed.get()) { "视频帧采样器已关闭" }
        val handle = FrameSamplingHandle()
        executor.execute {
            val result = if (handle.isCancelled) {
                FrameSamplingResult<Bitmap>(FrameSamplingStatus.CANCELLED, emptyList())
            } else {
                sampleFromRetriever(input, plan, callback, handle)
            }
            onComplete(result)
        }
        return handle
    }

    private fun sampleFromRetriever(
        input: VideoMediaInput,
        plan: SamplingPlan,
        callback: FrameSamplingCallback<Bitmap>,
        handle: FrameSamplingHandle,
    ): FrameSamplingResult<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, input.uri)
            FrameSamplingCoordinator<Bitmap>().sample(
                plan = plan,
                decoder = FrameDecoder { timestampMillis ->
                    val bitmap = retriever.getFrameAtTime(
                        timestampMillis * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    ) ?: throw FrameSamplingException("无法读取时间 ${timestampMillis}ms 的视频帧")
                    DecodedFrame(timestampMillis, timestampMillis, bitmap)
                },
                callback = callback,
                handle = handle,
            )
        } catch (error: Throwable) {
            FrameSamplingResult(FrameSamplingStatus.FAILED, emptyList(), error)
        } finally {
            retriever.release()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true) && executor is ExecutorService) {
            executor.shutdown()
        }
    }
}
